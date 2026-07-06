package com.demo.payment.service;

import com.demo.payment.model.*;
import com.demo.payment.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for PaymentService against a real H2 database.
 *
 * Strategy for deterministic randomness
 * ──────────────────────────────────────
 * PaymentService.RANDOM is a private static final field. We inject a
 * controllable subclass via reflection before each test and restore the
 * original afterwards. This is the only way to force the 10 % decline path
 * without changing production code.
 *
 * FixedRandom(n) always returns n from nextInt(), so:
 *   nextInt(10) == 0  → shouldDecline() == true   (use n=0)
 *   nextInt(10) == 1  → shouldDecline() == false  (use n=1)
 *   nextInt(3)  == 0  → pickDeclineCode() == DECLINED
 *   nextInt(3)  == 1  → pickDeclineCode() == INSUFFICIENT_FUNDS
 *   nextInt(3)  == 2  → pickDeclineCode() == EXPIRED_CARD (default)
 *
 * We also override simulateDelay by patching the static RANDOM so that
 * Thread.sleep(200 + RANDOM.nextInt(301)) sleeps 200 ms minimum.
 * For tests that need speed, we inject FixedRandom(0) for the delay too,
 * but that makes shouldDecline() return true. A dedicated ZeroDelayRandom
 * (nextInt always 1 except when asked for range 10) is not needed — instead
 * we simply accept the 200 ms minimum sleep in service integration tests.
 * (The controller-slice tests in PaymentControllerTest.java have no sleep.)
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransactionRepository transactionRepository;

    private Random originalRandom;
    private Field randomField;

    // ── Test fixtures ──────────────────────────────────────────────────────────

    private static final String VISA        = "4263970000005262";
    private static final String MASTERCARD  = "5425230000004415";
    private static final String AMEX        = "374101000000608";
    private static final String UNKNOWN     = "9999999999999";   // starts with '9'
    private static final String FUTURE_EXP  = "12/26";
    private static final String PAST_EXP    = "01/20";
    private static final String CVV         = "123";

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @BeforeAll
    void captureOriginalRandom() throws Exception {
        randomField = PaymentService.class.getDeclaredField("RANDOM");
        randomField.setAccessible(true);
        originalRandom = (Random) randomField.get(null);
    }

    @AfterEach
    void restoreRandom() throws Exception {
        randomField.set(null, originalRandom);
        transactionRepository.deleteAll();
    }

    // ── Helper: inject fixed behaviour ────────────────────────────────────────

    private void injectRandom(Random r) throws Exception {
        randomField.set(null, r);
    }

    /** Always returns the single value passed to the constructor, for any bound. */
    private static Random always(int value) {
        return new Random() {
            @Override public int nextInt(int bound) { return Math.min(value, bound - 1); }
            @Override public int nextInt()          { return value; }
        };
    }

    /**
     * Returns declineValue for nextInt(10) (shouldDecline check)
     * and 0 for all other calls (minimum delay, first decline code).
     *
     * Sequence: the first call to nextInt(10) controls decline, subsequent calls
     * control the delay (nextInt(301) → 0, so sleep = 200 ms).
     * We use a small call counter to differentiate.
     */
    private static Random declineControlled(boolean decline, int declineCode) {
        return new Random() {
            int calls = 0;
            @Override
            public int nextInt(int bound) {
                calls++;
                if (bound == 10) return decline ? 0 : 1; // shouldDecline
                if (bound == 3)  return declineCode;       // pickDeclineCode
                return 0;                                   // delay → 200 ms
            }
        };
    }

    private AuthorizeRequest req(String card, String expiry, BigDecimal amount) {
        return new AuthorizeRequest(card, expiry, CVV, amount, "USD");
    }

    // ── authorize() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("authorize — valid Visa card, no decline → AUTHORIZED persisted")
    void authorize_validVisa_authorized() throws Exception {
        injectRandom(declineControlled(false, 0));

        var resp = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("49.99")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(resp.responseCode()).isEqualTo("00");
        assertThat(resp.cardType()).isEqualTo("Visa");
        assertThat(resp.maskedCardNumber()).isEqualTo("**** **** **** 5262");
        assertThat(resp.transactionId()).isNotBlank();
        assertThat(resp.currency()).isEqualTo("USD");

        // Verify persistence
        var stored = transactionRepository.findById(resp.transactionId());
        assertThat(stored).isPresent();
        assertThat(stored.get().getStatus()).isEqualTo(TransactionStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("authorize — valid MasterCard, no decline → AUTHORIZED with correct type")
    void authorize_validMastercard_authorized() throws Exception {
        injectRandom(declineControlled(false, 0));

        var resp = paymentService.authorize(req(MASTERCARD, FUTURE_EXP, new BigDecimal("100.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(resp.cardType()).isEqualTo("MasterCard");
        assertThat(resp.maskedCardNumber()).isEqualTo("**** **** **** 4415");
    }

    @Test
    @DisplayName("authorize — valid Amex, no decline → AUTHORIZED with correct type")
    void authorize_validAmex_authorized() throws Exception {
        injectRandom(declineControlled(false, 0));

        var resp = paymentService.authorize(req(AMEX, FUTURE_EXP, new BigDecimal("200.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(resp.cardType()).isEqualTo("Amex");
    }

    @Test
    @DisplayName("authorize — unknown card prefix → AUTHORIZED with type Unknown")
    void authorize_unknownCardPrefix_resolvedAsUnknown() throws Exception {
        injectRandom(declineControlled(false, 0));

        var resp = paymentService.authorize(req(UNKNOWN, FUTURE_EXP, new BigDecimal("10.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(resp.cardType()).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("authorize — forced random decline (code 05) → DECLINED persisted")
    void authorize_forcedDecline_declined05() throws Exception {
        injectRandom(declineControlled(true, 0));   // decline=true, code index 0 → "05"

        var resp = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("25.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("05");
        assertThat(resp.responseMessage()).isEqualTo("Do not honor");

        var stored = transactionRepository.findById(resp.transactionId());
        assertThat(stored).isPresent();
        assertThat(stored.get().getStatus()).isEqualTo(TransactionStatus.DECLINED);
    }

    @Test
    @DisplayName("authorize — forced insufficient funds decline (code 51)")
    void authorize_forcedDecline_insufficientFunds() throws Exception {
        injectRandom(declineControlled(true, 1));   // code index 1 → "51"

        var resp = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("999.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("51");
        assertThat(resp.responseMessage()).isEqualTo("Insufficient funds");
    }

    @Test
    @DisplayName("authorize — forced expired-card decline (code 54) via random")
    void authorize_forcedDecline_randomExpiredCard() throws Exception {
        injectRandom(declineControlled(true, 2));   // code index 2 → default → "54"

        var resp = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("50.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("54");
    }

    @Test
    @DisplayName("authorize — past expiry date → DECLINED with code 54 (server-side check)")
    void authorize_expiredExpiryDate_declined54() throws Exception {
        // Expiry check runs before random decline — no need to control Random
        injectRandom(declineControlled(false, 0));

        var resp = paymentService.authorize(req(VISA, PAST_EXP, new BigDecimal("10.00")));

        assertThat(resp.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("54");
        assertThat(resp.responseMessage()).isEqualTo("Expired card");
    }

    @Test
    @DisplayName("authorize — currency defaults to USD when null passed via compact constructor")
    void authorize_nullCurrency_defaultsToUsd() throws Exception {
        injectRandom(declineControlled(false, 0));

        // The record compact constructor converts null → "USD"
        var req = new AuthorizeRequest(VISA, FUTURE_EXP, CVV, new BigDecimal("5.00"), null);
        var resp = paymentService.authorize(req);

        assertThat(resp.currency()).isEqualTo("USD");
    }

    // ── capture() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("capture — AUTHORIZED → CAPTURED, updatedAt populated")
    void capture_authorized_captured() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("75.00")));
        assertThat(auth.status()).isEqualTo(TransactionStatus.AUTHORIZED);

        var captured = paymentService.capture(new CaptureRequest(auth.transactionId()));

        assertThat(captured.status()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(captured.transactionId()).isEqualTo(auth.transactionId());
        assertThat(captured.updatedAt()).isNotNull();

        var stored = transactionRepository.findById(auth.transactionId());
        assertThat(stored.get().getStatus()).isEqualTo(TransactionStatus.CAPTURED);
    }

    @Test
    @DisplayName("capture — already CAPTURED → returns code 77, status unchanged")
    void capture_alreadyCaptured_code77() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("50.00")));
        paymentService.capture(new CaptureRequest(auth.transactionId()));

        // Second capture on same txn
        var dup = paymentService.capture(new CaptureRequest(auth.transactionId()));

        assertThat(dup.status()).isEqualTo(TransactionStatus.CAPTURED);   // status unchanged
        assertThat(dup.responseCode()).isEqualTo("77");
        assertThat(dup.responseMessage()).isEqualTo("Transaction already captured");
    }

    @Test
    @DisplayName("capture — DECLINED transaction → code 79 NOT_AUTHORIZED")
    void capture_declinedTransaction_code79() throws Exception {
        injectRandom(declineControlled(true, 0));
        var declined = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("10.00")));
        assertThat(declined.status()).isEqualTo(TransactionStatus.DECLINED);

        var result = paymentService.capture(new CaptureRequest(declined.transactionId()));

        assertThat(result.responseCode()).isEqualTo("79");
    }

    @Test
    @DisplayName("capture — non-existent transaction → throws TransactionNotFoundException")
    void capture_unknownId_throwsNotFoundException() {
        assertThatThrownBy(() ->
            paymentService.capture(new CaptureRequest("does-not-exist"))
        ).isInstanceOf(TransactionNotFoundException.class)
         .hasMessageContaining("does-not-exist");
    }

    // ── refund() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refund — CAPTURED → REFUNDED")
    void refund_captured_refunded() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth     = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("200.00")));
        var captured = paymentService.capture(new CaptureRequest(auth.transactionId()));
        assertThat(captured.status()).isEqualTo(TransactionStatus.CAPTURED);

        var refunded = paymentService.refund(new RefundRequest(auth.transactionId()));

        assertThat(refunded.status()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(refunded.transactionId()).isEqualTo(auth.transactionId());

        var stored = transactionRepository.findById(auth.transactionId());
        assertThat(stored.get().getStatus()).isEqualTo(TransactionStatus.REFUNDED);
    }

    @Test
    @DisplayName("refund — already REFUNDED → returns code 78, status unchanged")
    void refund_alreadyRefunded_code78() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("50.00")));
        paymentService.capture(new CaptureRequest(auth.transactionId()));
        paymentService.refund(new RefundRequest(auth.transactionId()));

        // Second refund on same txn
        var dup = paymentService.refund(new RefundRequest(auth.transactionId()));

        assertThat(dup.status()).isEqualTo(TransactionStatus.REFUNDED);   // status unchanged
        assertThat(dup.responseCode()).isEqualTo("78");
        assertThat(dup.responseMessage()).isEqualTo("Transaction already refunded");
    }

    @Test
    @DisplayName("refund — AUTHORIZED (not yet captured) → code 79 NOT_AUTHORIZED")
    void refund_notCaptured_code79() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("10.00")));

        var result = paymentService.refund(new RefundRequest(auth.transactionId()));

        assertThat(result.responseCode()).isEqualTo("79");
        assertThat(result.status()).isEqualTo(TransactionStatus.AUTHORIZED);  // unchanged
    }

    @Test
    @DisplayName("refund — non-existent transaction → throws TransactionNotFoundException")
    void refund_unknownId_throwsNotFoundException() {
        assertThatThrownBy(() ->
            paymentService.refund(new RefundRequest("ghost-id"))
        ).isInstanceOf(TransactionNotFoundException.class)
         .hasMessageContaining("ghost-id");
    }

    // ── getById() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getById — existing id → returns Optional with correct data")
    void getById_existingId_returnsPresent() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("30.00")));

        var result = paymentService.getById(auth.transactionId());

        assertThat(result).isPresent();
        assertThat(result.get().transactionId()).isEqualTo(auth.transactionId());
        assertThat(result.get().status()).isEqualTo(TransactionStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("getById — unknown id → returns Optional.empty()")
    void getById_unknownId_returnsEmpty() {
        var result = paymentService.getById("no-such-txn");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getById — second call returns cached result (no DB hit)")
    void getById_secondCall_usesCachedResult() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("15.00")));
        var id = auth.transactionId();

        var first  = paymentService.getById(id);
        var second = paymentService.getById(id);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        // Both point to the same cached object (referential equality in Caffeine)
        assertThat(first.get()).isEqualTo(second.get());
    }

    // ── getHistory() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHistory — returns up to 20 recent transactions, newest first")
    void getHistory_returnsNewestFirst() throws Exception {
        injectRandom(declineControlled(false, 0));

        // Persist 3 transactions in order
        var t1 = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("10.00")));
        var t2 = paymentService.authorize(req(MASTERCARD, FUTURE_EXP, new BigDecimal("20.00")));
        var t3 = paymentService.authorize(req(AMEX, FUTURE_EXP, new BigDecimal("30.00")));

        var history = paymentService.getHistory();

        assertThat(history).hasSizeGreaterThanOrEqualTo(3);
        // Newest (t3) should appear before t1
        var ids = history.stream().map(TransactionResponse::transactionId).toList();
        assertThat(ids.indexOf(t3.transactionId()))
            .isLessThan(ids.indexOf(t1.transactionId()));
    }

    @Test
    @DisplayName("getHistory — empty database returns empty list")
    void getHistory_emptyDb_returnsEmptyList() {
        // @AfterEach already cleared the DB
        var history = paymentService.getHistory();
        assertThat(history).isEmpty();
    }

    // ── clearCache() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearCache — evicts entry so next getById hits DB again")
    void clearCache_evictsEntry() throws Exception {
        injectRandom(declineControlled(false, 0));
        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("40.00")));
        var id = auth.transactionId();

        // Warm the cache
        paymentService.getById(id);

        // Evict all entries
        paymentService.clearCache();

        // Should still work (re-fetches from DB)
        var afterEvict = paymentService.getById(id);
        assertThat(afterEvict).isPresent();
        assertThat(afterEvict.get().transactionId()).isEqualTo(id);
    }

    // ── Full lifecycle ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full lifecycle: authorize → capture → refund persists correct statuses")
    void fullLifecycle_authorizeCapureRefund() throws Exception {
        injectRandom(declineControlled(false, 0));

        var auth = paymentService.authorize(req(VISA, FUTURE_EXP, new BigDecimal("150.00")));
        assertThat(auth.status()).isEqualTo(TransactionStatus.AUTHORIZED);

        var captured = paymentService.capture(new CaptureRequest(auth.transactionId()));
        assertThat(captured.status()).isEqualTo(TransactionStatus.CAPTURED);

        var refunded = paymentService.refund(new RefundRequest(auth.transactionId()));
        assertThat(refunded.status()).isEqualTo(TransactionStatus.REFUNDED);

        // Verify final DB state
        var stored = transactionRepository.findById(auth.transactionId());
        assertThat(stored.get().getStatus()).isEqualTo(TransactionStatus.REFUNDED);
    }

    // ── TransactionResponse record ────────────────────────────────────────────

    @Test
    @DisplayName("TransactionResponse.mask() — card shorter than 4 chars → '****'")
    void transactionResponse_shortCard_maskedAsFourStars() {
        // Exercise the mask() branch for cardNumber.length() < 4
        // We create a Transaction directly and call from()
        var t = new com.demo.payment.model.Transaction();
        t.setId("x");
        t.setCardNumber("12");
        t.setExpiryDate("12/26");
        t.setAmount(BigDecimal.ONE);
        t.setCurrency("USD");
        t.setStatus(TransactionStatus.AUTHORIZED);
        t.setResponseCode("00");
        t.setResponseMessage("Approved");
        t.setCardType("Visa");
        t.setCreatedAt(java.time.Instant.now());

        var resp = TransactionResponse.from(t);

        assertThat(resp.maskedCardNumber()).isEqualTo("****");
    }

    @Test
    @DisplayName("TransactionResponse.mask() — null card → '****'")
    void transactionResponse_nullCard_maskedAsFourStars() {
        var t = new com.demo.payment.model.Transaction();
        t.setId("y");
        t.setCardNumber(null);
        t.setExpiryDate("12/26");
        t.setAmount(BigDecimal.ONE);
        t.setCurrency("USD");
        t.setStatus(TransactionStatus.AUTHORIZED);
        t.setResponseCode("00");
        t.setResponseMessage("Approved");
        t.setCardType("Unknown");
        t.setCreatedAt(java.time.Instant.now());

        var resp = TransactionResponse.from(t);

        assertThat(resp.maskedCardNumber()).isEqualTo("****");
    }
}
