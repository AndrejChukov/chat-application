# Multi-stage build for Vert.x chat application

# Stage 1: Build
FROM gradle:8.10-jdk21 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle shadowJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S chat && adduser -S chat -G chat
USER chat

COPY --from=builder /app/build/libs/chat-application.jar app.jar

EXPOSE 8080

ENV DB_HOST=postgres
ENV DB_PORT=5432
ENV DB_NAME=chat_application
ENV DB_USER=postgres
ENV DB_PASSWORD=postgres
ENV HTTP_PORT=8080

ENTRYPOINT ["java", "-jar", "app.jar"]
