# Etapa 1: Construcción (Build)
FROM maven:3.8.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Etapa 2: Ejecución (Run)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Exponemos el puerto estándar de Spring Boot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
