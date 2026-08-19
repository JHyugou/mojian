# 后端多阶段构建：Maven 编译 -> JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先拷贝 pom.xml 并拉依赖，利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/mojian.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]