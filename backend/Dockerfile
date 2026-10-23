# syntax=docker/dockerfile:1
# 모든 Spring Boot 모듈이 같이 쓰는 이미지 템플릿. docker compose up --build 만으로 빌드부터 실행까지 한다.

# Note: 빌드 단계에는 MODULE 인자를 쓰지 않는다. 그래서 모든 서비스가 같은 빌드 단계를 공유하고, BuildKit이 한 번만 빌드해 캐시를 재사용한다.
# Gradle 의존성과 wrapper는 캐시 마운트(/root/.gradle)에 남아 다음 빌드부터 빨라진다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar -x test --no-daemon -q

FROM eclipse-temurin:17-jre
ARG MODULE
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/${MODULE}/build/libs/app.jar app.jar
USER app

ENV JAVA_OPTS="-Xms128m -Xmx384m" \
    TZ=Asia/Seoul
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
