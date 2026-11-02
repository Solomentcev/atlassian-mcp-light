# Сборка многомодульного atlassian-mcp-server
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .
COPY atlassian-core/pom.xml atlassian-core/pom.xml
COPY atlassian-jira/pom.xml atlassian-jira/pom.xml
COPY atlassian-confluence/pom.xml atlassian-confluence/pom.xml
COPY atlassian-mcp-app/pom.xml atlassian-mcp-app/pom.xml

RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

COPY atlassian-core/src atlassian-core/src
COPY atlassian-jira/src atlassian-jira/src
COPY atlassian-confluence/src atlassian-confluence/src
COPY atlassian-mcp-app/src atlassian-mcp-app/src

RUN ./mvnw clean package -pl atlassian-mcp-app -am -DskipTests -B

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache dumb-init
RUN addgroup -g 1000 -S appuser && adduser -u 1000 -S appuser -G appuser

WORKDIR /app

COPY --from=builder /app/atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar app.jar

RUN chown -R appuser:appuser /app
USER appuser

ENV JIRA_URL="" \
    JIRA_EMAIL="" \
    CONFLUENCE_URL="" \
    ATLASSIAN_LOG_LEVEL="INFO"

ENTRYPOINT ["dumb-init", "--"]
CMD ["java", "-jar", "app.jar"]
