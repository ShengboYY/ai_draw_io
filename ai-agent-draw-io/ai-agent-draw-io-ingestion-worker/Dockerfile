# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
COPY docker/maven-settings.xml docker/maven-settings.xml
COPY ai-agent-draw-io-api/pom.xml ai-agent-draw-io-api/pom.xml
COPY ai-agent-draw-io-app/pom.xml ai-agent-draw-io-app/pom.xml
COPY ai-agent-draw-io-domain/pom.xml ai-agent-draw-io-domain/pom.xml
COPY ai-agent-draw-io-infrastructure/pom.xml ai-agent-draw-io-infrastructure/pom.xml
COPY ai-agent-draw-io-ingestion-worker/pom.xml ai-agent-draw-io-ingestion-worker/pom.xml
COPY ai-agent-draw-io-trigger/pom.xml ai-agent-draw-io-trigger/pom.xml
COPY ai-agent-draw-io-types/pom.xml ai-agent-draw-io-types/pom.xml
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s docker/maven-settings.xml -pl ai-agent-draw-io-ingestion-worker -am package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-jammy

# Pin OCR runtime and language data so the persisted processing profile matches the image exactly.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr=4.1.1-2.1build1 \
        tesseract-ocr-eng=1:4.00~git30-7274cfa-1.1 \
        tesseract-ocr-chi-sim=1:4.00~git30-7274cfa-1.1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system worker \
    && useradd --system --gid worker --home-dir /worker worker

# These values are the runtime-profile authority for this image and are verified when processing is enabled.
ENV TESSERACT_LANGUAGES=eng+chi_sim \
    TESSERACT_RUNTIME_VERSION=tesseract-4.1.1
WORKDIR /worker
COPY --from=build /workspace/ai-agent-draw-io-ingestion-worker/target/ai-agent-draw-io-ingestion-worker.jar /worker/worker.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp"
USER worker
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /worker/worker.jar"]
