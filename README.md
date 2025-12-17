# Kafka Connect gRPC Source Connector

A Kafka Connect source connector for streaming data from gRPC server streaming endpoints into Kafka topics.

## Features

- **Server Streaming Support**: Connects to gRPC server streaming RPCs and continuously receives messages
- **Dynamic Message Handling**: Uses Protocol Buffer descriptors for flexible message handling without requiring compiled proto classes
- **Automatic Reconnection**: Exponential backoff with configurable retry attempts
- **Offset Management**: Sequence-based offset tracking for reliable message delivery and exactly-once semantics
- **TLS Support**: Full TLS/mTLS support with configurable certificates
- **Backpressure Handling**: Configurable message queue with monitoring
- **JMX Metrics**: Comprehensive metrics for monitoring connector health and performance
- **Structured Logging**: Detailed logging with MDC context for observability

## Requirements

- Apache Kafka 3.9.0 or later
- Java 11 or later
- gRPC server with server streaming method
- Protocol Buffer descriptor file (.desc) for your service

## Installation

1. Build the connector:

```bash
mvn clean package
```

2. Copy the JAR with dependencies to your Kafka Connect plugins directory:

```bash
cp target/kafka-connect-grpc-1.0.0-jar-with-dependencies.jar /path/to/kafka/plugins/
```

3. Restart Kafka Connect to load the connector.

## Configuration

### Required Configuration

| Property | Description | Example |
|----------|-------------|---------|
| `grpc.server.host` | gRPC server hostname or IP address | `localhost` |
| `grpc.server.port` | gRPC server port | `9090` |
| `grpc.service.name` | Fully qualified service name | `com.example.MyService` |
| `grpc.method.name` | Server streaming method name | `StreamData` |
| `kafka.topic` | Target Kafka topic | `grpc-messages` |

### Optional Configuration

#### Request Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `grpc.request.message` | JSON representation of the request message | `{}` |
| `grpc.proto.descriptor` | Path to .desc file or base64-encoded descriptor | `null` |

#### TLS Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `grpc.tls.enabled` | Enable TLS for gRPC connection | `false` |
| `grpc.tls.ca.cert` | Path to CA certificate file | `null` |
| `grpc.tls.client.cert` | Path to client certificate for mTLS | `null` |
| `grpc.tls.client.key` | Path to client private key for mTLS | `null` |

#### Metadata/Headers

| Property | Description | Default |
|----------|-------------|---------|
| `grpc.metadata` | Custom metadata (format: `key1:value1,key2:value2`) | `null` |
| `grpc.metadata.<key>` | Individual metadata entries | `null` |

#### Reconnection

| Property | Description | Default |
|----------|-------------|---------|
| `grpc.reconnect.enabled` | Enable automatic reconnection | `true` |
| `grpc.reconnect.interval.ms` | Initial reconnection interval | `5000` |
| `grpc.reconnect.max.attempts` | Max reconnection attempts (-1 for infinite) | `-1` |
| `grpc.reconnect.backoff.max.ms` | Maximum backoff delay | `60000` |

#### Advanced Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `grpc.message.queue.size` | Message buffer queue size | `10000` |
| `grpc.connection.timeout.ms` | Connection timeout | `30000` |
| `grpc.keepalive.time.ms` | Keepalive time (0 to disable) | `30000` |
| `grpc.keepalive.timeout.ms` | Keepalive timeout | `10000` |
| `grpc.max.inbound.message.size` | Max inbound message size in bytes | `4194304` (4MB) |

## Usage Examples

### Basic Configuration

