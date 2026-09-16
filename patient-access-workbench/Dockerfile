# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

# Runtime stage
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home workbench \
 && mkdir -p /var/lib/patient-access-workbench /opt/workbench/packages \
 && chown -R workbench:workbench /var/lib/patient-access-workbench /opt/workbench
WORKDIR /opt/workbench
COPY --from=build /src/build/libs/patient-access-workbench-*.jar app.jar
USER workbench
ENV SPRING_PROFILES_ACTIVE=prod \
    PAW_DATA_DIR=/var/lib/patient-access-workbench \
    PAW_PACKAGES_DIR=/opt/workbench/packages \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME ["/var/lib/patient-access-workbench"]
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD curl -fsS "http://localhost:${SERVER_PORT:-8090}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/opt/workbench/app.jar"]
