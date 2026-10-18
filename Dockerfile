# 后端多阶段构建：Maven 编译 -> JRE 运行（非 root 用户）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先拷贝 pom.xml 并拉依赖，利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app

# 健康检查需要 curl；同时创建非 root 运行用户
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 1000 appuser

COPY --from=build /app/target/mojian.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser
ENV HOME=/home/appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
