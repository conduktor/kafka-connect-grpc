# Frequently Asked Questions

Common questions and answers about the Kafka Connect gRPC Source Connector.

## General Questions

### What is this connector used for?

The Kafka Connect gRPC connector streams real-time data from gRPC server streaming endpoints into Kafka topics. It's ideal for:

- Microservices communication using gRPC
- Event streaming from gRPC-based platforms
- Real-time data pipelines with gRPC sources
- Cloud-native applications exposing gRPC APIs
- IoT and telemetry data from gRPC-enabled devices
- Financial services data feeds via gRPC

### How does it differ from the WebSocket connector?

| Feature | gRPC Connector | WebSocket Connector |
|---------|---------------|---------------------|
| **Protocol** | HTTP/2 + Protocol Buffers | WebSocket |
| **Schema** | Strong typing via Protobuf | String messages only |
| **Streaming** | Server streaming RPC | WebSocket push |
| **TLS/mTLS** | Built-in support | TLS only |
| **Offset Tracking** | Sequence-based | None |
| **Message Format** | Binary (Protobuf) | Text (JSON typically) |
| **Use Cases** | Microservices, typed APIs | Exchange feeds, IoT |

### What gRPC patterns are supported?

**Supported:**
- ✅ Server streaming (one request, stream of responses)

**Not Supported:**
- ❌ Unary (request-response)
- ❌ Client streaming (stream of requests, one response)
- ❌ Bidirectional streaming (stream both ways)

Only server streaming is compatible with Kafka Connect's source connector model.

## Installation & Setup

### Do I need to build from source?

