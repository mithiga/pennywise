FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/sync-server-all.jar /app/app.jar
ENV SYNC_DATA_DIR=/data
ENV SYNC_PORT=8080
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
