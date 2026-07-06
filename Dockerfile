# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Phase 1: maven:3.8.8-eclipse-temurin-11 → maven:3.9.9-eclipse-temurin-17
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime (distroless) ─────────────────────────────────────────────
# Phase 1: gcr.io/distroless/java11-debian12:nonroot → gcr.io/distroless/java17-debian12:nonroot
FROM gcr.io/distroless/java17-debian12:nonroot

WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=builder /build/target/payment-app-1.0.0.jar app.jar

# nonroot user is baked into the distroless image (uid 65532)
USER nonroot

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
