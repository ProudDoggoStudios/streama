# Production image for a public Streama deployment.
#
# Ships the official release jar (same one upstream's docker/Dockerfile uses) on a
# multi-arch JDK 8 base, so it runs on both x86 and the ARM Ampere VMs in Oracle
# Cloud's Always Free tier. To deploy your own modified code instead, build the fat
# jar with `./gradlew assemble` and copy it in as /app/streama.jar.
FROM eclipse-temurin:8-jre

ARG STREAMA_VERSION=1.11.2

WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /app/streama.jar \
      "https://github.com/streamaserver/streama/releases/download/v${STREAMA_VERSION}/streama-${STREAMA_VERSION}.jar"

# The `docker` Grails environment keeps the H2 database at /data/streamadb, and the
# app's upload directory is set to /data/streama in the in-app Settings screen, so
# mounting a single volume at /data persists both the database and the videos.
ENV JAVA_TOOL_OPTIONS="-Xmx3g"

EXPOSE 8080

ENTRYPOINT ["java", "-Dgrails.env=docker", "-jar", "streama.jar"]
