# Build stage
FROM golang:1.23-alpine AS builder

RUN apk add --no-cache protoc protobuf-dev git

WORKDIR /app

# Install protoc plugins (pinned versions compatible with Go 1.23)
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.34.2
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1

# Copy all source files
COPY go.mod stream.proto main.go ./

# Generate gRPC code from proto
RUN protoc --go_out=. --go-grpc_out=. stream.proto

# Generate proto descriptor for Kafka Connect gRPC connector
RUN protoc --descriptor_set_out=stream.desc --include_imports stream.proto

# Generate go.sum and download dependencies, then build
RUN go mod tidy && CGO_ENABLED=0 GOOS=linux go build -o server .

# Download grpc_health_probe - auto-detect architecture
RUN GRPC_HEALTH_PROBE_VERSION=v0.4.24 && \
    ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then \
        PROBE_ARCH="arm64"; \
    else \
        PROBE_ARCH="amd64"; \
    fi && \
    wget -qO/grpc_health_probe https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-${PROBE_ARCH} && \
    chmod +x /grpc_health_probe

# Runtime stage
FROM alpine:3.19

RUN apk add --no-cache ca-certificates

WORKDIR /app

COPY --from=builder /app/server /server
COPY --from=builder /app/stream.desc /app/stream.desc
COPY --from=builder /app/stream.proto /app/stream.proto
COPY --from=builder /grpc_health_probe /grpc_health_probe

EXPOSE 50051

CMD ["/server"]
