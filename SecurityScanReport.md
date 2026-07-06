# Security Scan Report — payment-app-java17

**Date:** 2025  
**Scope:** All Java source, config, frontend, Dockerfile, and k8s manifest under `payment-app-java17/`  
**Scanner:** Manual static analysis (Phase 1 + Phase 2)  
**Total findings: 14** (3 High · 6 Medium · 5 Low / Informational)

> **Context:** This is a demo application with no real payment network connectivity.
> Findings are rated against the risk they would pose in a **production-grade deployment**,
> which is the natural next destination for this codebase given the presence of a
> Dockerfile and an OpenShift manifest.

---

## Summary Table

| ID | Severity | Category | File | Line(s) | Title |
|---|---|---|---|---|---|
| S-01 | 🔴 HIGH | Sensitive data in source | `static/index.html` | 206–208 | Real-looking PAN and CVV values hardcoded in frontend |
| S-02 | 🔴 HIGH | Unauthenticated admin endpoint | `AdminController.java` | 23 | `/admin/cache/clear` has no authentication or authorisation |
| S-03 | 🔴 HIGH | CVV stored in persistence layer | `Transaction.java` | 29–31 / `PaymentService.java` | CVV is accepted but the system stores the full card number — storing sensitive auth data violates PCI-DSS |
| S-04 | 🟡 MEDIUM | Sensitive config exposed at runtime | `application.properties` | 18 | `health.show-details=always` leaks stack, DB, and env metadata to any caller |
| S-05 | 🟡 MEDIUM | Actuator endpoints unauthenticated | `application.properties` | 17 | `prometheus`, `info`, `metrics` exposed without any access control |
| S-06 | 🟡 MEDIUM | H2 web console enabled unconditionally | `application.properties` | 13–14 | Full database web shell accessible at `/h2-console` in any profile |
| S-07 | 🟡 MEDIUM | CDN scripts without Subresource Integrity | `static/index.html` | 8–11 | Three external scripts loaded without `integrity=` hashes — supply-chain injection risk |
| S-08 | 🟡 MEDIUM | No rate limiting on payment endpoints | `PaymentController.java` | 25–38 | Any client can flood `/authorize`, `/capture`, `/refund` without throttling |
| S-09 | 🟡 MEDIUM | `java.util.Random` used for security-sensitive logic | `PaymentService.java` | 24 | `Random` is not cryptographically secure; decline decisions are statistically predictable |
| S-10 | 🟡 MEDIUM | No CORS policy defined | (absent) | — | No `@CrossOrigin` or `WebMvcConfigurer` — browser default permits same-origin only but any server-side CORS requirement is undocumented and unenforceable |
| S-11 | 🟠 LOW | Missing HTTP security headers | (absent) | — | No `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, or `Strict-Transport-Security` headers configured |
| S-12 | 🟠 LOW | `spring.datasource.username=sa` committed to source | `application.properties` | 7–8 | Default H2 credential committed; will be picked up if accidentally reused against a real datasource |
| S-13 | 🟠 LOW | Development React build loaded in all environments | `static/index.html` | 8–9 | `react.development.js` includes verbose error output and is ~3× larger than the minified production bundle |
| S-14 | 🟠 LOW | `show-sql=false` is correctly set but no audit log | `application.properties` | 12 | No transaction audit trail beyond in-memory H2 — a real gateway requires immutable audit logging |

---

## Phase 1 — Hardcoded Secrets and Credential Scan

### S-01 🔴 HIGH — Real-looking PAN and CVV values hardcoded in frontend

**File:** [`src/main/resources/static/index.html`](src/main/resources/static/index.html)  
**Lines:** 206–208

```javascript
const TEST_CARDS = [
  { brand: "Visa",       number: "4263970000005262", cvv: "123",  expiry: "12/26" },
  { brand: "MasterCard", number: "5425230000004415", cvv: "456",  expiry: "06/27" },
  { brand: "Amex",       number: "374101000000608",  cvv: "7890", expiry: "09/25" },
];
```

**Risk:** These are test card numbers but they follow valid Luhn-check patterns for their respective networks and are accompanied by CVV and expiry values. If this frontend is shipped to a staging or production environment, a browser developer-tools inspection or a git history search reveals ready-made credential tuples. Even test card numbers with matching CVVs can trigger real gateway pre-auth calls against misconfigured environments.

**Fix:** Move test card data to a separate `test-cards.js` file included only when `SPRING_PROFILES_ACTIVE=dev`. In any other profile, render only a generic placeholder prompt.

---

### S-12 🟠 LOW — Default datasource credentials committed to source

**File:** [`src/main/resources/application.properties`](src/main/resources/application.properties)  
**Lines:** 7–8

```properties
spring.datasource.username=sa
spring.datasource.password=
```

**Risk:** Empty password for the `sa` superuser is committed to version control. While H2 in-memory mode is benign here, the pattern is dangerous: if the datasource URL is ever changed to a real JDBC target (e.g., during a quick demo modification), these credentials are silently accepted by most DBs that allow blank passwords for their admin account.

**Fix:** Externalize credentials via environment variables: `spring.datasource.username=${DB_USER:sa}` and `spring.datasource.password=${DB_PASSWORD:}`. In production, inject via a Kubernetes Secret.

---

## Phase 2 — Logic Flaws and Insecure Patterns

### S-02 🔴 HIGH — Unauthenticated admin endpoint

**File:** [`src/main/java/com/demo/payment/controller/AdminController.java`](src/main/java/com/demo/payment/controller/AdminController.java)  
**Line:** 23

```java
@PostMapping("/cache/clear")
public ResponseEntity<Map<String, String>> clearCache() {
```

**Risk:** `POST /admin/cache/clear` is fully open — no authentication, no IP restriction, no role check. Any caller on the network can evict the entire transaction cache, forcing a database re-read storm and enabling a cache-poisoning denial-of-service. In an OpenShift deployment where the Route is internet-facing, this endpoint is publicly reachable.

**Fix:** Add `spring-boot-starter-security` and protect `/admin/**` with `ROLE_ADMIN` via `HttpSecurity`:

```java
http.securityMatcher("/admin/**").authorizeHttpRequests(a -> a.anyRequest().hasRole("ADMIN"));
```

---

### S-03 🔴 HIGH — CVV field accepted and card number stored in plaintext

**File:** [`src/main/java/com/demo/payment/model/AuthorizeRequest.java`](src/main/java/com/demo/payment/model/AuthorizeRequest.java) line 29–31  
**File:** [`src/main/java/com/demo/payment/model/Transaction.java`](src/main/java/com/demo/payment/model/Transaction.java) line 28–29  
**File:** [`src/main/java/com/demo/payment/service/PaymentService.java`](src/main/java/com/demo/payment/service/PaymentService.java) line 61

```java
// AuthorizeRequest.java — CVV accepted into the record
String cvv,

// Transaction.java — full PAN stored in the DB column
@Column(nullable = false, length = 20)
private String cardNumber;

// PaymentService.java — full PAN copied to entity
t.setCardNumber(req.cardNumber());
```

**Risk:** Two PCI-DSS violations in one pattern:
1. **CVV must never be stored after authorisation** (PCI-DSS Requirement 3.2). The `Transaction` entity does not have a `cvv` column, but the `AuthorizeRequest` record carries it through the service layer in clear memory until the request is GC'd — in a real gateway the CVV must be zeroed immediately after the network call to the processor.
2. **PAN stored in plaintext**. The full 16-digit card number is written to the H2 `transactions` table without any encryption or tokenisation. In production this must be stored only as a token (with last-4 for display) and the real PAN held in a PCI-scoped vault.

**Fix (CVV):** Drop `cvv` from `AuthorizeRequest` after network validation — zero the field immediately after use or remove the field from the record entirely if the service layer doesn't use it.  
**Fix (PAN):** Store only a payment token (returned by a processor) and `last4` in the `Transaction` entity. Never write the full PAN to an application database.

---

### S-04 🟡 MEDIUM — Health endpoint leaks full internal details

**File:** [`src/main/resources/application.properties`](src/main/resources/application.properties)  
**Line:** 18

```properties
management.endpoint.health.show-details=always
```

**Risk:** With `show-details=always`, `GET /actuator/health` returns the full component breakdown — database connection URL, pool stats, disk space, JVM metrics — to any unauthenticated caller. This constitutes information disclosure: an attacker learns the internal datasource URL, DB vendor, and available disk headroom.

**Fix:** Change to `show-details=when-authorized` and configure Spring Security to require `ROLE_ACTUATOR` for health detail access. Use `show-details=never` as the safe default for internet-facing deployments.

---

### S-05 🟡 MEDIUM — Actuator scrape endpoints exposed without access control

**File:** [`src/main/resources/application.properties`](src/main/resources/application.properties)  
**Line:** 17

```properties
management.endpoints.web.exposure.include=health,prometheus,info,metrics
```

**Risk:** `prometheus` exposes all JVM, HTTP, cache, and DB connection-pool metrics without authentication. `metrics` exposes individual metric names and values. `info` can reveal application version, Git SHA, and environment. This constitutes a reconnaissance surface: response times, error rates, and pool utilisation can be used to fingerprint backend behaviour and inform timing attacks.

**Fix:** Expose only `health` publicly. Move `prometheus` to a dedicated management port (via `management.server.port=9090`) accessible only within the cluster, or protect it with Spring Security requiring `ROLE_PROMETHEUS`.

---

### S-06 🟡 MEDIUM — H2 web console enabled unconditionally

**File:** [`src/main/resources/application.properties`](src/main/resources/application.properties)  
**Lines:** 13–14

```properties
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
```

**Risk:** The H2 console is a fully functional web-based SQL shell. With `username=sa` and an empty password, any browser can connect at `/h2-console` using JDBC URL `jdbc:h2:mem:paymentdb` and read or delete all transaction records. If the application is reachable from outside the cluster (via the OpenShift Route), this is a complete data exfiltration path.

**Fix:** Gate the console on the `dev` profile only:

```properties
# application.properties
spring.h2.console.enabled=false

# application-dev.properties
spring.h2.console.enabled=true
```

In Spring Security, also add `.requestMatchers(PathRequest.toH2Console()).permitAll()` only in the dev profile, and add `.headers(h -> h.frameOptions(f -> f.sameOrigin()))` to allow the console iframe.

---

### S-07 🟡 MEDIUM — CDN scripts loaded without Subresource Integrity hashes

**File:** [`src/main/resources/static/index.html`](src/main/resources/static/index.html)  
**Lines:** 8–11

```html
<script crossorigin src="https://unpkg.com/react@18/umd/react.development.js"></script>
<script crossorigin src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"></script>
<script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
```

**Risk:** All three scripts are loaded from `unpkg.com` without `integrity=` SHA-384 hashes. If unpkg is compromised — or the CDN serves a mutated response via a BGP hijack or CDN-side XSS — the injected script executes in the same origin as the payment form, with full access to card number, CVV, and expiry inputs.

The third script (`@babel/standalone`) also has no `crossorigin` attribute, so browser errors from it are masked by CORS opaque responses, making supply-chain injection harder to detect.

**Fix:** Generate and pin SRI hashes for all three scripts:

```html
<script crossorigin="anonymous"
        integrity="sha384-<hash>"
        src="https://unpkg.com/react@18.3.1/umd/react.production.min.js"></script>
```

Use the production (minified) builds instead of development builds to reduce attack surface and payload size. Run `openssl dgst -sha384 -binary <file> | openssl base64 -A` against the pinned file to generate hashes.

---

### S-08 🟡 MEDIUM — No rate limiting on payment endpoints

**File:** [`src/main/java/com/demo/payment/controller/PaymentController.java`](src/main/java/com/demo/payment/controller/PaymentController.java)  
**Lines:** 25–38

```java
@PostMapping("/authorize")
public ResponseEntity<TransactionResponse> authorize(@Valid @RequestBody AuthorizeRequest req) { ... }
```

**Risk:** All three mutating endpoints (`/authorize`, `/capture`, `/refund`) accept unlimited requests per client. An attacker can:
- Mount a card-stuffing attack by iterating PAN ranges against `/authorize`
- Exhaust the H2 connection pool via concurrent capture/refund floods
- Abuse `simulateDelay()` resource consumption to degrade all other clients

**Fix:** Add Bucket4j or Resilience4j rate limiting. A minimal defence is an OpenShift Route rate limit annotation; a proper fix is per-IP rate limiting at the application layer:

```java
// pom.xml
<dependency>
    <groupId>com.github.vladimir-bukhtoyarov</groupId>
    <artifactId>bucket4j-core</artifactId>
</dependency>

// PaymentController.java — per-IP token bucket, 10 req/min
@PostMapping("/authorize")
public ResponseEntity<TransactionResponse> authorize(..., HttpServletRequest httpReq) {
    rateLimiter.consumeOrThrow(httpReq.getRemoteAddr());
    ...
}
```

---

### S-09 🟡 MEDIUM — `java.util.Random` used for security-relevant decisions

**File:** [`src/main/java/com/demo/payment/service/PaymentService.java`](src/main/java/com/demo/payment/service/PaymentService.java)  
**Line:** 24

```java
private static final Random RANDOM = new Random();
```

**Risk:** `java.util.Random` uses a linear congruential generator (LCG). Given enough observed outputs (decline/approve patterns), the internal state can be reconstructed, allowing an attacker to predict future outcomes deterministically. For a demo this is cosmetic, but the pattern is dangerous in any real gateway where "random" must mean cryptographically unpredictable — e.g., for nonce generation, idempotency tokens, or challenge values.

**Fix:** Replace with `java.security.SecureRandom` — a one-line change with no API difference for `nextInt()`:

```java
// Before
private static final Random RANDOM = new Random();

// After
private static final Random RANDOM = new SecureRandom();
```

---

### S-10 🟡 MEDIUM — No CORS policy defined

**File:** No CORS configuration exists anywhere in the codebase.

**Risk:** Without an explicit CORS policy, Spring Boot's default behaviour allows same-origin requests only. However, there is no `SecurityFilterChain` or `WebMvcConfigurer` that explicitly rejects cross-origin requests with a documented policy. If `spring-boot-starter-security` is added in the future (recommended), the default CORS policy changes, potentially breaking the frontend or inadvertently opening cross-origin access. The absence of an explicit policy makes the security posture implicit and fragile.

**Fix:** Add an explicit CORS configuration, even if it only allows same-origin:

```java
@Bean
public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                    .allowedOrigins("${app.allowed-origins:}")
                    .allowedMethods("GET", "POST");
        }
    };
}
```

---

### S-11 🟠 LOW — Missing HTTP security response headers

**File:** No Spring Security configuration exists; no custom filter sets security headers.

**Risk:** The application sends no `Content-Security-Policy` (CSP), `X-Frame-Options`, `X-Content-Type-Options`, or `Strict-Transport-Security` headers. Without these:
- The page can be embedded in an `<iframe>` on an attacker's site (clickjacking)
- Browsers will MIME-sniff response content (content-type confusion)
- HTTP→HTTPS redirection at the Route layer is not enforced in the browser memory (HSTS missing)

The OpenShift Route does enforce TLS edge termination with HTTP redirect (k8s line 129), but that only covers the ingress layer — the application itself makes no TLS assertions.

**Fix:** Add Spring Security and enable the default security headers (active by default once Spring Security is on the classpath):

```java
http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
    .frameOptions(f -> f.deny())
    .xssProtection(Customizer.withDefaults())
    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
);
```

---

### S-13 🟠 LOW — React development build served in all environments

**File:** [`src/main/resources/static/index.html`](src/main/resources/static/index.html)  
**Lines:** 8–9

```html
<script ... src="https://unpkg.com/react@18/umd/react.development.js"></script>
<script ... src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"></script>
```

**Risk:** The development builds include detailed error messages, component stack traces, and React internal warnings that are rendered to the browser console. In a payment UI, this exposes component state structure to anyone with DevTools open, which aids reverse engineering and error-handling bypass attempts.

**Fix:** Switch to the production minified builds:

```html
<script src="https://unpkg.com/react@18.3.1/umd/react.production.min.js" ...></script>
<script src="https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js" ...></script>
```

---

### S-14 🟠 LOW — No immutable audit log for financial transactions

**File:** [`src/main/java/com/demo/payment/service/PaymentService.java`](src/main/java/com/demo/payment/service/PaymentService.java) — `authorize()`, `capture()`, `refund()`.

**Risk:** The only record of each payment event is a mutable row in H2. The `Transaction` entity's `status` field is overwritten in-place with no event history, no actor identifier (who called the API), and no timestamp audit trail beyond `createdAt`/`updatedAt`. In a real payment system, every state transition must be appended to an immutable event log for dispute resolution, regulatory compliance (PCI-DSS Requirement 10), and fraud forensics. Because H2 is in-memory, a restart wipes all data entirely.

**Fix:** Add an `TransactionEvent` entity with an append-only write model (no `@PreUpdate`, only `@PrePersist`), capturing `transactionId`, `fromStatus`, `toStatus`, `timestamp`, and `callerIp`. In production, back this with a durable store (PostgreSQL) and write to an out-of-process audit sink.

---

## Findings by File

| File | Findings |
|---|---|
| `static/index.html` | S-01, S-07, S-13 |
| `AdminController.java` | S-02 |
| `AuthorizeRequest.java` / `Transaction.java` / `PaymentService.java` | S-03 |
| `application.properties` | S-04, S-05, S-06, S-12 |
| `PaymentController.java` | S-08 |
| `PaymentService.java` | S-09, S-14 |
| (absent — no config) | S-10, S-11 |

---

## Risk Prioritisation for Production Readiness

| Priority | Action | Findings addressed |
|---|---|---|
| **P0 — Block deploy** | Remove CVV from persistence path; store only tokenised PAN | S-03 |
| **P0 — Block deploy** | Add Spring Security; protect `/admin/**` | S-02 |
| **P0 — Block deploy** | Disable H2 console outside `dev` profile | S-06 |
| **P1 — Before first user** | Restrict actuator endpoints; change `show-details` | S-04, S-05 |
| **P1 — Before first user** | Add SRI hashes and switch to production React | S-07, S-13 |
| **P1 — Before first user** | Add rate limiting on `/authorize` | S-08 |
| **P2 — Sprint backlog** | Add HTTP security headers via Spring Security | S-11 |
| **P2 — Sprint backlog** | Define explicit CORS policy | S-10 |
| **P2 — Sprint backlog** | Replace `java.util.Random` with `SecureRandom` | S-09 |
| **P3 — Hardening** | Externalise credentials to Kubernetes Secret | S-12 |
| **P3 — Hardening** | Move test card data to dev-profile-only asset | S-01 |
| **P3 — Hardening** | Add append-only audit event log | S-14 |
