FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/availability-service-*.jar /app/app.jar
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]
