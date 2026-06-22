# keepIT frontend — build the Vite app, serve the static bundle with nginx.
# The frontend is its own deployable (ARCHITECTURE.md): no ASP.NET Core hosting it.

FROM node:22-alpine AS build
WORKDIR /app
# Install deps first for layer caching. The generated API client (src/api/schema.d.ts) is committed,
# so the build needs no running backend.
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine AS final
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
