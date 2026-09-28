# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app \
    && mkdir -p /var/lib/account-manager/files \
    && chown -R 10001:10001 /var/lib/account-manager
WORKDIR /app
COPY --from=build /workspace/target/account-manager-backend-*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
