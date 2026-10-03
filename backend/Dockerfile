# ---- Stage 1: build the jar with Maven (runs on Render, no local build needed) ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Stage 2: slim runtime image ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Sized for Render free/starter (512 MB). Spring binds server.port to $PORT, which Render injects.
ENV JAVA_OPTS="-Xmx350m -Xss512k -XX:+UseContainerSupport -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dspring.jmx.enabled=false"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
