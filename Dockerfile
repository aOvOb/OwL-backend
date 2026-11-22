# Runtime image only: the jar is built by Gradle before `docker build` (see .github/workflows/deploy.yml).
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home owl
WORKDIR /app
COPY build/libs/owl.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
