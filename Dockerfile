FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY build/libs/*.jar app.jar
# BE-14 (2026-07-15 성능 감사): 컨테이너 memory limit 미설정 환경이라 MaxRAMPercentage 전환 금지
# (limit 부재 시 호스트 RAM 비율로 계산돼 과대 heap). heap 512m 유지 + 보수적 가드만 추가:
# - MaxMetaspaceSize: metaspace 무한 성장 차단 (비-heap OOM 의 조기 가시화)
# - ExitOnOutOfMemoryError: OOM 후 half-dead 상태로 살아있는 대신 즉시 종료 → 오케스트레이터 재기동
# compose/k8s 에서 memory limit 설정 후 MaxRAMPercentage 전환 검토.
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
