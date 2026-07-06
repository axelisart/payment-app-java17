package com.demo.payment.controller;

import com.demo.payment.model.*;
import com.demo.payment.service.PaymentService;
import com.demo.payment.service.TransactionNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Phase 1: javax.validation.Valid → jakarta.validation.Valid
import jakarta.validation.Valid;
import java.util.List;

// Phase 3: record accessors (req.cardNumber(), req.transactionId()) are called
// inside PaymentService — the controller itself is unchanged except for the import.
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<TransactionResponse> authorize(@Valid @RequestBody AuthorizeRequest req) {
        return ResponseEntity.ok(paymentService.authorize(req));
    }

    @PostMapping("/capture")
    public ResponseEntity<TransactionResponse> capture(@Valid @RequestBody CaptureRequest req) {
        return ResponseEntity.ok(paymentService.capture(req));
    }

    @PostMapping("/refund")
    public ResponseEntity<TransactionResponse> refund(@Valid @RequestBody RefundRequest req) {
        return ResponseEntity.ok(paymentService.refund(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String id) {
        return paymentService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<TransactionResponse>> getHistory() {
        return ResponseEntity.ok(paymentService.getHistory());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<String> handleNotFound(TransactionNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }
}
