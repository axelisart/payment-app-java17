package com.demo.payment.controller;

import com.demo.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Phase 2: Collections.singletonMap() → Map.of()
//          import java.util.Collections removed
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final PaymentService paymentService;

    public AdminController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache() {
        paymentService.clearCache();
        // Phase 2: Collections.singletonMap() → Map.of()
        return ResponseEntity.ok(Map.of("message", "Cache cleared successfully"));
    }
}
