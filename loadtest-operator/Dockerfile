# The operator image (alternative to `mvn jib:dockerBuild`).
#   mvn -pl loadtest-operator -am package
#   docker build -t labuk-loadtest-operator:1.0-SNAPSHOT loadtest-operator
FROM eclipse-temurin:17-jre

ARG VERSION=1.0-SNAPSHOT
COPY target/lib/ /app/lib/
COPY target/loadtest-operator-${VERSION}.jar /app/loadtest-operator.jar

USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app/loadtest-operator.jar:/app/lib/*", "org.codelabuk.perf.operator.OperatorMain"]
