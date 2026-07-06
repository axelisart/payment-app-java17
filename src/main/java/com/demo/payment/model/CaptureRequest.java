package com.demo.payment.model;

// Phase 1: javax.validation.constraints.NotBlank → jakarta.validation.constraints.NotBlank
// Phase 3: mutable class → record
import jakarta.validation.constraints.NotBlank;

/**
 * Immutable request record for the capture endpoint.
 *
 * 13 lines → 4 lines. Constructor, accessor, equals, hashCode, toString
 * are all compiler-generated.
 */
public record CaptureRequest(
    @NotBlank(message = "Transaction ID is required") String transactionId
) {}
