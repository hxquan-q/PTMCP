# syntax=docker/dockerfile:1.7
# PTMCP 控制台 SPA + 同源入口（nginx）。OAuth 页面不走这个镜像，由 authz 自带。
#
#   docker build -f deploy/docker/ui.Dockerfile -t ptmcp/ui:0.1.0 .
ARG NODE_IMAGE=node@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8
ARG NGINX_IMAGE=nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

FROM ${NODE_IMAGE} AS build
WORKDIR /src
RUN corepack enable
COPY ptmcp-ui-react/package.json ptmcp-ui-react/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY ptmcp-ui-react/ ./
RUN pnpm build

FROM ${NGINX_IMAGE}
# 非 root 运行：临时文件与 pid 全部落在 /tmp，compose 用 read_only + tmpfs /tmp 承载。
COPY deploy/docker/nginx.conf /etc/nginx/nginx.conf
RUN rm -f /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/ /usr/share/nginx/html/
USER nginx
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
