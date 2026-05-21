FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache fontconfig ttf-dejavu
WORKDIR /app
COPY target/jrxml-renderer.jar /app/jrxml-renderer.jar
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-jar", "/app/jrxml-renderer.jar"]
