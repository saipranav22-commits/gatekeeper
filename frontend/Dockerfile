# ==============================================================================
# Gatekeeper Frontend Dashboard - Production Multi-Stage Dockerfile
# ==============================================================================
# Stage 1: Build Frontend Assets
# ==============================================================================
FROM node:20-alpine AS builder

WORKDIR /app

# Cache package dependencies
COPY package.json package-lock.json ./
RUN npm ci

# Copy source code and build production bundle
COPY . .
RUN npm run build

# ==============================================================================
# Stage 2: Hardened Nginx Web Server
# ==============================================================================
FROM nginx:1.27-alpine AS runtime

LABEL maintainer="Gatekeeper Architecture Team" \
      description="Gatekeeper Admin Dashboard Frontend"

# Remove default nginx welcome page
RUN rm -rf /usr/share/nginx/html/*

# Copy built distribution from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy production Nginx reverse proxy configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# Healthcheck validating Nginx is serving content
HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:80/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
