# ---- build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --system --no-create-home wayfinder
COPY --from=build /workspace/target/*.jar app.jar
USER wayfinder

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
