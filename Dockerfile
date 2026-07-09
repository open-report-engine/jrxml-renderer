FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY lib/ lib/
COPY src/ src/
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk-alpine
RUN mkdir -p /usr/share/fonts/custom /fonts && \
    apk add --no-cache fontconfig ttf-dejavu && \
    rm -rf /var/cache/apk/* /tmp/*
COPY --from=builder /app/target/jrxml-renderer.jar /app/jrxml-renderer.jar
COPY Carlito-Bold.ttf /app/ 2>/dev/null || true
COPY Carlito-Regular.ttf /app/ 2>/dev/null || true
COPY Carlito-Italic.ttf /app/ 2>/dev/null || true
COPY Carlito-BoldItalic.ttf /app/ 2>/dev/null || true
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
WORKDIR /app
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["docker-entrypoint.sh"]
