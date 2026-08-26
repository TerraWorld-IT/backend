FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# 비-root 실행: eclipse-temurin alpine 이미지에는 앱 전용 계정이 없어 직접 만든다.
# -S = 시스템 계정/그룹(로그인 불가, UID/GID 자동 할당).
RUN addgroup -S app && adduser -S -G app app
# --chown 으로 복사 시점에 소유권을 넘긴다 (COPY 는 원본 퍼미션을 보존하므로
# 빌드 호스트의 jar 가 제한적 퍼미션이면 app 계정이 못 읽는 상황을 방지).
COPY --chown=app:app build/libs/*.jar app.jar
# BE-14 (2026-07-15 성능 감사): 컨테이너 memory limit 미설정 환경이라 MaxRAMPercentage 전환 금지
# (limit 부재 시 호스트 RAM 비율로 계산돼 과대 heap). heap 512m 유지 + 보수적 가드만 추가:
# - MaxMetaspaceSize: metaspace 무한 성장 차단 (비-heap OOM 의 조기 가시화)
# - ExitOnOutOfMemoryError: OOM 후 half-dead 상태로 살아있는 대신 즉시 종료 → 오케스트레이터 재기동
# compose/k8s 에서 memory limit 설정 후 MaxRAMPercentage 전환 검토.
# -Duser.timezone=UTC: 무인자 now() 를 UTC 로 간주하는 저장·응답 계약을 JVM 시작부터 명시(코드의 JvmTimeZone.pinUtc 와 같은 값).
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
USER app
# HEALTHCHECK 는 여기 두지 않는다. deploy/docker-compose.yml 이 backend 서비스에
# 자체 healthcheck 를 정의하고 있고, compose 의 healthcheck 는 이미지의 것을 **덮어쓴다**.
# 둘 다 두면 실제로는 compose 쪽만 동작하면서 두 정의가 조용히 어긋난다
# (compose 는 8080 하드코딩, 이미지는 ${SERVER_PORT:-8080} 해석 — .env 가 8082 면 불일치).
# 컨테이너 헬스체크의 SoT 는 compose 다.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
