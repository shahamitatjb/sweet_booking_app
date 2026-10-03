FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/jb-api-0.1.0.jar app.jar
ENV JAVA_OPTS="-Xmx350m -Xss512k -XX:+UseContainerSupport -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dspring.jmx.enabled=false"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
