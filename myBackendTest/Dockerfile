# ===================== BUILD STAGE =====================
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Maven Wrapper kopieren
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Dependencies zuerst laden (Docker Layer Cache)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Source Code kopieren und bauen
COPY src src
RUN ./mvnw package -DskipTests -B

# ===================== RUN STAGE =====================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# JAR aus Build-Stage kopieren
COPY --from=build /app/target/*.jar app.jar

# Spring Profile auf "docker" setzen
ENV SPRING_PROFILES_ACTIVE=docker

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