```json
{
  "name": "grpc-source-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with TLS

```json
{
  "name": "grpc-source-connector-tls",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "grpc.example.com",
    "grpc.server.port": "443",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.tls.enabled": "true",
    "grpc.tls.ca.cert": "/path/to/ca.crt",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with Mutual TLS

```json
{
  "name": "grpc-source-connector-mtls",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "grpc.example.com",
    "grpc.server.port": "443",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.tls.enabled": "true",
    "grpc.tls.ca.cert": "/path/to/ca.crt",
    "grpc.tls.client.cert": "/path/to/client.crt",
    "grpc.tls.client.key": "/path/to/client.key",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with Custom Metadata

```json
{
  "name": "grpc-source-connector-auth",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.metadata.authorization": "Bearer your-token-here",
    "grpc.metadata.x-api-key": "your-api-key",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with Request Message

```json
{
  "name": "grpc-source-connector-request",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.request.message": "{\"filter\": \"active\", \"limit\": 100}",
    "grpc.proto.descriptor": "/path/to/service.desc",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with Proto Descriptor

To generate a proto descriptor file:

```bash
protoc --descriptor_set_out=service.desc --include_imports your_service.proto
```

Then use it in the connector configuration:

```json
{
  "name": "grpc-source-connector-proto",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.proto.descriptor": "/path/to/service.desc",
    "kafka.topic": "grpc-messages"
  }
}
```

### Configuration with Reconnection Settings

```json
{
  "name": "grpc-source-connector-reconnect",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.MyService",
    "grpc.method.name": "StreamData",
    "grpc.reconnect.enabled": "true",
    "grpc.reconnect.interval.ms": "3000",
    "grpc.reconnect.max.attempts": "10",
    "grpc.reconnect.backoff.max.ms": "120000",
    "kafka.topic": "grpc-messages"
  }
}
```

## Offset Management

The connector uses sequence-based offset tracking to ensure reliable message delivery:

- **Session ID**: Unique identifier for each connection lifecycle
- **Sequence Number**: Monotonically increasing counter for each message
- **Offset Storage**: Automatically persisted by Kafka Connect framework

This enables:
- Detection of message gaps (potential data loss)
- Tracking across reconnections
- Resume from last committed position after restart

## Monitoring

### JMX Metrics

The connector exposes comprehensive JMX metrics under:
```
io.conduktor.connect.grpc:type=GrpcConnector,name=<connector-name>,server=<server>
```

Available metrics:
- `MessagesReceived`: Total messages received from gRPC
- `MessagesDropped`: Messages dropped due to queue overflow
- `RecordsProduced`: Total records produced to Kafka
- `QueueSize`: Current queue size
- `QueueUtilizationPercent`: Queue utilization percentage
- `IsConnected`: Connection status
- `MillisSinceLastMessage`: Time since last message received
- `UptimeMillis`: Connection uptime
- `TotalReconnects`: Total reconnection attempts
- `LagCount`: Messages received but not yet produced
- `DropRate`: Message drop rate percentage

### Structured Logging

The connector uses structured logging with `event=` prefixes for easy parsing and monitoring:

```
event=task_starting connector_name=grpc-source grpc_server=localhost:9090 grpc_method=com.example.MyService/StreamData
event=session_initialized session_id=abc123
event=streaming_started service=com.example.MyService method=StreamData
event=task_metrics connected=true messages_received=1000 queue_utilization_percent=45.2 status=HEALTHY
```

## Troubleshooting

### Connection Issues

**Problem**: Connector fails to connect to gRPC server

**Solutions**:
- Verify server host and port are correct
- Check network connectivity
- Ensure TLS configuration matches server requirements
- Check server logs for authentication/authorization errors

### Message Drops

**Problem**: `messages_dropped` metric is increasing

**Solutions**:
- Increase `grpc.message.queue.size`
- Check Kafka broker performance and connectivity
- Review `tasks.max` configuration
- Monitor queue utilization metrics

### Proto Descriptor Errors

**Problem**: Failed to load proto descriptor

**Solutions**:
- Ensure descriptor file includes all dependencies (`--include_imports`)
- Verify file path is accessible to connector
- Check descriptor file format (binary .desc file)
- Validate service and method names match descriptor

### Reconnection Issues

**Problem**: Connector keeps reconnecting

**Solutions**:
- Check server availability and stability
- Review server-side logs for errors
- Adjust `grpc.reconnect.interval.ms` and `grpc.reconnect.backoff.max.ms`
- Verify keepalive settings match server expectations

## Performance Tuning

### Queue Size

- **Default**: 10,000 messages
- **Increase**: For high-throughput streams
- **Decrease**: For memory-constrained environments

### Message Size

- **Default**: 4MB max inbound message size
- **Adjust**: `grpc.max.inbound.message.size` based on your message size

### Keepalive

- **Default**: 30s keepalive time
- **Adjust**: Based on network conditions and server settings
- **Disable**: Set `grpc.keepalive.time.ms` to `0` if not needed

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Kafka Connect Framework                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    GrpcSourceConnector                           │
│  - Configuration validation                                      │
│  - Task management                                               │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      GrpcSourceTask                              │
│  - poll() loop                                                   │
│  - Offset management (sequence-based)                            │
│  - Metrics logging                                               │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                        GrpcClient                                │
│  - gRPC channel management                                       │
│  - StreamObserver for server streaming                           │
│  - Message queue (LinkedBlockingDeque)                           │
│  - Reconnection logic with exponential backoff                   │
│  - Dynamic message handling via proto descriptors                │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
                   ┌────────────────┐
                   │  gRPC Server   │
                   │  (Streaming)   │
                   └────────────────┘
```

## Development

### Building

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

### Running Integration Tests

```bash
mvn verify
```

## License

This connector follows the same license as the kafka-connect-websocket project.

## Contributing

Contributions are welcome! Please ensure:
- Code follows existing patterns and style
- All tests pass
- New features include appropriate tests
- Documentation is updated

## Related Projects

- [kafka-connect-websocket](../kafka-connect-websocket) - WebSocket Source Connector for Kafka Connect
