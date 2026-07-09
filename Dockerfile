FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
COPY lib/ lib/
RUN mvn install:install-file -Dfile=lib/ljp-7.0.6.jar -DgroupId=io.github.open-report-engine -DartifactId=ljp -Dversion=7.0.6 -Dpackaging=jar
RUN mvn dependency:resolve
COPY src/ src/
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk-alpine
RUN mkdir -p /usr/share/fonts/custom /fonts && \
    apk add --no-cache fontconfig ttf-dejavu && \
    rm -rf /var/cache/apk/* /tmp/*
COPY --from=builder /app/target/jrxml-renderer.jar /app/jrxml-renderer.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
WORKDIR /app
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["docker-entrypoint.sh"]
