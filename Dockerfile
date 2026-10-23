# Build the JARs with mvn package before building this runtime image.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 surstor \
    && useradd --uid 10001 --gid surstor --home-dir /data --no-create-home surstor \
    && mkdir -p /app/modules /data \
    && chown 10001:10001 /data && chmod 700 /data
LABEL org.opencontainers.image.source="https://github.com/SurStor/SurStor_Memory"
LABEL org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app
COPY surstor-node/target/surstor-node.jar /app/surstor-node.jar
COPY surstor-adapter/target/surstor-adapter-0.2.0-alpha.2-module.jar /app/modules/surstor-adapter-module.jar
COPY docker/*.toml /app/config/
COPY LICENSE /app/LICENSE
ENV HOME=/data DJL_CACHE_DIR=/tmp/djl
USER 10001:10001
EXPOSE 8484
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:8484/capture || exit 1
ENTRYPOINT ["java", "-jar", "/app/surstor-node.jar"]
CMD ["start", "--config", "/app/config/standard.toml", "--allow-container-network"]
