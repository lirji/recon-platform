FROM node:20-alpine AS build

RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .

# A1 认证: Vite 环境变量在构建期烘焙进 bundle (import.meta.env)。默认 dev(免 Casdoor,
# 对应后端 DevSecurityConfig permitAll);secure/联调经 compose build.args 传 oidc + Casdoor 参数。
ARG VITE_AUTH_MODE=dev
ARG VITE_CASDOOR_SERVER_URL=http://localhost:8000
ARG VITE_CASDOOR_CLIENT_ID=
ARG VITE_CASDOOR_ORGANIZATION=recon-platform
ARG VITE_CASDOOR_APP_NAME=rag-shared
ARG VITE_CASDOOR_SCOPE="openid profile offline_access"
ENV VITE_AUTH_MODE=$VITE_AUTH_MODE \
    VITE_CASDOOR_SERVER_URL=$VITE_CASDOOR_SERVER_URL \
    VITE_CASDOOR_CLIENT_ID=$VITE_CASDOOR_CLIENT_ID \
    VITE_CASDOOR_ORGANIZATION=$VITE_CASDOOR_ORGANIZATION \
    VITE_CASDOOR_APP_NAME=$VITE_CASDOOR_APP_NAME \
    VITE_CASDOOR_SCOPE=$VITE_CASDOOR_SCOPE
RUN pnpm build

FROM nginx:1.27-alpine

ENV RECON_API_URL=http://host.docker.internal:8080 \
    NGINX_ENVSUBST_FILTER=RECON_API_URL

COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8088
