FROM eclipse-temurin:21-jre
WORKDIR /app
# copy the fat jar built by spring-boot-maven-plugin
COPY target/booking-service-*.jar /app/app.jar
EXPOSE 8085
ENTRYPOINT ["java","-jar","/app/app.jar"]
