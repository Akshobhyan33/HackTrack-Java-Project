# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────────────────────
# HackTrack — multi-stage build
# Build stage uses Maven + JDK 17 (matches <java.version>17</java.version> in pom.xml)
# Runtime stage uses a JRE 17 image only.
#
# Secrets (GEMINI_API_KEY, config/google-credentials.json, config/gmail-tokens.json,
# config/application-local.properties) are NEVER copied into the image.
# They are supplied at runtime via environment variables / mounted volumes.
#
# Runtime environment variables (all optional):
#   PORT                HTTP port           default 8080
#   HACKTRACK_DB_PATH   SQLite file path    default hacktrack.db; set to
#                                           /data/hacktrack.db in deployment
#                                           (image default below), persist with
#                                           a volume mounted at /data
#   GMAIL_REDIRECT_URI  Gmail OAuth callback default http://localhost:8080/api/gmail/callback
#                       set to the deployed HTTPS callback URL at deployment time
#   GEMINI_API_KEY      Gemini API key, only needed for the AI schedule feature
#
# Image-provided variables (do not override unless you know why):
#   CHROME_BIN          Headless Chrome binary for the AI render fallback
#   CHROMEDRIVER_PATH   ChromeDriver matching that Chrome build
#   SE_AVOID_STATS      Selenium Manager anonymous telemetry opt-out
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM maven:3.9.7-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies separately from sources.
COPY pom.xml ./
# Best-effort dependency pre-fetch; the real resolve happens in `package` anyway.
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests clean package

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

# Run as a non-root user; the app writes to /data (SQLite) and /app/config.
RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin hacktrack

WORKDIR /app

COPY --from=build /build/target/hacktrack-1.0-SNAPSHOT.jar /app/hacktrack.jar

# ── Headless browser for the AI schedule render fallback ─────────────────────
# The AI feature first fetches the page over plain HTTP; when that fetch is
# refused (HTTP 403 from bot protection) or the page only renders client-side,
# it retries once in headless Chrome. Google Chrome plus the ChromeDriver that
# matches the installed build are baked in here so Selenium never downloads a
# browser at runtime. amd64 only: on other architectures the image still builds
# and the AI feature keeps its HTTP-only behaviour.
RUN set -eux; \
    if [ "$(dpkg --print-architecture)" = "amd64" ]; then \
        apt-get update; \
        apt-get install -y --no-install-recommends \
            ca-certificates curl fonts-liberation jq unzip; \
        curl -fsSLo /tmp/browser.deb \
            https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb; \
        apt-get install -y --no-install-recommends /tmp/browser.deb; \
        rm -f /tmp/browser.deb; \
        chrome_major="$(google-chrome --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"; \
        driver_url="$(curl -fsSL \
            https://googlechromelabs.github.io/chrome-for-testing/latest-versions-per-milestone-with-downloads.json \
            | jq -r --arg m "$chrome_major" \
                '(((.milestones[$m] // {}) | (.downloads // {}) | (.chromedriver // []))[]? | select(.platform == "linux64") | .url)' \
                || true)"; \
        if [ -z "$driver_url" ] || [ "$driver_url" = "null" ]; then \
            stable="$(curl -fsSL https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_STABLE)"; \
            driver_url="https://storage.googleapis.com/chrome-for-testing-public/${stable}/linux64/chromedriver-linux64.zip"; \
        fi; \
        curl -fsSLo /tmp/chromedriver.zip "$driver_url"; \
        mkdir -p /tmp/chromedriver; \
        unzip -o -j -q /tmp/chromedriver.zip -d /tmp/chromedriver; \
        install -m 755 /tmp/chromedriver/chromedriver /usr/local/bin/chromedriver; \
        rm -rf /tmp/chromedriver /tmp/chromedriver.zip; \
        google-chrome --version; \
        chromedriver --version; \
    else \
        echo "Skipping headless browser install on $(dpkg --print-architecture) (amd64 only)"; \
    fi; \
    rm -rf /var/lib/apt/lists/*

# Writable locations:
#   /data                  -> SQLite database (mount a persistent volume here;
#                             /app holds the JAR and must NOT be a volume)
#   /app/config            -> Gmail credentials/tokens + optional local overrides
RUN mkdir -p /app/config /data && chown -R hacktrack:hacktrack /app /data

# Keep application data out of the JAR directory; override with -e HACKTRACK_DB_PATH=...
ENV HACKTRACK_DB_PATH=/data/hacktrack.db

# Pinned browser locations for hacktrack.ai.RenderedPageFetcher (see above).
# SE_AVOID_STATS opts out of Selenium Manager's anonymous telemetry.
ENV CHROME_BIN=/usr/bin/google-chrome \
    CHROMEDRIVER_PATH=/usr/local/bin/chromedriver \
    SE_AVOID_STATS=true

USER hacktrack

# Default port; overridable at runtime with the PORT environment variable
# (see server.port=${PORT:8080} in src/main/resources/application.properties).
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/hacktrack.jar"]
