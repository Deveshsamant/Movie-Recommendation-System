# Movie Recommender System (Java) - container image
#
# Reads $PORT and binds 0.0.0.0, so no platform-specific start command is needed.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# dependencies first so this layer caches across code edits
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
# curl is only for the healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/movie-recommender.jar app.jar
COPY config.example.json .

# Hugging Face Spaces runs as a non-root user and expects 7860.
# cache/ and data/ must be writable: the app downloads MovieLens on first run and
# writes trained models there.
RUN useradd -m -u 1000 app \
    && mkdir -p /app/cache /app/data \
    && chown -R app:app /app
USER app

ENV PORT=7860 \
    HOST=0.0.0.0
EXPOSE 7860

# First boot downloads MovieLens and trains every model. Everything is cached
# afterwards. start-period covers that first train.
HEALTHCHECK --interval=30s --timeout=10s --start-period=240s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/api/status" > /dev/null || exit 1

CMD ["java", "-jar", "app.jar"]
