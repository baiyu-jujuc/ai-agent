# syntax=docker/dockerfile:1.7

FROM maven:3-eclipse-temurin-24 AS builder
WORKDIR /app

# 构建走阿里云 Maven 镜像：容器里从 Maven Central 拉 Spring AI 全家桶非常慢
COPY scripts/maven-aliyun-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY src src
# 缓存挂到 /root/.m2/repository（不要挂 /root/.m2，否则会把上面的 settings.xml 盖掉）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B --no-transfer-progress -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
