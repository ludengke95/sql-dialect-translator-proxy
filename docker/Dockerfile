# SDT Proxy Docker Image — 多阶段构建
# Stage 1: 编译阶段（使用 Maven 3.9 + JDK 21 官方镜像）
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace

# 复制项目所有文件
COPY . .

# 全量编译打包，并动态归一化应用目录到 /workspace/dist（避免版本号变化及双层目录嵌套问题）
RUN mvn clean package -DskipTests -B -q \
    && mkdir -p /workspace/dist \
    && cp -r /workspace/sdtp-server/target/sdtp-server-*/sdtp-server-*/* /workspace/dist/

# -------------------------------------------------------------------
# Stage 2: 运行阶段（仅 JRE 21 镜像）
FROM eclipse-temurin:21-jre

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

# 从编译阶段复制解压归一化后的应用文件目录
COPY --from=builder /workspace/dist /opt/sdtp

WORKDIR /opt/sdtp

# 日志目录
RUN mkdir -p logs

EXPOSE 7788

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["./bin/sdtp.sh", "start-fg"]
