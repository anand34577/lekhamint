# syntax=docker/dockerfile:1
# LekhaMint server with the web app built in. Multi-arch: linux/amd64, arm64, arm/v7.
#   docker build -t lekhamint .

# --- web app ---
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# --- server: static binary, no CGO (the SQLite driver is pure Go) ---
FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS build
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN rm -rf internal/webui/dist
COPY --from=web /web/dist ./internal/webui/dist
ARG VERSION=dev
ARG TARGETOS TARGETARCH TARGETVARIANT
RUN GOARM="${TARGETVARIANT#v}" CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/lekhamint ./cmd/lekhamint \
 && mkdir -p /out/data

# --- runtime: distroless, no shell, runs as non-root (uid 65532) ---
FROM gcr.io/distroless/static-debian12:nonroot
ARG VERSION=dev
LABEL org.opencontainers.image.title="LekhaMint" \
      org.opencontainers.image.description="Self-hosted sync server and web app for the LekhaMint money manager" \
      org.opencontainers.image.source="https://github.com/anand34577/lekhamint" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=build /out/lekhamint /lekhamint
COPY --from=build --chown=nonroot:nonroot /out/data /data
ENV LEKHAMINT_DATA_DIR=/data \
    LEKHAMINT_ADDR=:8080 \
    LEKHAMINT_LOG_FORMAT=json
VOLUME ["/data"]
EXPOSE 8080
USER nonroot:nonroot
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/lekhamint", "healthcheck"]
ENTRYPOINT ["/lekhamint"]
CMD ["serve"]
