# syntax=docker/dockerfile:1.5

# ==================== 构建阶段 ====================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 利用 Docker 层缓存 + Maven 本地仓库缓存
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# 复制源码并编译
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

# ==================== 运行阶段 ====================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="ezzziy"
LABEL description="OJ Code Sandbox Service"

RUN apk add --no-cache \
    docker-cli \
    curl \
    tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN mkdir -p /tmp/sandbox

EXPOSE 6060

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENV DOCKER_HOST="unix:///var/run/docker.sock"

USER root

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
