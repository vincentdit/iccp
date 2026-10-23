# Build:  docker build -t iccp-backend backend
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21.0.5_11-jre
RUN useradd --system --uid 10001 iccp
WORKDIR /app
COPY --from=build /src/target/iccp-backend.jar app.jar
USER iccp
ENV TZ=Africa/Dar_es_Salaam JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
