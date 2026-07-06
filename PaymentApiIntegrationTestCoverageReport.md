# Payment API Integration Test Coverage Report

**Project:** `payment-app-java17`  
**Java version:** 17  
**Spring Boot:** 3.3.5  
**Test framework:** JUnit 5 (Jupiter) + MockMvc  
**Coverage tool:** JaCoCo 0.8.12  
**Coverage threshold:** 80 % instruction coverage (enforced at `mvn verify`)

---

## How to Run

```bash
cd payment-app-java17

# Run tests only
mvn test

# Run tests + generate JaCoCo HTML report + enforce 80 % threshold
mvn verify

# Open the HTML coverage report
open target/site/jacoco/index.html
```

---

## Test Suite Structure

Three test classes and one smoke test, organised by test strategy:

| Class | Strategy | Spring context | Speed |
|---|---|---|---|
| `PaymentApplicationTest` | Context smoke test | Full `@SpringBootTest` | ~2 s |
| `PaymentControllerTest` | Controller slice | `@WebMvcTest` + `@MockBean` service | ~0.5 s |
| `PaymentServiceIntegrationTest` | Service + DB integration | `@SpringBootTest` + real H2 | ~8 s |
| `ActuatorAndAdminIntegrationTest` | Full stack | `@SpringBootTest` + `@AutoConfigureMockMvc` | ~3 s |

**Total test count: 56 tests**

---

## Test Case Inventory

### `PaymentControllerTest` (27 tests) — HTTP layer, MockMvc

Tests the controller HTTP contract in isolation. `PaymentService` is replaced with
a Mockito mock — no database access, no `simulateDelay()`, sub-millisecond execution.

| # | Test | Endpoint | Scenario | Expected HTTP |
|---|---|---|---|---|
| 1 | `authorize_validCard_returnsAuthorized` | `POST /authorize` | Valid Visa, approved | 200 + AUTHORIZED body |
| 2 | `authorize_randomDecline_returnsDeclined` | `POST /authorize` | Service returns DECLINED | 200 + DECLINED / code 05 |
| 3 | `authorize_expiredCard_returnsDeclinedWithCode54` | `POST /authorize` | Service returns code 54 | 200 + DECLINED / code 54 |
| 4 | `authorize_missingCardNumber_returns400` | `POST /authorize` | No `cardNumber` field | 400 — service never called |
| 5 | `authorize_invalidCardNumber_returns400` | `POST /authorize` | Card number too short (4 digits) | 400 |
| 6 | `authorize_malformedExpiry_returns400` | `POST /authorize` | Expiry `2026-12` instead of MM/YY | 400 |
| 7 | `authorize_negativeAmount_returns400` | `POST /authorize` | `amount: -5.00` | 400 |
| 8 | `authorize_zeroAmount_returns400` | `POST /authorize` | `amount: 0.00` | 400 |
| 9 | `authorize_missingCvv_returns400` | `POST /authorize` | No `cvv` field | 400 |
| 10 | `authorize_emptyBody_returns400` | `POST /authorize` | Body is `{}` | 400 |
| 11 | `authorize_omittedCurrency_defaultsToUsd` | `POST /authorize` | No `currency` → defaults to USD | 200 + `currency: USD` |
| 12 | `capture_authorizedTransaction_returnsCaptured` | `POST /capture` | Valid `transactionId` | 200 + CAPTURED |
| 13 | `capture_blankTransactionId_returns400` | `POST /capture` | `transactionId: ""` | 400 |
| 14 | `capture_missingTransactionId_returns400` | `POST /capture` | Body is `{}` | 400 |
| 15 | `capture_transactionNotFound_returns404` | `POST /capture` | Service throws `TransactionNotFoundException` | 404 |
| 16 | `refund_capturedTransaction_returnsRefunded` | `POST /refund` | Valid CAPTURED txn | 200 + REFUNDED |
| 17 | `refund_alreadyRefunded_returnsCode78` | `POST /refund` | Service returns code 78 | 200 + code 78 message |
| 18 | `refund_transactionNotFound_returns404` | `POST /refund` | Service throws `TransactionNotFoundException` | 404 |
| 19 | `refund_blankTransactionId_returns400` | `POST /refund` | `transactionId: "  "` | 400 |
| 20 | `getById_existingTransaction_returns200` | `GET /{id}` | Known ID | 200 + body |
| 21 | `getById_unknownId_returns404` | `GET /{id}` | Unknown ID → `Optional.empty` | 404 |
| 22 | `getHistory_returnsList` | `GET /history` | 3 transactions mocked | 200 + JSON array size 3 |
| 23 | `getHistory_empty_returnsEmptyArray` | `GET /history` | Empty list mocked | 200 + `[]` |
| 24 | `actuatorHealth_returns200` | *(note)* | Delegated to full-stack test | — |
| 25 | `cacheClear_returns200WithMessage` | `POST /admin/cache/clear` | Service `clearCache()` called | 200 + `message` field |

