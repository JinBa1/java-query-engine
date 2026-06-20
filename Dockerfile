# syntax=docker/dockerfile:1

# Build the server fat jar (and its engine dependency) inside the image, so the build is
# self-contained and reproducible. A BuildKit cache mount keeps the Maven repo warm across
# rebuilds without baking it into a layer. Tests are CI's job; skip them here for a fast image.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -pl server -am clean package -Dmaven.test.skip=true -q

# Runtime: JRE only, non-root, just the executable Spring Boot jar.
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system cuckoo && useradd --system --gid cuckoo --home /app cuckoo
WORKDIR /app
COPY --from=build /src/server/target/cuckoodb-server-*.jar /app/cuckoodb-server.jar

# Self-host layout: mount a folder of CSVs at /cuckoodb/data and they are loaded into the catalog
# at startup; uploads (when enabled) persist under /cuckoodb/work. data-dir is the PARENT of the
# data/ folder the engine scans, hence /cuckoodb with CSVs in /cuckoodb/data.
RUN mkdir -p /cuckoodb/data /cuckoodb/work && chown -R cuckoo:cuckoo /cuckoodb
ENV CUCKOODB_DATA_DIR=/cuckoodb \
    CUCKOODB_WORK_DIR=/cuckoodb/work

USER cuckoo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/cuckoodb-server.jar"]
