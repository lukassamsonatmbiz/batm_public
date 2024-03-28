package com.generalbytes.batm.server.extensions.extra.atmbiz.mq;

import com.generalbytes.batm.server.extensions.extra.atmbiz.AtmbizExtension;
import com.generalbytes.batm.server.extensions.extra.atmbiz.controller.AtmbizRPCController;
import com.generalbytes.batm.server.extensions.extra.atmbiz.dao.ErrorMessage;
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public class RPCServer implements AutoCloseable, Runnable {
    private static final String ATMBIZ_CONFIG = "atmbiz";

    private static final String MQ_HOST = AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG, "MQ_HOST", "localhost");
    private static final int MQ_PORT = Integer.parseInt(AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG, "MQ_PORT", "5672"));
    private static final String MQ_USER = AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG, "MQ_USER", "user");
    private static final String MQ_PASSWORD = AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG, "MQ_PASSWORD", "password");
    private static final String MQ_PREFIX = AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG,"MQ_PREFIX", "prefix");
    private static final Boolean MQ_SSL = AtmbizExtension.getExtensionContext().getConfigProperty(ATMBIZ_CONFIG,"MQ_SSL", "true").equals("true");

    private static final String TERMINALS_INPUT = MQ_PREFIX + "_terminals_input";
    private static final String TERMINALS_OUTPUT = MQ_PREFIX + "_terminals_output";

    private static final String SELL_CRYPTO_INPUT = MQ_PREFIX + "_sell_crypto_input";
    private static final String SELL_CRYPTO_OUTPUT = MQ_PREFIX + "_sell_crypto_output";

    private static final String TRANSACTION_INFO_INPUT = MQ_PREFIX + "_transaction_info_input";
    private static final String TRANSACTION_INFO_OUTPUT = MQ_PREFIX + "_transaction_info_output";

    private static final Logger log = LoggerFactory.getLogger(RPCServer.class);
    private boolean running = true;
    private Connection connection;
    private Channel channel;

    public RPCServer(){
        log.info("Starting RPC server");
    }

    private void reconnect() {
        while (true) {
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(MQ_HOST);
                factory.setPort(MQ_PORT);
                factory.setUsername(MQ_USER);
                factory.setPassword(MQ_PASSWORD);

                if (MQ_SSL) {
                    factory.useSslProtocol();
                }

                connection = factory.newConnection();
                channel = connection.createChannel();

                log.info("Connection successful.");

                break; // Exit loop if connection is successful
            } catch (Exception e) {
                log.error("Connection failed, retrying...", e);
                try {
                    Thread.sleep(5000); // Wait for 5 seconds before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString(); // stack trace as a string
    }

    private static void handleRequests(Channel channel, String inputQueue, String outputQueue, ProcessingFunction function) throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            AMQP.BasicProperties replyProps = new AMQP.BasicProperties
                    .Builder()
                    .correlationId(delivery.getProperties().getCorrelationId())
                    .build();

            String response = "";

            try {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                response = function.process(message);
            } catch (ErrorMessage error){
                log.error(error.getMessage());
                log.error(error.toString());
                response = error.toString();
            } catch (Exception e) {
                log.error(e.getMessage());
                log.error(getStackTraceAsString(e));
                log.error(e.toString());
                response = e.toString();
            } finally {
                channel.basicPublish(MQ_PREFIX + "_direct", outputQueue, replyProps, response.getBytes(StandardCharsets.UTF_8));
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(inputQueue, false, deliverCallback, (consumerTag -> {
        }));
    }

    public synchronized void stop() {
        running = false;
        notifyAll(); // To wake up the thread
    }

    @Override
    public void close() throws Exception {
        if (channel != null) {
            channel.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void run() {
        reconnect();
        log.info("Atmbiz plugin started");
        while (running) {
            try {
                log.info(" [x] Awaiting RPC requests");
                handleRequests(channel, TERMINALS_INPUT, TERMINALS_OUTPUT, AtmbizRPCController::terminalsRpc);
                handleRequests(channel, SELL_CRYPTO_INPUT, SELL_CRYPTO_OUTPUT, AtmbizRPCController::sellCryptoRpc);
                handleRequests(channel, TRANSACTION_INFO_INPUT, TRANSACTION_INFO_OUTPUT, AtmbizRPCController::getTransactionInfo);

                synchronized (this) {
                    while (running) {
                        wait();
                    }
                }
            } catch (IOException e) {
                log.error("IOException in run, attempting to reconnect...", e);
                reconnect();
            } catch (Exception e) {
                log.error("Unexpected error, stopping server", e);
                running = false;
            }
        }
    }

    @FunctionalInterface
    private interface ProcessingFunction {
        String process(String request) throws ErrorMessage;
    }
}