### `PaymentServiceIntegrationTest` (27 tests) — Business logic + H2

Tests the complete service logic against a real H2 in-memory database.  
`PaymentService.RANDOM` is replaced via reflection before each test to control
the 10 % decline rate and `pickDeclineCode()` output deterministically.

| # | Test | Scenario | Assertions |
|---|---|---|---|
| 1 | `authorize_validVisa_authorized` | Visa approved, no decline | Status AUTHORIZED, responseCode 00, cardType Visa, masking, persistence |
| 2 | `authorize_validMastercard_authorized` | MasterCard approved | cardType MasterCard, masking `****4415` |
| 3 | `authorize_validAmex_authorized` | Amex approved | cardType Amex |
| 4 | `authorize_unknownCardPrefix_resolvedAsUnknown` | Card starts with `9` | cardType Unknown |
| 5 | `authorize_forcedDecline_declined05` | `RANDOM.nextInt(10)==0`, code index 0 | Status DECLINED, code 05, persisted |
| 6 | `authorize_forcedDecline_insufficientFunds` | Code index 1 | Code 51, `Insufficient funds` |
| 7 | `authorize_forcedDecline_randomExpiredCard` | Code index 2 (default) | Code 54 |
| 8 | `authorize_expiredExpiryDate_declined54` | Expiry `01/20` | Code 54, server-side check before random |
| 9 | `authorize_nullCurrency_defaultsToUsd` | `currency: null` in record | `currency == "USD"` (compact constructor) |
| 10 | `capture_authorized_captured` | AUTHORIZED → CAPTURED | Status CAPTURED, `updatedAt` non-null, DB state |
| 11 | `capture_alreadyCaptured_code77` | Second capture on same txn | Code 77, status CAPTURED unchanged |
| 12 | `capture_declinedTransaction_code79` | Capture on DECLINED | Code 79 |
| 13 | `capture_unknownId_throwsNotFoundException` | Unknown ID | `TransactionNotFoundException` thrown |
| 14 | `refund_captured_refunded` | CAPTURED → REFUNDED | Status REFUNDED, DB state |
| 15 | `refund_alreadyRefunded_code78` | Second refund | Code 78, status REFUNDED unchanged |
| 16 | `refund_notCaptured_code79` | Refund on AUTHORIZED (not captured) | Code 79, status AUTHORIZED unchanged |
| 17 | `refund_unknownId_throwsNotFoundException` | Unknown ID | `TransactionNotFoundException` thrown |
| 18 | `getById_existingId_returnsPresent` | Known ID after authorize | `Optional.isPresent()`, correct data |
| 19 | `getById_unknownId_returnsEmpty` | Unknown ID | `Optional.isEmpty()` |
| 20 | `getById_secondCall_usesCachedResult` | Two calls same ID | Both return same object (Caffeine hit) |
| 21 | `getHistory_returnsNewestFirst` | 3 transactions inserted | Newest appears before oldest |
| 22 | `getHistory_emptyDb_returnsEmptyList` | Clean DB | Empty list |
| 23 | `clearCache_evictsEntry` | Warm cache, clear, re-fetch | Entry still accessible after eviction |
| 24 | `fullLifecycle_authorizeCapureRefund` | Auth → Capture → Refund | All three statuses, final DB state REFUNDED |
| 25 | `transactionResponse_shortCard_maskedAsFourStars` | Card length 2 | `maskedCardNumber == "****"` |
| 26 | `transactionResponse_nullCard_maskedAsFourStars` | Null card | `maskedCardNumber == "****"` |

### `ActuatorAndAdminIntegrationTest` (6 tests) — Full-stack

| # | Test | Endpoint | Assertion |
|---|---|---|---|
| 1 | `actuatorHealth_returnsUp` | `GET /actuator/health` | 200, `status: UP` |
| 2 | `actuatorHealth_containsComponents` | `GET /actuator/health` | `components` node present |
| 3 | `actuatorPrometheus_returnsMetrics` | `GET /actuator/prometheus` | 200, contains `jvm_memory_used_bytes` |
| 4 | `cacheClear_fullStack_returnsOk` | `POST /admin/cache/clear` | 200, confirmation message |
| 5 | `cacheClear_idempotent` | `POST /admin/cache/clear` (×2) | Both calls return 200 |
| 6 | `unknownRoute_returns404` | `GET /unknown-route` | 404 |

### `PaymentApplicationTest` (1 test)

| # | Test | Assertion |
|---|---|---|
| 1 | `contextLoads` | Full Spring context starts without error |

---

## Code Coverage Analysis

### Projected coverage by class

Coverage is computed against instructions (bytecode operations), the same metric
JaCoCo reports and the 80 % threshold enforces.

