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

# Writable locations:
#   /data                  -> SQLite database (mount a persistent volume here;
#                             /app holds the JAR and must NOT be a volume)
#   /app/config            -> Gmail credentials/tokens + optional local overrides
RUN mkdir -p /app/config /data && chown -R hacktrack:hacktrack /app /data

# Keep application data out of the JAR directory; override with -e HACKTRACK_DB_PATH=...
ENV HACKTRACK_DB_PATH=/data/hacktrack.db

USER hacktrack

# Default port; overridable at runtime with the PORT environment variable
# (see server.port=${PORT:8080} in src/main/resources/application.properties).
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/hacktrack.jar"]
