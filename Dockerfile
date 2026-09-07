FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Aliyun Maven mirror for faster downloads in China
RUN mkdir -p /root/.m2 && cat > /root/.m2/settings.xml << 'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>*</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
    <mirror>
      <id>aliyun-spring</id>
      <mirrorOf>spring-milestones</mirrorOf>
      <url>https://maven.aliyun.com/repository/spring</url>
    </mirror>
  </mirrors>
</settings>
EOF

COPY pom.xml .
RUN mvn -B --no-transfer-progress dependency:go-offline
COPY src src
RUN mvn -B --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
