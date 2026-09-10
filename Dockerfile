# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B --no-transfer-progress -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