**No.** Pre-built JARs are available from [GitHub Releases](https://github.com/conduktor/kafka-connect-grpc/releases):

```bash
# Download the latest release
wget https://github.com/conduktor/kafka-connect-grpc/releases/download/v1.0.0/kafka-connect-grpc-1.0.0-jar-with-dependencies.jar

# Copy to plugin directory
cp kafka-connect-grpc-1.0.0-jar-with-dependencies.jar $KAFKA_HOME/plugins/kafka-connect-grpc/
```

Building from source is only needed for development or custom modifications.

### Which Kafka version do I need?

**Minimum:** Kafka 3.9.0
**Recommended:** Latest stable Kafka version

The connector uses Kafka Connect API features available in 3.9.0+.

### Do I need protoc installed?

**Yes**, if you need to generate Protocol Buffer descriptor files (.desc).

```bash
# Install protoc
brew install protobuf  # macOS
sudo apt install protobuf-compiler  # Ubuntu/Debian

# Generate descriptor
protoc --descriptor_set_out=service.desc \
  --include_imports \
  your_service.proto
```

The descriptor file is required for dynamic message handling.

### Can I use this with Confluent Platform?

Yes, the connector works with:

- Apache Kafka (open source)
- Confluent Platform
- Amazon MSK (Managed Streaming for Kafka)
- Azure Event Hubs for Kafka
- Any Kafka-compatible platform supporting Connect API

## Configuration

### What's the minimum configuration?

Five required parameters:

```json
{
  "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
  "grpc.server.host": "localhost",
  "grpc.server.port": "9090",
  "grpc.service.name": "com.example.MyService",
  "grpc.method.name": "StreamData",
  "kafka.topic": "grpc-messages"
}
```

### How do I configure TLS/mTLS?

**TLS (server verification only):**
```json
{
  "grpc.tls.enabled": "true",
  "grpc.tls.ca.cert": "/path/to/ca.crt"
}
```

**mTLS (mutual authentication):**
```json
{
  "grpc.tls.enabled": "true",
  "grpc.tls.ca.cert": "/path/to/ca.crt",
  "grpc.tls.client.cert": "/path/to/client.crt",
  "grpc.tls.client.key": "/path/to/client.key"
}
```

### How do I add custom metadata/headers?

**Option 1: Comma-separated format**
```json
{
  "grpc.metadata": "authorization:Bearer token123,x-api-key:key456"
}
```

**Option 2: Individual entries**
```json
{
  "grpc.metadata.authorization": "Bearer token123",
  "grpc.metadata.x-api-key": "key456"
}
```

### How do I send a request message?

Use `grpc.request.message` with JSON format:

```json
{
  "grpc.request.message": "{\"filter\":\"active\",\"limit\":100}",
  "grpc.proto.descriptor": "/path/to/service.desc"
}
```

The JSON is converted to Protobuf using the descriptor file.

### Can I connect to multiple gRPC endpoints?

No, each connector instance connects to one gRPC endpoint. To stream from multiple endpoints:

1. Create separate connector instances
2. Use different connector names
3. Route to the same or different Kafka topics

Example:
```bash
# Connector 1: Service A
curl -X POST http://localhost:8083/connectors \
  -d '{"name":"grpc-service-a", "config":{...}}'

# Connector 2: Service B
curl -X POST http://localhost:8083/connectors \
  -d '{"name":"grpc-service-b", "config":{...}}'
```

## Proto Descriptors

### What is a proto descriptor file?

A compiled Protocol Buffer schema containing all message and service definitions. It's used for dynamic message handling without code generation.

### How do I generate a descriptor file?

```bash
protoc --descriptor_set_out=service.desc \
  --include_imports \
  your_service.proto
```

**Important:** Always use `--include_imports` to include dependencies.

### Can I use base64-encoded descriptors?

Yes, you can base64-encode the descriptor and provide it inline:

```bash
# Encode descriptor
base64 service.desc > service.desc.b64

# Use in configuration
{
  "grpc.proto.descriptor": "$(cat service.desc.b64)"
}
```

### What if my proto imports other files?

Use `--include_imports` when generating the descriptor:

```bash
protoc --descriptor_set_out=service.desc \
  --include_imports \
  --proto_path=. \
  --proto_path=./vendor \
  your_service.proto
```

This includes all imported message definitions in the descriptor.

## Data & Reliability

### What delivery guarantees does this connector provide?

The connector provides **at-least-once** delivery semantics with sequence-based offset tracking:

- Messages are tracked by sequence number
- Offsets are committed to Kafka Connect
- Gaps in sequence numbers are detected and logged
- Cannot replay from gRPC server (server streaming limitation)

### What happens if the connector crashes?

1. **In-memory queue**: Messages in queue are lost
2. **Offset tracking**: Last committed offset is preserved
3. **Reconnection**: Connector reconnects and requests new stream
4. **Gap detection**: Sequence gaps are logged as warnings

The connector cannot replay lost messages from the gRPC server.

### Can I replay historical data?

**No.** gRPC server streaming limitations:

- Server doesn't store message history
- Cannot "rewind" to previous position
- Each connection gets a new stream starting from current state

For historical data, use the gRPC server's unary RPC methods (if available) or another mechanism.

### What data format is produced to Kafka?

Messages are produced as **JSON strings** (converted from Protobuf):

```json
{
  "topic": "grpc-messages",
  "partition": 0,
  "offset": 12345,
  "key": null,
  "value": "{\"field1\":\"value1\",\"field2\":123}"
}
```

For structured processing, use Kafka Streams or ksqlDB to parse JSON downstream.

### How are offsets tracked?

The connector uses sequence-based offset tracking:

- **Session ID**: Unique per connection (UUID)
- **Sequence Number**: Monotonically increasing counter
- **Offset Format**: `{"sessionId":"abc123","sequence":1000}`

This enables gap detection and tracking across reconnections.

## Operations

### How do I monitor the connector?

Three monitoring approaches:

1. **JMX Metrics** (recommended):
   ```bash
   # View metrics via JMX
   jconsole localhost:9999
   ```

2. **Structured Logging**:
   ```bash
   tail -f $KAFKA_HOME/logs/connect.log | grep "event="
   ```

3. **Kafka Connect REST API**:
   ```bash
   curl http://localhost:8083/connectors/grpc-connector/status
   ```

See [Configuration](getting-started/configuration.md) for monitoring setup.

### What metrics should I alert on?

Critical alerts:

- **Connector down** - `connector.state != RUNNING`
- **Not connected** - `IsConnected = false` for > 5 minutes
- **No messages** - `MessagesReceived` not increasing
- **Queue overflow** - `QueueUtilizationPercent > 80%`
- **High reconnection rate** - `TotalReconnects` increasing rapidly

### How do I troubleshoot connection failures?

1. **Test gRPC endpoint**:
   ```bash
   grpcurl -plaintext localhost:9090 list
   grpcurl -plaintext localhost:9090 com.example.MyService/StreamData
   ```

2. **Check TLS configuration**:
   ```bash
   openssl s_client -connect grpc-server:443
   ```

3. **Review connector logs**:
   ```bash
   grep "event=connection_failed" $KAFKA_HOME/logs/connect.log
   ```

4. **Verify proto descriptor**:
   ```bash
   grpcurl -protoset service.desc list
   ```

### How do I update connector configuration?

**For running connectors:**

```bash
curl -X PUT http://localhost:8083/connectors/grpc-connector/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json
```

The connector will restart automatically with new configuration.

**Note:** Some changes require full restart (TLS, server host/port, service/method names).

### Can I pause and resume the connector?

Yes, use the Kafka Connect REST API:

**Pause:**
```bash
curl -X PUT http://localhost:8083/connectors/grpc-connector/pause
```

**Resume:**
```bash
curl -X PUT http://localhost:8083/connectors/grpc-connector/resume
```

**Warning:** Pausing closes the gRPC connection. Messages sent during pause are lost.

## Performance

### What throughput can I expect?

Typical performance on standard hardware (4 CPU, 8 GB RAM):

- **Message rate**: 10,000+ messages/second
- **Latency**: < 5ms from gRPC receipt to Kafka produce
- **Queue capacity**: Configurable (default: 10,000 messages)

Actual throughput depends on:
- Message size
- Kafka broker performance
- Network latency
- gRPC server throughput

### How many tasks can I run per connector?

**Always 1 task** per connector.

gRPC server streaming connections are single-threaded by design. Each connector maintains one gRPC stream.

To parallelize:
- Create multiple connector instances
- Each connects to different service/method or uses different request filters

### What's the memory footprint?

Memory usage depends on queue size and message size:

**Formula:**
```
Memory ≈ queue_size × avg_message_size × 2
```

**Example:**
- Queue size: 10,000 messages
- Average message: 2 KB (Protobuf)
- Memory: ~40 MB (with overhead)

**Recommendation:**
- Development: 512 MB heap
- Production: 2 GB heap

### How do I optimize throughput?

1. **Increase queue size** (handle bursts):
   ```properties
   grpc.message.queue.size=50000
   ```

2. **Increase message size limit** (if needed):
   ```properties
   grpc.max.inbound.message.size=16777216
   ```

3. **Optimize Kafka producer** (in `connect-distributed.properties`):
   ```properties
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   ```

4. **Tune keepalive** (reduce overhead):
   ```properties
   grpc.keepalive.time.ms=60000
   ```

## Troubleshooting

### Why isn't my connector appearing in the plugin list?

**Check:**

1. JAR is in the correct plugin directory
2. `plugin.path` is configured in `connect-distributed.properties`
3. Kafka Connect was restarted after installation
4. All dependencies are present (should use uber JAR)

**Verify:**
```bash
ls -lh $KAFKA_HOME/plugins/kafka-connect-grpc/
curl http://localhost:8083/connector-plugins | jq
```

### Why do I get "UNAVAILABLE: io exception"?

**Cause:** gRPC server not reachable.

**Solution:**
```bash
# Test connectivity
telnet grpc-server 9090

# Test with grpcurl
grpcurl -plaintext grpc-server:9090 list

# Check connector logs
grep "event=connection_failed" $KAFKA_HOME/logs/connect.log
```

### Why do I get "Service not found in descriptor"?

**Cause:** Service name doesn't match proto definition.

**Solution:**
```bash
# List services in descriptor
grpcurl -protoset service.desc list

# Verify exact service name (case-sensitive)
grpcurl -protoset service.desc describe com.example.MyService
```

### Why is my queue constantly full?

**Cause:** Kafka producer throughput < gRPC message rate.

**Solutions:**

1. **Increase queue size** (temporary):
   ```properties
   grpc.message.queue.size=50000
   ```

2. **Optimize Kafka producer** (permanent):
   ```properties
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   ```

3. **Scale Kafka infrastructure**:
   - Add more brokers
   - Increase partition count
   - Use SSD storage

### Why do I see sequence gaps in logs?

**Cause:** Messages lost due to queue overflow or network issues.

**Investigation:**
```bash
# Check for gap warnings
grep "event=sequence_gap" $KAFKA_HOME/logs/connect.log

# Check queue utilization
# Monitor QueueUtilizationPercent metric via JMX
```

**Solutions:**
- Increase `grpc.message.queue.size`
- Optimize Kafka producer settings
- Investigate network stability

## Compatibility

### Does this work with Kafka 2.x?

No, minimum Kafka version is 3.9.0. The connector uses APIs introduced in Kafka 3.x.

### Does this work with Java 8?

No, minimum Java version is 11. The connector uses Java 11 language features and gRPC Java 1.60.0 requires Java 11+.

### Does this work with Kubernetes?

Yes, deploy Kafka Connect in Kubernetes and include this connector:

- **Strimzi Kafka Operator** - Build custom Connect image
- **Confluent for Kubernetes** - Use custom Connect image
- **Helm Charts** - Mount plugin directory via ConfigMap/PersistentVolume

### Does this work with Docker?

Yes. Create a custom image:

```dockerfile
FROM confluentinc/cp-kafka-connect:7.5.0

# Copy the uber JAR (includes all dependencies)
COPY kafka-connect-grpc-1.0.0-jar-with-dependencies.jar \
  /usr/share/confluent-hub-components/kafka-connect-grpc/
```

## Still Have Questions?

- **GitHub Issues**: [Open an issue](https://github.com/conduktor/kafka-connect-grpc/issues)
- **Slack Community**: [Join Conduktor Slack](https://conduktor.io/slack)
- **Documentation**: [Browse documentation](https://conduktor.github.io/kafka-connect-grpc/)
