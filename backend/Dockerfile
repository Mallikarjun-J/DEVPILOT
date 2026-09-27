# ---------------------------------------------
# Stage 1: Build the Spring Boot application
# ---------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy Maven wrapper and POM first to leverage Docker layer caching
COPY mvnw pom.xml ./
COPY .mvn .mvn

RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

# Copy application source code and package
COPY src src
RUN ./mvnw clean package -DskipTests

# ---------------------------------------------
# Stage 2: Runtime image
# ---------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root user for security
RUN groupadd -r devpilot && useradd -r -g devpilot devpilot

COPY --from=build --chown=devpilot:devpilot /app/target/*.jar app.jar

USER devpilot

EXPOSE 8081
ENV PORT=8081

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT} -jar app.jar"]
