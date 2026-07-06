package com.demo.payment.service;

// No changes from the Java 11 original — no javax.*, no deprecated APIs.
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(String id) {
        super("Transaction not found: " + id);
    }
}
