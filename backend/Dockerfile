# Runtime image for the backend. Build the jar on the host first (uses your local Maven repository):
#   cd C:\dev\iccp\backend; mvn -B -DskipTests package
# Dockerfile.ci builds inside Docker instead (for CI servers with a reliable connection to Maven Central).
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 iccp && mkdir -p /app/var/files && chown -R iccp /app
WORKDIR /app
COPY target/iccp-backend.jar app.jar
USER iccp
ENV TZ=Africa/Dar_es_Salaam
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
