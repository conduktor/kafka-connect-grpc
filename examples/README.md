# gRPC Connector Docker Example

This example demonstrates the gRPC Source Connector streaming data from a test gRPC server.

## Prerequisites

- Docker and Docker Compose
- Built connector JAR (run `mvn clean package` in parent directory)

## Quick Start

```bash
# Build the connector first
cd ..
mvn clean package -DskipTests

# Start the stack (builds test gRPC server)
cd examples
docker compose up -d --build

# Wait for services to be healthy (about 30-60 seconds)
docker compose ps

# Generate the proto descriptor for the connector
docker compose exec grpc-server cat /app/stream.proto > /tmp/stream.proto
protoc --descriptor_set_out=/tmp/stream.desc --include_imports /tmp/stream.proto

# Or use the pre-generated one from the test server
# The descriptor is generated during the Docker build

# Deploy the gRPC connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "grpc-test-source",
    "config": {
      "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
      "tasks.max": "1",
      "grpc.server.host": "grpc-server",
      "grpc.server.port": "50051",
      "grpc.service.name": "teststream.TestStreamService",
      "grpc.method.name": "StreamEvents",
      "grpc.request.message": "{\"interval_ms\": 2000}",
      "kafka.topic": "grpc-events"
    }
  }'

# Check connector status
curl http://localhost:8083/connectors/grpc-test-source/status | jq

# View messages in Kafka
docker exec kafka-grpc /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic grpc-events \
  --from-beginning \
  --max-messages 5
```

## What's Running

| Service | Port | Description |
|---------|------|-------------|
| Kafka | 9092 | Apache Kafka broker |
| Kafka Connect | 8083 | REST API for connector management |
| gRPC Test Server | 50051 | Test server with streaming RPC |
| Conduktor Console | 8080 | Web UI for Kafka management |

## Test gRPC Server

The included test server (`grpc-test-server/`) implements a simple streaming service:

```protobuf
service TestStreamService {
  rpc StreamEvents(StreamRequest) returns (stream StreamEvent);
}
```

It sends events continuously with configurable interval:
- `user.created`
- `order.placed`
- `payment.processed`
- `item.shipped`
- `user.login`

### Testing with grpcurl

```bash
# List services
grpcurl -plaintext localhost:50051 list

# Describe the service
grpcurl -plaintext localhost:50051 describe teststream.TestStreamService

# Call the streaming method
grpcurl -plaintext -d '{"interval_ms": 1000}' \
  localhost:50051 teststream.TestStreamService/StreamEvents
```

### Configuration Options

| Parameter | Description | Default |
|-----------|-------------|---------|
| `filter` | Filter events by type | (none) |
| `interval_ms` | Interval between events in ms | 1000 |

## Connector Configuration Examples

### Basic Configuration
```json
{
  "name": "grpc-basic",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "grpc-server",
    "grpc.server.port": "50051",
    "grpc.service.name": "teststream.TestStreamService",
    "grpc.method.name": "StreamEvents",
    "kafka.topic": "grpc-events"
  }
}
```

### With Request Parameters
```json
{
  "name": "grpc-filtered",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "grpc-server",
    "grpc.server.port": "50051",
    "grpc.service.name": "teststream.TestStreamService",
    "grpc.method.name": "StreamEvents",
    "grpc.request.message": "{\"filter\": \"order.placed\", \"interval_ms\": 500}",
    "kafka.topic": "grpc-orders"
  }
}
```

### With Reconnection Settings
```json
{
  "name": "grpc-resilient",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "grpc-server",
    "grpc.server.port": "50051",
    "grpc.service.name": "teststream.TestStreamService",
    "grpc.method.name": "StreamEvents",
    "grpc.reconnect.enabled": "true",
    "grpc.reconnect.interval.ms": "3000",
    "grpc.reconnect.max.attempts": "10",
    "kafka.topic": "grpc-events"
  }
}
```

## Viewing Data

### Via Console
Open http://localhost:8080 to access Conduktor Console and view the topics.

### Via CLI
```bash
# View events
docker exec kafka-grpc /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic grpc-events \
  --from-beginning

# Check topic metadata
docker exec kafka-grpc /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic grpc-events
```

## Testing Reconnection

```bash
# Stop the gRPC server
docker compose stop grpc-server

# Watch connector logs (should show reconnection attempts)
docker compose logs -f kafka-connect

# Restart the gRPC server
docker compose start grpc-server

# Connector should automatically reconnect
```

## Cleanup

```bash
docker compose down -v
```
