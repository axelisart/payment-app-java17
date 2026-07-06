package com.demo.payment.model;

// No changes from the Java 11 original.
// Enums are the correct abstraction here: flat variants with no per-variant
// behaviour. Converting to sealed classes would add complexity without benefit
// (see §4.2 of JavaModernizationPlan.md).
public enum TransactionStatus {
    AUTHORIZED,
    CAPTURED,
    DECLINED,
    REFUNDED
}
