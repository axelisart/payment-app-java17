package com.demo.payment.service;

import com.demo.payment.model.*;
import com.demo.payment.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
// Phase 2: Collectors import removed — Stream.toList() needs no import

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Random RANDOM = new Random();
    private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("MM/yy");

    // Phase 2: mutable HashMap + static{} block → Map.of() (immutable, one declaration)
    private static final Map<String, String> CARD_TYPES = Map.of(
        "4263970000005262", "Visa",
        "5425230000004415", "MasterCard",
        "374101000000608",  "Amex"
    );

    private final TransactionRepository repo;

    public PaymentService(TransactionRepository repo) {
        this.repo = repo;
    }

    // ── Authorize ──────────────────────────────────────────────────────────

    public TransactionResponse authorize(AuthorizeRequest req) {
        simulateDelay();

        var cardType = resolveCardType(req.cardNumber());  // var: type obvious from context

        // Validate expiry
        if (isExpired(req.expiryDate())) {
            return save(buildDeclined(req, cardType, ResponseCode.EXPIRED_CARD));
        }

        // 10 % random decline
        if (shouldDecline()) {
            var rc = pickDeclineCode();
            log.info("Random decline ({}) for card ending {}", rc, last4(req.cardNumber()));
            return save(buildDeclined(req, cardType, rc));
        }

        // Approved
        var t = new Transaction();
        t.setCardNumber(req.cardNumber());
        t.setExpiryDate(req.expiryDate());
        t.setAmount(req.amount());
        t.setCurrency(req.currency());
        t.setStatus(TransactionStatus.AUTHORIZED);
        t.setResponseCode(ResponseCode.APPROVED.getCode());
        t.setResponseMessage(ResponseCode.APPROVED.getMessage());
        t.setCardType(cardType);
        return TransactionResponse.from(repo.save(t));
    }

    // ── Capture ────────────────────────────────────────────────────────────

    @CacheEvict(value = "transactions", key = "#req.transactionId")
    public TransactionResponse capture(CaptureRequest req) {
        simulateDelay();

        var t = findOrThrow(req.transactionId());

        if (t.getStatus() == TransactionStatus.CAPTURED) {
            return errorResponse(t, ResponseCode.ALREADY_CAPTURED);
        }
        if (t.getStatus() != TransactionStatus.AUTHORIZED) {
            return errorResponse(t, ResponseCode.NOT_AUTHORIZED);
        }

        t.setStatus(TransactionStatus.CAPTURED);
        return TransactionResponse.from(repo.save(t));
    }

    // ── Refund ─────────────────────────────────────────────────────────────

    @CacheEvict(value = "transactions", key = "#req.transactionId")
    public TransactionResponse refund(RefundRequest req) {
        simulateDelay();

        var t = findOrThrow(req.transactionId());

        if (t.getStatus() == TransactionStatus.REFUNDED) {
            return errorResponse(t, ResponseCode.ALREADY_REFUNDED);
        }
        if (t.getStatus() != TransactionStatus.CAPTURED) {
            return errorResponse(t, ResponseCode.NOT_AUTHORIZED);
        }

        t.setStatus(TransactionStatus.REFUNDED);
        return TransactionResponse.from(repo.save(t));
    }

    // ── Get by ID ──────────────────────────────────────────────────────────

    @Cacheable(value = "transactions", key = "#id")
    public Optional<TransactionResponse> getById(String id) {
        return repo.findById(id).map(TransactionResponse::from);
    }

    // ── History ────────────────────────────────────────────────────────────

    public List<TransactionResponse> getHistory() {
        // Phase 2: .collect(Collectors.toList()) → .toList()
        // Stream.toList() returns an unmodifiable list — correct here since the
        // caller never mutates the history result.
        return repo.findTop20ByOrderByCreatedAtDesc()
                   .stream()
                   .map(TransactionResponse::from)
                   .toList();
    }

    // ── Cache clear ────────────────────────────────────────────────────────

    @CacheEvict(value = "transactions", allEntries = true)
    public void clearCache() {
        log.info("Transaction cache cleared");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Transaction findOrThrow(String id) {
        return repo.findById(id).orElseThrow(
            () -> new TransactionNotFoundException(id)
        );
    }

    private boolean isExpired(String expiryDate) {
        try {
            var expiry = YearMonth.parse(expiryDate, EXPIRY_FMT);
            return expiry.isBefore(YearMonth.now());
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private boolean shouldDecline() {
        return RANDOM.nextInt(10) == 0; // 10 %
    }

    // Phase 3: if/if/return chain → switch expression
    private ResponseCode pickDeclineCode() {
        return switch (RANDOM.nextInt(3)) {
            case 0  -> ResponseCode.DECLINED;
            case 1  -> ResponseCode.INSUFFICIENT_FUNDS;
            default -> ResponseCode.EXPIRED_CARD;
        };
    }

    // Phase 3: if/if/if/return chain → Map lookup + switch expression on first char
    private String resolveCardType(String cardNumber) {
        if (cardNumber == null) return "Unknown";
        var stripped = cardNumber.replaceAll("\\s", "");
        if (CARD_TYPES.containsKey(stripped)) return CARD_TYPES.get(stripped);
        if (stripped.isEmpty()) return "Unknown";
        return switch (stripped.charAt(0)) {
            case '4' -> "Visa";
            case '5' -> "MasterCard";
            case '3' -> "Amex";
            default  -> "Unknown";
        };
    }

    private Transaction buildDeclined(AuthorizeRequest req, String cardType, ResponseCode rc) {
        var t = new Transaction();
        t.setCardNumber(req.cardNumber());
        t.setExpiryDate(req.expiryDate());
        t.setAmount(req.amount());
        t.setCurrency(req.currency());
        t.setStatus(TransactionStatus.DECLINED);
        t.setResponseCode(rc.getCode());
        t.setResponseMessage(rc.getMessage());
        t.setCardType(cardType);
        return t;
    }

    private TransactionResponse save(Transaction t) {
        return TransactionResponse.from(repo.save(t));
    }

    private TransactionResponse errorResponse(Transaction t, ResponseCode rc) {
        // Return current state with an overridden response code/message — don't mutate status
        var copy = new Transaction();
        copy.setId(t.getId());
        copy.setCardNumber(t.getCardNumber());
        copy.setExpiryDate(t.getExpiryDate());
        copy.setAmount(t.getAmount());
        copy.setCurrency(t.getCurrency());
        copy.setStatus(t.getStatus());
        copy.setResponseCode(rc.getCode());
        copy.setResponseMessage(rc.getMessage());
        copy.setCardType(t.getCardType());
        copy.setCreatedAt(t.getCreatedAt());
        copy.setUpdatedAt(t.getUpdatedAt());
        return TransactionResponse.from(copy);
    }

    private void simulateDelay() {
        try {
            Thread.sleep(200 + RANDOM.nextInt(301)); // 200–500 ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String last4(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "****";
        return cardNumber.substring(cardNumber.length() - 4);
    }
}
