# Build the Next.js standalone server.
FROM node:22-bookworm-slim AS deps

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-bookworm-slim AS build

WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:22-bookworm-slim AS runtime

WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
ENV NEXT_PUBLIC_API_BASE_URL=/api/v1

RUN groupadd --system nodejs && useradd --system --gid nodejs --home-dir /app nextjs

COPY --from=build --chown=nextjs:nodejs /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# The entrypoint writes runtime config to /tmp, which works with a read-only root filesystem.
RUN rm -f ./public/env-config.js \
    && ln -s /tmp/env-config.js ./public/env-config.js \
    && chown -h nextjs:nodejs ./public/env-config.js \
    && chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 3000
USER nextjs

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["node", "server.js"]
