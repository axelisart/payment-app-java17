package com.demo.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies the Spring application context loads without errors.
 * If this fails, all other tests will too.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentApplicationTest {

    @Test
    void contextLoads() {
        // Passes if the context starts without throwing an exception.
    }
}
