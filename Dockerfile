# Multi-stage build for trade-capture application
FROM maven:3.9.6-eclipse-temurin-21 AS build

# Set working directory
WORKDIR /workspace

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Set working directory
WORKDIR /opt/app

# Copy the built JAR from build stage
COPY --from=build /workspace/target/pms-trade-capture-*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /opt/app

# Switch to non-root user
USER appuser

# Expose the application port
EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8082/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java -XX:+UseContainerSupport -Xms256m -Xmx1g -jar /opt/app/app.jar"]
