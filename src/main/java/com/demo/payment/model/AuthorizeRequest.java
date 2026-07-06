package com.demo.payment.model;

// Phase 1: javax.validation.constraints.* → jakarta.validation.constraints.*
// Phase 3: class with getters/setters → record
//
// Bean Validation annotations are fully supported on record components in Java 16+.
// Jackson 2.12+ (bundled with Spring Boot 3) deserialises records via their
// canonical constructor — no @JsonProperty annotations are needed.
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Immutable request record for the authorize endpoint.
 *
 * Record accessor names match the JSON field names exactly, so Jackson's
 * default property detection works without any additional configuration.
 * Spring's @Valid triggers Bean Validation on each component annotation.
 */
public record AuthorizeRequest(

    @NotBlank(message = "Card number is required")
    @Size(min = 13, max = 19, message = "Card number must be 13-19 digits")
    String cardNumber,

    @NotBlank(message = "Expiry date is required")
    @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}$", message = "Expiry must be MM/YY")
    String expiryDate,

    @NotBlank(message = "CVV is required")
    @Pattern(regexp = "^\\d{3,4}$", message = "CVV must be 3 or 4 digits")
    String cvv,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be positive")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,

    String currency

) {
    /**
     * Compact canonical constructor: apply the default currency when the caller
     * omits it (null). Records do not support field initializers, so this is
     * the idiomatic place for defaulting logic.
     */
    public AuthorizeRequest {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
    }
}
