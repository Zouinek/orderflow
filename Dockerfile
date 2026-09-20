# ---- build stage: compile inside the image so the build is reproducible ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# copy the poms FIRST (parent + module) so dependency download is cached
# and only re-runs when a pom changes, not on every source edit
COPY pom.xml .
COPY services/order/pom.xml services/order/
RUN mvn -q -f services/order/pom.xml dependency:go-offline

COPY services/order/src services/order/src
RUN mvn -q -f services/order/pom.xml clean package -DskipTests

# ---- run stage: tiny JRE image, no Maven, no source ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# non-root user (least privilege; many clusters reject root via Pod Security)
RUN useradd -r -u 1001 spring
USER 1001

COPY --from=build /app/services/order/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT [ "java", "-jar", "app.jar" ]
