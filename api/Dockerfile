# Build context: repository root (the API packages db/migration and reads contracts/ in tests).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY api/pom.xml api/pom.xml
RUN mvn -q -B -f api/pom.xml dependency:go-offline
COPY db db
COPY contracts contracts
COPY api/src api/src
RUN mvn -q -B -f api/pom.xml -DskipTests package && cp api/target/insightrag-api-*.jar /app.jar

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 insightrag && mkdir -p /data/uploads && chown -R insightrag /data
USER insightrag
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
