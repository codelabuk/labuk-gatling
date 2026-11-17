# Test-only image of the mock API (system under test for cluster tests). Build context: gatling-api-core/target
#   mvn -pl gatling-api-core test-compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
#   docker build -t labuk-mock-api:1.0-SNAPSHOT -f gatling-api-core/src/test/docker/Dockerfile gatling-api-core/target
FROM eclipse-temurin:17-jre
COPY lib/ /app/lib/
COPY classes/ /app/classes/
COPY test-classes/ /app/test-classes/
USER 1000
EXPOSE 18080
ENTRYPOINT ["java", "-cp", "/app/test-classes:/app/classes:/app/lib/*", "org.codelabuk.perf.it.MockApiMain", "18080", "0.0.0.0"]
