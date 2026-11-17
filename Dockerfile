# 서버를 실행 파일(jar) 하나로 묶어 Java만 있는 작은 이미지에 담는다.
# 빌드 단계(JDK + Gradle)와 실행 단계(JRE만)를 나눠 배포 이미지에 빌드 도구가 들어가지 않게 한다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# 의존성 내려받기를 먼저 해 두면 코드만 바뀌었을 때 이 단계는 캐시를 쓴다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies -q > /dev/null
COPY src/main src/main
RUN ./gradlew bootJar -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/nowstation-server-0.0.1-SNAPSHOT.jar app.jar
# 배포에 필요한 설정 파일도 이미지에 같이 담는다: EC2는 이미지만 받아 여기서 꺼내 쓴다(코드와 설정 버전이 항상 같음)
COPY compose.prod.yaml Caddyfile scripts/remote-deploy.sh /deploy/
COPY site /deploy/site
EXPOSE 8080
# 작은 서버(메모리 1GB)에서 MySQL과 같이 돌리므로 Java가 쓸 메모리를 컨테이너 한도의 일부로 묶는다
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]
