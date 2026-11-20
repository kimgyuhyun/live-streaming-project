FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
