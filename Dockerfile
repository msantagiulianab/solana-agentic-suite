# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — builder: compile and repackage the Spring Boot executable fat jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

# Cache the Maven wrapper and dependency graph first so source-only changes do
# not invalidate the dependency layer.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./

# Windows checkouts can leave CRLF line endings on the wrapper script, which
# breaks the Linux shebang; normalize it and ensure it is executable.
RUN sed -i 's/\r$//' mvnw \
    && sed -i 's/\r$//' .mvn/wrapper/maven-wrapper.properties \
    && chmod +x mvnw

RUN ./mvnw dependency:go-offline -B

# Compile and package the production executable jar (tests skipped for image).
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2 — runtime: minimal, non-root JRE container.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Create a non-privileged service account for least-privilege execution.
RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

WORKDIR /app

# Spring Boot repackage emits a single executable fat jar under target/.
ARG JAR_FILE=target/*.jar
COPY --from=builder /workspace/${JAR_FILE} app.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
