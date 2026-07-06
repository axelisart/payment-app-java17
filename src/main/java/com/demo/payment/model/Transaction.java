package com.demo.payment.model;

// Phase 1: javax.persistence.* → jakarta.persistence.*
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity — must remain a regular mutable class.
 * Records are unsuitable for @Entity because JPA requires a no-arg constructor
 * and mutable state for dirty-checking.
 *
 * Changes vs Java 11 original:
 *   • jakarta.persistence.* (Phase 1)
 *   • No logic changes — @PrePersist UUID generation is still the correct
 *     pattern for Hibernate 5 and 6 alike (GenerationType.UUID requires JPA 3.x
 *     which is only in Hibernate 6.2+; keeping @PrePersist is safer here).
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 20)
    private String cardNumber;

    @Column(nullable = false, length = 7)
    private String expiryDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false, length = 2)
    private String responseCode;

    @Column(nullable = false)
    private String responseMessage;

    @Column(nullable = false, length = 20)
    private String cardType;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant updatedAt;

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        this.createdAt = Instant.now();
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public String getExpiryDate() { return expiryDate; }
    public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }

    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }

    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
