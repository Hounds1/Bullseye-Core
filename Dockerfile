FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY bullseye-common ./bullseye-common
COPY bullseye-core ./bullseye-core
COPY bullseye-native ./bullseye-native
COPY config ./config

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew :bullseye-core:installDist --no-daemon

FROM eclipse-temurin:25-jre

WORKDIR /opt/bullseye
COPY --from=build /workspace/bullseye-core/build/install/bullseye-core/ ./
COPY config/bullseye-docker.properties ./config/bullseye-docker.properties

ENTRYPOINT ["./bin/bullseye-core"]
CMD ["config/bullseye.properties"]
