# Production image of the backend (local development runs from IntelliJ / mvn spring-boot:run, not from this).
#   docker build -t premium-cars-backend .
# CI builds it on every pull request and pushes it to ghcr.io/ilutov90/premium-cars-backend on main.

# ---- build: the jar, and the jars needed to run the Playwright CLI (browser install) below
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests \
 && mvn -q -B dependency:copy-dependencies -DincludeGroupIds=com.microsoft.playwright -DoutputDirectory=/playwright

# ---- runtime: Java 21 on Ubuntu 24.04 with headless Chromium (same Playwright version as the pom)
FROM eclipse-temurin:21-jre-noble
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build /playwright /tmp/playwright
# Chromium and its system libraries, then curl for the health check; the CLI jars are not kept
RUN java -cp "/tmp/playwright/*" com.microsoft.playwright.CLI install --with-deps chromium \
 && apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* /tmp/playwright \
 && useradd --system --uid 10001 --create-home app \
 && mkdir -p /app/data && chown -R app:app /app

WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER app
# Car photos are written to /app/data/media: mount a volume on /app/data.
VOLUME /app/data
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
