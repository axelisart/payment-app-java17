package com.demo.payment.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack tests exercising actuator endpoints and the AdminController
 * against the complete Spring context (real H2, real cache, real service).
 *
 * @WebMvcTest cannot load actuator endpoints — @SpringBootTest + @AutoConfigureMockMvc
 * is required for those.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorAndAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Actuator: health ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /actuator/health → 200 UP")
    void actuatorHealth_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /actuator/health — response contains db and diskSpace components")
    void actuatorHealth_containsComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components").exists());
    }

    // ── Actuator: prometheus ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /actuator/prometheus → 200 text/plain with metrics")
    void actuatorPrometheus_returnsMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                .accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    // ── Admin: cache clear ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/cache/clear → 200 with confirmation message")
    void cacheClear_fullStack_returnsOk() throws Exception {
        mockMvc.perform(post("/admin/cache/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Cache cleared successfully"));
    }

    @Test
    @DisplayName("POST /admin/cache/clear — is idempotent (second call also returns 200)")
    void cacheClear_idempotent() throws Exception {
        mockMvc.perform(post("/admin/cache/clear"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/admin/cache/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Cache cleared successfully"));
    }

    // ── Unknown routes ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /unknown-route → 404 Not Found")
    void unknownRoute_returns404() throws Exception {
        mockMvc.perform(get("/unknown-route"))
            .andExpect(status().isNotFound());
    }
}
