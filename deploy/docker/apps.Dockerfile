# syntax=docker/dockerfile:1.7
# PTMCP 三个 Boot 进程镜像（同一构建阶段产出三个 target，共享 Maven/前端缓存）。
#
#   docker build -f deploy/docker/apps.Dockerfile --target admin   -t ptmcp/admin:0.1.0 .
#   docker build -f deploy/docker/apps.Dockerfile --target authz   -t ptmcp/authz:0.1.0 .
#   docker build -f deploy/docker/apps.Dockerfile --target gateway -t ptmcp/gateway:0.1.0 .
#
# 基础镜像按 digest 固定；升级时同时改下面三个 ARG。
ARG MAVEN_IMAGE=maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e
ARG JRE_IMAGE=eclipse-temurin@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
ARG NODE_IMAGE=node@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8

# OAuth 登录/授权页由 authz 托管；页面目录结构为 <dir>/oauth/*.html + <dir>/assets。
FROM ${NODE_IMAGE} AS oauth-pages
WORKDIR /src
RUN corepack enable
COPY ptmcp-ui-react/package.json ptmcp-ui-react/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY ptmcp-ui-react/ ./
RUN pnpm build:oauth

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY ptmcp-parent/ ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dmaven.test.skip=true -pl ptmcp-admin,ptmcp-authz,ptmcp-gateway -am package
# -plain.jar 不匹配该通配，避免把不可执行包带进镜像。
RUN mkdir -p /out \
 && cp ptmcp-admin/target/ptmcp-admin-*-SNAPSHOT.jar /out/admin.jar \
 && cp ptmcp-authz/target/ptmcp-authz-*-SNAPSHOT.jar /out/authz.jar \
 && cp ptmcp-gateway/target/ptmcp-gateway-*-SNAPSHOT.jar /out/gateway.jar

FROM ${JRE_IMAGE} AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 10001 ptmcp \
 && useradd --uid 10001 --gid 10001 --home-dir /var/lib/ptmcp \
        --no-create-home --shell /usr/sbin/nologin ptmcp \
 && mkdir -p /app /var/lib/ptmcp/wal \
 && chown -R 10001:10001 /app /var/lib/ptmcp
# 三个模块默认只监听回环；容器内必须显式放开（Spring relaxed binding）。
ENV SERVER_ADDRESS=0.0.0.0
WORKDIR /app
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

FROM runtime AS admin
COPY --from=build /out/admin.jar /app/app.jar
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

FROM runtime AS authz
COPY --from=build /out/authz.jar /app/app.jar
COPY --from=oauth-pages /src/dist-oauth/ /srv/oauth-pages/
ENV PTMCP_AUTHZ_OAUTH_PAGES_DIR=/srv/oauth-pages
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health || exit 1

FROM runtime AS gateway
COPY --from=build /out/gateway.jar /app/app.jar
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8082/actuator/health || exit 1