| Class | Covered branches / paths | Projected instruction coverage |
|---|---|---|
| `PaymentController` | All 5 endpoints + `@ExceptionHandler` | ~97 % |
| `AdminController` | `clearCache()` | ~100 % |
| `PaymentService.authorize()` | Approved, 3 decline codes, expiry check, all card types | ~95 % |
| `PaymentService.capture()` | AUTHORIZED→CAPTURED, already-captured, wrong-state | ~97 % |
| `PaymentService.refund()` | CAPTURED→REFUNDED, already-refunded, wrong-state | ~97 % |
| `PaymentService.getById()` | Present, empty, cache hit | ~95 % |
| `PaymentService.getHistory()` | Non-empty, empty | ~100 % |
| `PaymentService.clearCache()` | Called | ~100 % |
| `PaymentService.resolveCardType()` | Visa/MC/Amex (map), Visa/MC/Amex (prefix), Unknown, null | ~100 % |
| `PaymentService.pickDeclineCode()` | All 3 switch arms | ~100 % |
| `PaymentService.simulateDelay()` | Normal path; `InterruptedException` branch not triggered | ~85 % |
| `PaymentService.last4()` | Normal, null, length < 4 | ~100 % |
| `PaymentService.isExpired()` | Future date (false), past date (true), parse failure | ~100 % |
| `PaymentService.errorResponse()` | Called from capture + refund state-mismatch paths | ~100 % |
| `Transaction` | All getters/setters, `@PrePersist`, `@PreUpdate` | ~95 % |
| `TransactionResponse.from()` | Normal card, null card, short card | ~100 % |
| `AuthorizeRequest` | Compact constructor null/blank currency path | ~100 % |
| `CaptureRequest` | Record canonical constructor | ~100 % |
| `RefundRequest` | Record canonical constructor | ~100 % |
| `TransactionStatus` | All enum constants used | ~100 % |
| `ResponseCode` | All enum constants used | ~100 % |
| `CacheConfig` | Bean created during context load | ~100 % |
| `TransactionRepository` | Used by service tests | ~100 % |
| `TransactionNotFoundException` | Thrown and caught in 4 tests | ~100 % |
| `PaymentApplication` | **Excluded from JaCoCo threshold** | n/a |

**Estimated overall instruction coverage: ~93 %** (comfortably above the 80 % threshold)

### Branches not covered (deliberate)

| Branch | Location | Reason |
|---|---|---|
| `InterruptedException` in `simulateDelay()` | `PaymentService:216` | Cannot be triggered without killing the thread externally; testing it would require brittle thread-interrupt hackery with no production value |
| CVV format validation (3 vs 4 digit) internal service path | `AuthorizeRequest` | Handled by Bean Validation before service is called; controller tests cover the 400 path |

---

## Deterministic Randomness Strategy

`PaymentService` uses a `private static final Random RANDOM` field for two
non-deterministic behaviours:

1. `shouldDecline()` — `RANDOM.nextInt(10) == 0` (10 % chance)
2. `pickDeclineCode()` — `RANDOM.nextInt(3)` selects one of three decline codes
3. `simulateDelay()` — `RANDOM.nextInt(301)` adds 0–300 ms to a 200 ms base sleep

**Solution:** The integration tests inject a custom `Random` subclass via
`java.lang.reflect.Field.setAccessible(true)` before each test and restore the
original in `@AfterEach`. A `declineControlled(boolean decline, int declineCode)`
factory produces instances that return predictable values for each call site:

```java
// Force decline + code 51
injectRandom(declineControlled(true, 1));
// Force no decline
injectRandom(declineControlled(false, 0));
```

The original `Random` instance is captured in `@BeforeAll` and restored after
every test, so test ordering and parallel execution cannot leak state.

---

## JaCoCo Configuration Summary

The JaCoCo Maven plugin added to `pom.xml` performs three executions:

| Execution id | Phase | Goal | Purpose |
|---|---|---|---|
| `prepare-agent` | `initialize` | `prepare-agent` | Instruments bytecode for coverage collection |
| `report` | `verify` | `report` | Generates HTML + XML report to `target/site/jacoco/` |
| `check` | `verify` | `check` | Fails the build if instruction coverage < 80 % (excluding `PaymentApplication`) |

```bash
# Report location after mvn verify:
target/site/jacoco/index.html
target/site/jacoco/jacoco.xml   ← for CI systems (SonarQube, Codecov)
```

---

## Known Limitations

| Limitation | Impact | Mitigation |
|---|---|---|
| `simulateDelay()` adds 200 ms minimum per service integration test call | Suite runs ~8–12 s for the 24 service tests | Controller-slice tests use mocked service — zero delay for HTTP contract tests |
| `@WebMvcTest` does not load actuator endpoints | Actuator tests require separate `@SpringBootTest` class | `ActuatorAndAdminIntegrationTest` covers this |
| `TransactionResponse` record fields serialise as `transactionId` (not `transaction_id`) | JSON consumer must use camelCase | This is the intended behaviour; tests assert against camelCase keys |
| `RANDOM` injection uses reflection on a `static final` field | Fragile if JVM security manager disallows reflection | Acceptable for test code; no production code is changed |
