# syntax=docker/dockerfile:1

# ---- build: Node, only to produce static files ----
# Debian slim rather than Alpine for this stage: the toolchain (TypeScript 7, Vite's bundler) ships
# native binaries, and glibc builds are the ones every package publishes. The stage is thrown away.
FROM node:22-bookworm-slim AS build
WORKDIR /app

# Lockfile first, so `npm ci` stays cached until dependencies change.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# Vite inlines VITE_* at build time (there's no runtime env in a static bundle). "/ws" means "this
# page's own origin, path /ws" (see src/config.ts), which nginx proxies to the WebSocket server, so
# REST, WebSocket and the session cookie are all on one origin.
ARG VITE_WS_URL=/ws
ENV VITE_WS_URL=$VITE_WS_URL
RUN npm run build

# ---- runtime: nginx serves dist/ and proxies /api and /ws ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
