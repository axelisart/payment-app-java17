package com.demo.payment.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phase 3: mutable class with static factory → record with static factory.
 *
 * Records are an ideal fit for read-only response DTOs. The static {@code from()}
 * factory is kept as a companion method on the record itself — this is the
 * idiomatic Java 17 pattern when the record needs to be built from a different
 * type (here: Transaction → TransactionResponse).
 *
 * Jackson 2.12+ (Spring Boot 3) serialises records to JSON without any extra
 * configuration: accessor names (e.g. transactionId()) map 1-to-1 to JSON
 * field names.
 */
public record TransactionResponse(
    String transactionId,
    TransactionStatus status,
    String responseCode,
    String responseMessage,
    String maskedCardNumber,
    String cardType,
    BigDecimal amount,
    String currency,
    Instant createdAt,
    Instant updatedAt
) {
    // ── Static factory ─────────────────────────────────────────────────────

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
            t.getId(),
            t.getStatus(),
            t.getResponseCode(),
            t.getResponseMessage(),
            mask(t.getCardNumber()),
            t.getCardType(),
            t.getAmount(),
            t.getCurrency(),
            t.getCreatedAt(),
            t.getUpdatedAt()
        );
    }

    // ── Private helper — visible inside the record body ────────────────────

    private static String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "****";
        return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }
}
