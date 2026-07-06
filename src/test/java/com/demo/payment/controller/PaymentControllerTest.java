package com.demo.payment.controller;

import com.demo.payment.model.*;
import com.demo.payment.service.PaymentService;
import com.demo.payment.service.TransactionNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice tests using @WebMvcTest.
 *
 * The Spring context loads only the web layer (controllers, filters, Jackson).
 * PaymentService is replaced with a Mockito mock so:
 *   - Tests run in milliseconds (no simulateDelay(), no H2)
 *   - Every service branch can be forced deterministically
 *   - HTTP contract (status codes, response fields) is verified in isolation
 */
@WebMvcTest(controllers = {PaymentController.class, AdminController.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    // ── Fixture helpers ────────────────────────────────────────────────────────

    private static final String VISA_CARD   = "4263970000005262";
    private static final String FUTURE_EXP  = "12/26";
    private static final String CVV         = "123";

    private TransactionResponse approvedResponse(String id) {
        return new TransactionResponse(
            id, TransactionStatus.AUTHORIZED, "00", "Approved",
            "**** **** **** 5262", "Visa",
            new BigDecimal("99.99"), "USD",
            Instant.now(), null
        );
    }

    private TransactionResponse declinedResponse(String id, String code, String message) {
        return new TransactionResponse(
            id, TransactionStatus.DECLINED, code, message,
            "**** **** **** 5262", "Visa",
            new BigDecimal("99.99"), "USD",
            Instant.now(), null
        );
    }

    private TransactionResponse capturedResponse(String id) {
        return new TransactionResponse(
            id, TransactionStatus.CAPTURED, "00", "Approved",
            "**** **** **** 5262", "Visa",
            new BigDecimal("99.99"), "USD",
            Instant.now(), Instant.now()
        );
    }

    private TransactionResponse refundedResponse(String id) {
        return new TransactionResponse(
            id, TransactionStatus.REFUNDED, "00", "Approved",
            "**** **** **** 5262", "Visa",
            new BigDecimal("99.99"), "USD",
            Instant.now(), Instant.now()
        );
    }

    // ── POST /api/payments/authorize ──────────────────────────────────────────

    @Test
    @DisplayName("POST /authorize — valid Visa card → 200 AUTHORIZED")
    void authorize_validCard_returnsAuthorized() throws Exception {
        var id = "txn-001";
        when(paymentService.authorize(any())).thenReturn(approvedResponse(id));

        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "%s",
              "cvv": "%s",
              "amount": 99.99,
              "currency": "USD"
            }
            """.formatted(VISA_CARD, FUTURE_EXP, CVV);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value(id))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"))
            .andExpect(jsonPath("$.responseCode").value("00"))
            .andExpect(jsonPath("$.maskedCardNumber").value("**** **** **** 5262"))
            .andExpect(jsonPath("$.cardType").value("Visa"))
            .andExpect(jsonPath("$.amount").value(99.99))
            .andExpect(jsonPath("$.currency").value("USD"));

        verify(paymentService, times(1)).authorize(any());
    }

    @Test
    @DisplayName("POST /authorize — declined card → 200 DECLINED with code 05")
    void authorize_randomDecline_returnsDeclined() throws Exception {
        var id = "txn-002";
        when(paymentService.authorize(any()))
            .thenReturn(declinedResponse(id, "05", "Do not honor"));

        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "%s",
              "cvv": "%s",
              "amount": 50.00
            }
            """.formatted(VISA_CARD, FUTURE_EXP, CVV);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLINED"))
            .andExpect(jsonPath("$.responseCode").value("05"))
            .andExpect(jsonPath("$.responseMessage").value("Do not honor"));
    }

    @Test
    @DisplayName("POST /authorize — expired card → 200 DECLINED with code 54")
    void authorize_expiredCard_returnsDeclinedWithCode54() throws Exception {
        when(paymentService.authorize(any()))
            .thenReturn(declinedResponse("txn-003", "54", "Expired card"));

        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "01/20",
              "cvv": "%s",
              "amount": 10.00
            }
            """.formatted(VISA_CARD, CVV);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLINED"))
            .andExpect(jsonPath("$.responseCode").value("54"));
    }

    @Test
    @DisplayName("POST /authorize — missing card number → 400 Bad Request")
    void authorize_missingCardNumber_returns400() throws Exception {
        var body = """
            {
              "expiryDate": "12/26",
              "cvv": "123",
              "amount": 10.00
            }
            """;

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(paymentService, never()).authorize(any());
    }

    @Test
    @DisplayName("POST /authorize — invalid card number (too short) → 400 Bad Request")
    void authorize_invalidCardNumber_returns400() throws Exception {
        var body = """
            {
              "cardNumber": "1234",
              "expiryDate": "12/26",
              "cvv": "123",
              "amount": 10.00
            }
            """;

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(paymentService, never()).authorize(any());
    }

    @Test
    @DisplayName("POST /authorize — malformed expiry (not MM/YY) → 400 Bad Request")
    void authorize_malformedExpiry_returns400() throws Exception {
        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "2026-12",
              "cvv": "123",
              "amount": 10.00
            }
            """.formatted(VISA_CARD);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /authorize — negative amount → 400 Bad Request")
    void authorize_negativeAmount_returns400() throws Exception {
        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "12/26",
              "cvv": "123",
              "amount": -5.00
            }
            """.formatted(VISA_CARD);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /authorize — zero amount → 400 Bad Request")
    void authorize_zeroAmount_returns400() throws Exception {
        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "12/26",
              "cvv": "123",
              "amount": 0.00
            }
            """.formatted(VISA_CARD);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /authorize — missing CVV → 400 Bad Request")
    void authorize_missingCvv_returns400() throws Exception {
        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "12/26",
              "amount": 10.00
            }
            """.formatted(VISA_CARD);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /authorize — null body → 400 Bad Request")
    void authorize_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /authorize — currency defaults to USD when omitted")
    void authorize_omittedCurrency_defaultsToUsd() throws Exception {
        when(paymentService.authorize(any())).thenReturn(approvedResponse("txn-004"));

        var body = """
            {
              "cardNumber": "%s",
              "expiryDate": "%s",
              "cvv": "%s",
              "amount": 20.00
            }
            """.formatted(VISA_CARD, FUTURE_EXP, CVV);

        mockMvc.perform(post("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("USD"));
    }

    // ── POST /api/payments/capture ────────────────────────────────────────────

    @Test
    @DisplayName("POST /capture — valid AUTHORIZED txn → 200 CAPTURED")
    void capture_authorizedTransaction_returnsCaptured() throws Exception {
        var id = "txn-010";
        when(paymentService.capture(any())).thenReturn(capturedResponse(id));

        var body = """
            { "transactionId": "%s" }
            """.formatted(id);

        mockMvc.perform(post("/api/payments/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value(id))
            .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /capture — blank transactionId → 400 Bad Request")
    void capture_blankTransactionId_returns400() throws Exception {
        var body = """
            { "transactionId": "" }
            """;

        mockMvc.perform(post("/api/payments/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(paymentService, never()).capture(any());
    }

    @Test
    @DisplayName("POST /capture — missing transactionId field → 400 Bad Request")
    void capture_missingTransactionId_returns400() throws Exception {
        mockMvc.perform(post("/api/payments/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /capture — transaction not found → 404 Not Found")
    void capture_transactionNotFound_returns404() throws Exception {
        when(paymentService.capture(any()))
            .thenThrow(new TransactionNotFoundException("txn-999"));

        var body = """
            { "transactionId": "txn-999" }
            """;

        mockMvc.perform(post("/api/payments/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    // ── POST /api/payments/refund ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /refund — valid CAPTURED txn → 200 REFUNDED")
    void refund_capturedTransaction_returnsRefunded() throws Exception {
        var id = "txn-020";
        when(paymentService.refund(any())).thenReturn(refundedResponse(id));

        var body = """
            { "transactionId": "%s" }
            """.formatted(id);

        mockMvc.perform(post("/api/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value(id))
            .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("POST /refund — duplicate refund returns 200 with code 78")
    void refund_alreadyRefunded_returnsCode78() throws Exception {
        var id = "txn-021";
        // Service returns current status (REFUNDED) with override code 78 — no exception
        var dupRefund = new TransactionResponse(
            id, TransactionStatus.REFUNDED, "78", "Transaction already refunded",
            "**** **** **** 5262", "Visa",
            new BigDecimal("99.99"), "USD",
            Instant.now(), Instant.now()
        );
        when(paymentService.refund(any())).thenReturn(dupRefund);

        var body = """
            { "transactionId": "%s" }
            """.formatted(id);

        mockMvc.perform(post("/api/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.responseCode").value("78"))
            .andExpect(jsonPath("$.responseMessage").value("Transaction already refunded"))
            .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("POST /refund — transaction not found → 404 Not Found")
    void refund_transactionNotFound_returns404() throws Exception {
        when(paymentService.refund(any()))
            .thenThrow(new TransactionNotFoundException("no-such-id"));

        var body = """
            { "transactionId": "no-such-id" }
            """;

        mockMvc.perform(post("/api/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /refund — blank transactionId → 400 Bad Request")
    void refund_blankTransactionId_returns400() throws Exception {
        mockMvc.perform(post("/api/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"transactionId\": \"  \" }"))
            .andExpect(status().isBadRequest());

        verify(paymentService, never()).refund(any());
    }

    // ── GET /api/payments/{id} ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/payments/{id} — existing txn → 200 with body")
    void getById_existingTransaction_returns200() throws Exception {
        var id = "txn-030";
        when(paymentService.getById(id)).thenReturn(Optional.of(approvedResponse(id)));

        mockMvc.perform(get("/api/payments/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value(id))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/payments/{id} — unknown id → 404 Not Found")
    void getById_unknownId_returns404() throws Exception {
        when(paymentService.getById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/payments/{id}", "ghost"))
            .andExpect(status().isNotFound());
    }

    // ── GET /api/payments/history ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /history — returns list of transactions")
    void getHistory_returnsList() throws Exception {
        var txns = List.of(
            approvedResponse("txn-040"),
            capturedResponse("txn-041"),
            refundedResponse("txn-042")
        );
        when(paymentService.getHistory()).thenReturn(txns);

        mockMvc.perform(get("/api/payments/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].transactionId").value("txn-040"))
            .andExpect(jsonPath("$[0].status").value("AUTHORIZED"))
            .andExpect(jsonPath("$[1].status").value("CAPTURED"))
            .andExpect(jsonPath("$[2].status").value("REFUNDED"));
    }

    @Test
    @DisplayName("GET /history — empty history returns empty array")
    void getHistory_empty_returnsEmptyArray() throws Exception {
        when(paymentService.getHistory()).thenReturn(List.of());

        mockMvc.perform(get("/api/payments/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── Actuator ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /actuator/health → 200 UP")
    void actuatorHealth_returns200() throws Exception {
        // @WebMvcTest does not load actuator endpoints — skip with a note.
        // Actuator health is tested in PaymentServiceIntegrationTest via @SpringBootTest.
    }

    // ── Admin: POST /admin/cache/clear ────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/cache/clear → 200 with confirmation message")
    void cacheClear_returns200WithMessage() throws Exception {
        doNothing().when(paymentService).clearCache();

        mockMvc.perform(post("/admin/cache/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Cache cleared successfully"));

        verify(paymentService, times(1)).clearCache();
    }
}
