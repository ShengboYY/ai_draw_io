# syntax=docker/dockerfile:1.7
# Build the Spring Boot app from the Maven multi-module project.
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy POMs first so Docker can cache dependency resolution between source edits.
COPY pom.xml .
COPY docker/maven-settings.xml docker/maven-settings.xml
COPY ai-agent-draw-io-api/pom.xml ai-agent-draw-io-api/pom.xml
COPY ai-agent-draw-io-app/pom.xml ai-agent-draw-io-app/pom.xml
COPY ai-agent-draw-io-domain/pom.xml ai-agent-draw-io-domain/pom.xml
COPY ai-agent-draw-io-infrastructure/pom.xml ai-agent-draw-io-infrastructure/pom.xml
COPY ai-agent-draw-io-trigger/pom.xml ai-agent-draw-io-trigger/pom.xml
COPY ai-agent-draw-io-types/pom.xml ai-agent-draw-io-types/pom.xml

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s docker/maven-settings.xml -pl ai-agent-draw-io-app -am package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY --from=build /workspace/ai-agent-draw-io-app/target/ai-agent-draw-io-app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV SPRING_CONFIG_IMPORT=optional:file:.env[.properties],classpath:agent/agent-draw-io.yml
ENV SERVER_FORWARD_HEADERS_STRATEGY=framework
ENV SERVER_SERVLET_SESSION_COOKIE_SECURE=true
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp"

EXPOSE 8091
USER app

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
