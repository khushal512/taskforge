FROM maven:3-eclipse-temurin-21-noble AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# CI verifies with Testcontainers before building; local Compose builds run independently.
# Run mvn verify separately when packaging locally.
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN test -x /usr/bin/curl
COPY --from=build /workspace/target/taskforge-*.jar /app/taskforge.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/taskforge.jar"]
