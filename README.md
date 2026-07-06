# Payment App — Java 17 Edition

A modernized version of the mock credit card payment gateway, upgraded from
Java 11 / Spring Boot 2.7 to **Java 17 / Spring Boot 3.3.5**.

Run with a single command:

```bash
cd payment-app-java17
mvn spring-boot:run
```

Open **http://localhost:8080** in your browser.

---

## What Changed From the Java 11 Original

This folder is the output of executing `JavaModernizationPlan.md`. All three
phases have been applied.

### Phase 1 — Mandatory breaking changes

| File | Change |
|---|---|
| `pom.xml` | `spring-boot-starter-parent` 2.7.18 → **3.3.5** |
| `pom.xml` | `java.version` 11 → **17** |
| `application.properties` | Removed `spring.jpa.database-platform` (Hibernate 6 auto-detects) |
| `application.properties` | `management.metrics.export.prometheus.enabled` renamed to `management.prometheus.metrics.export.enabled` |
| `model/Transaction.java` | `javax.persistence.*` → `jakarta.persistence.*` |
| `model/AuthorizeRequest.java` | `javax.validation.constraints.*` → `jakarta.validation.constraints.*` |
| `model/CaptureRequest.java` | `javax.validation.constraints.NotBlank` → `jakarta.*` |
| `model/RefundRequest.java` | `javax.validation.constraints.NotBlank` → `jakarta.*` |
| `controller/PaymentController.java` | `javax.validation.Valid` → `jakarta.validation.Valid` |
| `Dockerfile` | Build stage: `maven:3.8.8-eclipse-temurin-11` → `maven:3.9.9-eclipse-temurin-17` |
| `Dockerfile` | Runtime stage: `gcr.io/distroless/java11-debian12:nonroot` → `gcr.io/distroless/java17-debian12:nonroot` |

### Phase 2 — Deprecated API cleanup

| File | Before | After |
|---|---|---|
| `service/PaymentService.java` | `static HashMap` + `static {}` block | `Map.of(...)` — immutable, one line |
| `service/PaymentService.java` | `.collect(Collectors.toList())` | `.toList()` — `Collectors` import removed |
| `controller/AdminController.java` | `Collections.singletonMap(...)` | `Map.of(...)` — `Collections` import removed |

### Phase 3 — Java 17 feature adoption

| File | Feature | Detail |
|---|---|---|
| `model/CaptureRequest.java` | **Record** | 13 lines → 4 lines |
| `model/RefundRequest.java` | **Record** | 13 lines → 4 lines |
| `model/AuthorizeRequest.java` | **Record** | 44 lines → 49 lines (compact constructor for default currency) |
| `model/TransactionResponse.java` | **Record** | 54 lines → 52 lines; static factory kept as companion method |
| `service/PaymentService.java` | **`switch` expression** | `pickDeclineCode()` — if/if/return → `switch` |
| `service/PaymentService.java` | **`switch` expression** | `resolveCardType()` — `startsWith` chain → map lookup + `switch` on first char |
| `service/PaymentService.java` | **`var`** | Local variable type inference where right-hand side makes type obvious |

### Not changed (with reasons)

| Item | Reason |
|---|---|
| `TransactionStatus` enum | Already idiomatic; sealed classes would add complexity without benefit |
| `ResponseCode` enum | Same — flat variants with no per-variant behaviour |
| `Transaction.java` stays a class | JPA `@Entity` requires a no-arg constructor and mutable fields; records cannot be entities |
| Text blocks | No multi-line string literals exist in this codebase |
| `instanceof` pattern matching | All branching uses enum `==` and string prefix checks — no type-narrowing casts |
| `k8s/deployment.yaml` | JVM flags are Java-version-agnostic; no changes needed |

---

## API Reference

Identical to the Java 11 version — no API contract changes.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/payments/authorize` | Authorize a card transaction |
| POST | `/api/payments/capture` | Capture an authorized transaction |
| POST | `/api/payments/refund` | Refund a captured transaction |
| GET | `/api/payments/{id}` | Get transaction by ID |
| GET | `/api/payments/history` | Last 20 transactions |
| POST | `/admin/cache/clear` | Evict all Caffeine cache entries |
| GET | `/actuator/health` | Readiness / health probe |
| GET | `/actuator/prometheus` | Prometheus metrics |

## Test Card Numbers

| Network | Card Number |
|---|---|
| Visa | 4263970000005262 |
| MasterCard | 5425230000004415 |
| Amex | 374101000000608 |

Use any future expiry (e.g. `12/26`) and any 3–4 digit CVV.

---

## Docker

```bash
docker build -t payment-app:java17 .
docker run -p 8080:8080 payment-app:java17
```

---

## H2 Console

Available at **http://localhost:8080/h2-console**  
JDBC URL: `jdbc:h2:mem:paymentdb`
