# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Cache dependencies in a separate layer
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S chat && adduser -S chat -G chat
WORKDIR /app
COPY --from=build /build/target/secure-chat.jar app.jar
RUN mkdir -p /app/certs /app/data && chown -R chat:chat /app
USER chat
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar", "server"]
