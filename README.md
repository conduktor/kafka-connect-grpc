# Kafka Connect gRPC Source Connector

A Kafka Connect source connector for streaming data from gRPC server streaming endpoints into Apache Kafka topics.

**[Documentation](https://conduktor.github.io/kafka-connect-grpc)**

## Architecture

```
┌─────────────────────┐     ┌──────────────────────────────┐     ┌─────────────────┐
│    gRPC Server      │     │       Kafka Connect          │     │   Apache Kafka  │
│  (Server Streaming) │────▶│   gRPC Source Connector      │────▶│      Topic      │
└─────────────────────┘     └──────────────────────────────┘     └─────────────────┘
         │                              │                              │
    Protobuf messages           Converts to Kafka              Downstream
    via streaming RPC           SourceRecords (JSON)           consumers
```

## Features

- Stream from gRPC server streaming endpoints
- Dynamic protobuf message handling via .desc files
- TLS/mTLS support for secure connections
- Automatic reconnection with exponential backoff
- Custom metadata/headers for authentication
- Configurable message buffering
- JMX metrics for monitoring

## Quick Start

### Installation

```bash
# Download the pre-built JAR
wget https://github.com/conduktor/kafka-connect-grpc/releases/download/v1.0.0/kafka-connect-grpc-1.0.0-jar-with-dependencies.jar

# Copy to Kafka Connect plugins directory
mkdir -p $KAFKA_HOME/plugins/kafka-connect-grpc
cp kafka-connect-grpc-1.0.0-jar-with-dependencies.jar $KAFKA_HOME/plugins/kafka-connect-grpc/

# Restart Kafka Connect
systemctl restart kafka-connect
```

### Deploy a Connector

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-grpc-stream",
    "config": {
      "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
      "tasks.max": "1",
      "grpc.server.host": "localhost",
      "grpc.server.port": "9090",
      "grpc.service.name": "com.example.MyService",
      "grpc.method.name": "StreamData",
      "kafka.topic": "grpc-messages"
    }
  }'
```

### Verify

```bash
# Check connector status
curl http://localhost:8083/connectors/my-grpc-stream/status

# Consume messages
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic grpc-messages --from-beginning
```

## Configuration

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `grpc.server.host` | Yes | - | gRPC server hostname |
| `grpc.server.port` | Yes | - | gRPC server port |
| `grpc.service.name` | Yes | - | Fully qualified service name |
| `grpc.method.name` | Yes | - | Server streaming method name |
| `kafka.topic` | Yes | - | Target Kafka topic |
| `grpc.proto.descriptor` | No | null | Path to .desc file or base64-encoded |
| `grpc.request.message` | No | `{}` | JSON request message |
| `grpc.tls.enabled` | No | false | Enable TLS |
| `grpc.tls.ca.cert` | No | null | Path to CA certificate |
| `grpc.metadata` | No | null | Custom headers (format: `key1:value1,key2:value2`) |
| `grpc.reconnect.enabled` | No | true | Enable automatic reconnection |
| `grpc.reconnect.interval.ms` | No | 5000 | Reconnection interval |
| `grpc.message.queue.size` | No | 10000 | In-memory buffer size |

## Limitations

- **Single task per connector**: gRPC streams are single-threaded by protocol design
- **Server streaming only**: Unary, client streaming, and bidirectional streaming not supported
- **At-most-once delivery**: Messages can be lost during shutdowns, crashes, or queue overflow
- **No replay capability**: gRPC protocol doesn't support offset-based replay

> **Note**: Best suited for telemetry, event streaming, and scenarios where occasional data loss is acceptable.

## Documentation

Full documentation is available at: **[conduktor.github.io/kafka-connect-grpc](https://conduktor.github.io/kafka-connect-grpc)**

- [Getting Started Guide](https://conduktor.github.io/kafka-connect-grpc/getting-started/)
- [Configuration Reference](https://conduktor.github.io/kafka-connect-grpc/getting-started/configuration/)
- [Monitoring & Operations](https://conduktor.github.io/kafka-connect-grpc/operations/RUNBOOK/)
- [FAQ](https://conduktor.github.io/kafka-connect-grpc/faq/)

## Development

### Building from Source

```bash
git clone https://github.com/conduktor/kafka-connect-grpc.git
cd kafka-connect-grpc
mvn clean package
```

Output: `target/kafka-connect-grpc-1.0.0-jar-with-dependencies.jar`

### Running Tests

```bash
# Run unit tests
mvn test

# Run integration tests (requires Docker)
mvn verify

# Run specific test class
mvn test -Dtest=GrpcSourceConnectorConfigTest
```

### Test Coverage

The test suite includes:

- **Unit Tests**: Configuration validation, connector lifecycle, task management
- **Integration Tests**: gRPC client behavior, TLS configuration, proto descriptor handling
- **System Integration Tests** (`GrpcConnectorSystemIT`): Full end-to-end testing with Testcontainers
  - Spins up Kafka and Kafka Connect containers
  - Deploys connector via REST API
  - Verifies connector plugin loading and status

> **Note**: System integration tests require Docker to be running

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
