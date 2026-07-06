package com.demo.payment.model;

// Phase 1: javax.validation.constraints.NotBlank → jakarta.validation.constraints.NotBlank
// Phase 3: mutable class → record
import jakarta.validation.constraints.NotBlank;

/**
 * Immutable request record for the refund endpoint.
 *
 * 13 lines → 4 lines. Constructor, accessor, equals, hashCode, toString
 * are all compiler-generated.
 */
public record RefundRequest(
    @NotBlank(message = "Transaction ID is required") String transactionId
) {}
