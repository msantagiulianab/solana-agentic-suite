# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Production image for the x402 MCP compliance server (stdio transport).
#
# Build context: this directory (agent-tools/mcp-server), e.g.
#   docker build -t x402-mcp-server .
#
# The build compiles src/ -> dist/ with tsc so the running container always
# serves the current toolset (screen_solana_address, get_channel_status,
# generate_compliance_report).
# ---------------------------------------------------------------------------

# ---- Stage 1: builder — install all deps and compile TypeScript -----------
FROM node:20-alpine AS builder

WORKDIR /app

# Install dependencies first (including devDependencies such as typescript).
COPY package.json package-lock.json ./
RUN npm ci

# Compile the TypeScript source into the ESM dist/ bundle.
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ---- Stage 2: runtime — minimal image with production dependencies only ----
FROM node:20-alpine AS runtime

ENV NODE_ENV=production

WORKDIR /app

# Production-only dependencies, then drop the npm cache.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

# Copy the compiled server emitted by the builder stage.
COPY --from=builder /app/dist ./dist

# Run as the unprivileged node user (least privilege). stdio transport, so no
# network port is exposed.
USER node

ENTRYPOINT ["node", "dist/index.js"]
