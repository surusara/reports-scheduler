# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven \
    && mvn clean package -DskipTests -Drevision=${APP_VERSION:-1.0.0-SNAPSHOT} \
    && mv target/reports-scheduler.jar app.jar

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=builder /build/app.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
