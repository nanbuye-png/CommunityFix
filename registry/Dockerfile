FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:resolve
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/target/communityfix-registry-0.2.0.jar app.jar
USER app
EXPOSE 8761
ENTRYPOINT ["java","-jar","app.jar"]
