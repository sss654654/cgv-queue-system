# ===============================================================
# CGV Backend Multi-stage Dockerfile
# ===============================================================
# Build: Maven + JDK 17
# Runtime: JRE 17 only (minimal attack surface, ~60% smaller)
# ---------------------------------------------------------------

# 1. Build Stage
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

# Maven Wrapper + pom.xml 먼저 복사 (의존성 캐시 레이어)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# 소스 복사 후 빌드
COPY src ./src
RUN ./mvnw package -DskipTests -B

# 2. Runtime Stage (JRE only - no JDK, no AWS CLI)
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser

# JAR 복사
COPY --from=builder /app/target/*.jar app.jar

# 소유권 설정
RUN chown -R appuser:appuser /app
USER appuser

# Health check - K8s probe 외 Docker 자체 체크
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health/live || exit 1

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
