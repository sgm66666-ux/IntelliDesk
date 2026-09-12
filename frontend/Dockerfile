# IntelliDesk — single `web` image (Vue static + Nginx reverse proxy)
#
# Stage 1: build the Vue production bundle inside the image (npm ci + npm run build).
#          dist is generated here at build time — the host never needs npm, and no
#          host dist is bind-mounted.
# Stage 2: Nginx runtime serving the built dist + proxying /api/* to backend.
#
# Build context = repo root (see compose `build.context: ..`) so that
# deploy/nginx/nginx.conf is available to COPY. A root .dockerignore keeps the
# context slim.

# ---- Stage 1: build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
RUN ls -la dist

# ---- Stage 2: Nginx runtime (no Node) ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD wget -qO- http://localhost/ || exit 1