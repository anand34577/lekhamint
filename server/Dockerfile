# syntax=docker/dockerfile:1

# --- build: static binary, no CGO (the SQLite driver is pure Go) ---
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/kosh ./cmd/kosh \
 && mkdir -p /out/data

# --- runtime: ~2 MB distroless base, no shell, runs as non-root ---
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/kosh /kosh
COPY --from=build --chown=nonroot:nonroot /out/data /data
ENV KOSH_DATA_DIR=/data \
    KOSH_ADDR=:8080 \
    KOSH_LOG_FORMAT=json
VOLUME ["/data"]
EXPOSE 8080
USER nonroot:nonroot
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/kosh", "healthcheck"]
ENTRYPOINT ["/kosh"]
CMD ["serve"]
