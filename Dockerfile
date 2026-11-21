FROM gradle:8.7-jdk17 AS builder
WORKDIR /workspace

COPY build.gradle settings.gradle ./
COPY src ./src

RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jdk
WORKDIR /app

RUN mkdir -p /app/heapdumps
COPY --from=builder /workspace/build/libs/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Dsun.net.inetaddr.ttl=0", "-Dsun.net.inetaddr.negative.ttl=0", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/heapdumps", "-jar", "/app/app.jar"]
