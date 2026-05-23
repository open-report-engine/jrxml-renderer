FROM jrxml-renderer:test AS jar-extract
FROM eclipse-temurin:21-jdk-alpine
RUN mkdir -p /usr/share/fonts/custom /fonts && \
    apk add --no-cache fontconfig ttf-dejavu && \
    rm -rf /var/cache/apk/* /tmp/*
COPY --from=jar-extract /app/jrxml-renderer.jar /app/jrxml-renderer.jar
COPY --from=jar-extract /app/Carlito-Bold.ttf /app/
COPY --from=jar-extract /app/Carlito-Regular.ttf /app/
COPY --from=jar-extract /app/Carlito-Italic.ttf /app/
COPY --from=jar-extract /app/Carlito-BoldItalic.ttf /app/
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
WORKDIR /app
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["docker-entrypoint.sh"]
