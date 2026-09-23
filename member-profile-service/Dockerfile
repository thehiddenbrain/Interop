# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

# Runtime stage
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home service \
 && mkdir -p /opt/member-profile && chown -R service:service /opt/member-profile
WORKDIR /opt/member-profile
COPY --from=build /src/build/libs/member-profile-service-*.jar app.jar
USER service
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD curl -fsS "http://localhost:${SERVER_PORT:-8081}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/opt/member-profile/app.jar"]
