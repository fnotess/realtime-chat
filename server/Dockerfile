# syntax=docker/dockerfile:1

# ---- build: JDK + Maven (via the wrapper, so the Maven version matches local builds) ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Only the build definition first. This layer, and the dependency download below, are reused
# from cache until pom.xml changes, so a code-only change doesn't re-download the internet.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
# -DskipTests: the integration tests start Postgres with Testcontainers, which needs a Docker
# daemon, and there is none inside `docker build`. Tests run separately (./mvnw test on the host
# or in CI) before an image is built; this stage only packages.
RUN ./mvnw -B -q -DskipTests package \
 && cp target/chat-server-*.jar /src/app.jar

# ---- runtime: JRE only, no compiler or Maven, much smaller and less to patch ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root: a compromise of the app then can't write to the image or bind privileged ports.
RUN addgroup -S chat && adduser -S chat -G chat
COPY --from=build --chown=chat:chat /src/app.jar app.jar
USER chat

# 8080: REST (nginx proxies /api/). 8081: the hand-written WebSocket server (nginx proxies /ws).
EXPOSE 8080 8081

# Exec form (a JSON array), not shell form: `ENTRYPOINT java ...` runs under /bin/sh -c, so the
# shell is PID 1 and `docker stop` sends SIGTERM to the shell, which doesn't forward it. Java would
# then be SIGKILLed after the grace period with no shutdown hooks: Spring never closes, WsServer.stop()
# never sends 1001 Going Away, and clients see an abnormal 1006 instead of a clean restart.
# In exec form java itself is PID 1 and gets the SIGTERM.
#
# MaxRAMPercentage sizes the heap from the container's memory limit (the JVM reads cgroups), rather
# than the default 25%, which wastes most of a small container. 75% leaves room for metaspace,
# thread stacks and direct buffers. Extra flags go in JAVA_TOOL_OPTIONS, which the JVM reads itself,
# since exec form can't expand variables.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
