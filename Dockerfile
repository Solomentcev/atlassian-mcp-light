# Multi-stage Dockerfile для atlassian-mcp-server
# Stage 1: сборка (build stage)
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Копируем только файлы, необходимые для сборки (для кэширования слоёв)
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw

# Скачиваем зависимости (кэшируется, если pom.xml не менялся)
RUN ./mvnw dependency:go-offline -B

# Копируем исходный код и собираем
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: runtime (финальный образ)
FROM eclipse-temurin:17-jre-alpine

# Устанавливаем dumb-init для корректной обработки сигналов (stdin/stdout)
RUN apk add --no-cache dumb-init

# Создаём непривилегированного пользователя
RUN addgroup -g 1000 -S appuser && \
    adduser -u 1000 -S appuser -G appuser

WORKDIR /app

# Копируем собранный uber-jar из builder stage
COPY --from=builder /app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar app.jar

# Меняем владельца файлов
RUN chown -R appuser:appuser /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Переменные окружения по умолчанию (переопределяются при запуске)
ENV JIRA_URL="" \
    JIRA_EMAIL="" \
    CONFLUENCE_URL="" \
    ATLASSIAN_LOG_LEVEL="INFO"

# Запуск через dumb-init для корректной работы с stdin/stdout
# MCP-сервер работает в интерактивном режиме (stdin/stdout)
ENTRYPOINT ["dumb-init", "--"]
CMD ["java", "-jar", "app.jar"]
