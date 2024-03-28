package com.generalbytes.batm.server.extensions.extra.atmbiz.dao;

import java.util.List;

public class TransactionInfoResponse {
    private List<Transaction> transactions;

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }
}
