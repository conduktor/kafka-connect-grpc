# Configuration Reference

Complete reference for all Kafka Connect gRPC Source Connector configuration parameters.

## Required Configuration

These parameters must be specified for the connector to start:

### gRPC Server Connection

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `grpc.server.host` | String | gRPC server hostname or IP address | `localhost`, `grpc.example.com` |
| `grpc.server.port` | Integer | gRPC server port | `9090`, `443` |
| `grpc.service.name` | String | Fully qualified service name from proto | `com.example.EventService` |
| `grpc.method.name` | String | Server streaming method name | `StreamEvents` |

### Kafka Configuration

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `kafka.topic` | String | Target Kafka topic for gRPC messages | `grpc-events`, `streaming-data` |

## Optional Configuration

### Request Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grpc.request.message` | String | `{}` | JSON representation of the request message sent to the gRPC method |
| `grpc.proto.descriptor` | String | `null` | Path to .desc descriptor file or base64-encoded descriptor |

**Example:**
```json
{
  "grpc.request.message": "{\"filter\":\"active\",\"limit\":100}",
  "grpc.proto.descriptor": "/path/to/service.desc"
}
```

### TLS/mTLS Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grpc.tls.enabled` | Boolean | `false` | Enable TLS for gRPC connection |
| `grpc.tls.ca.cert` | String | `null` | Path to CA certificate file for server verification |
| `grpc.tls.client.cert` | String | `null` | Path to client certificate for mutual TLS |
| `grpc.tls.client.key` | String | `null` | Path to client private key for mutual TLS |

**Example (TLS only):**
```json
{
  "grpc.tls.enabled": "true",
  "grpc.tls.ca.cert": "/etc/ssl/certs/ca.crt"
}
```

**Example (mTLS):**
```json
{
  "grpc.tls.enabled": "true",
  "grpc.tls.ca.cert": "/etc/ssl/certs/ca.crt",
  "grpc.tls.client.cert": "/etc/ssl/certs/client.crt",
  "grpc.tls.client.key": "/etc/ssl/private/client.key"
}
```

### Metadata/Headers Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grpc.metadata` | String | `null` | Custom metadata in format: `key1:value1,key2:value2` |
| `grpc.metadata.<key>` | String | `null` | Individual metadata entry (alternative to comma-separated format) |

**Example (comma-separated):**
```json
{
  "grpc.metadata": "authorization:Bearer token123,x-api-key:key456"
}
```

**Example (individual entries):**
```json
{
  "grpc.metadata.authorization": "Bearer token123",
  "grpc.metadata.x-api-key": "key456",
  "grpc.metadata.x-request-id": "req-789"
}
```

### Reconnection Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grpc.reconnect.enabled` | Boolean | `true` | Enable automatic reconnection on disconnect |
| `grpc.reconnect.interval.ms` | Long | `5000` | Initial reconnection interval in milliseconds |
| `grpc.reconnect.max.attempts` | Integer | `-1` | Maximum reconnection attempts (-1 for infinite) |
| `grpc.reconnect.backoff.max.ms` | Long | `60000` | Maximum backoff delay for exponential backoff |

**Backoff Calculation:**
```
delay = min(interval * 2^attempt, backoff.max)
```

**Example (aggressive reconnection):**
```json
{
  "grpc.reconnect.enabled": "true",
  "grpc.reconnect.interval.ms": "1000",
  "grpc.reconnect.max.attempts": "-1",
  "grpc.reconnect.backoff.max.ms": "30000"
}
```

**Example (limited retries):**
```json
{
  "grpc.reconnect.enabled": "true",
  "grpc.reconnect.interval.ms": "5000",
  "grpc.reconnect.max.attempts": "10",
  "grpc.reconnect.backoff.max.ms": "120000"
}
```

### Advanced Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grpc.message.queue.size` | Integer | `10000` | Maximum size of the message buffer queue |
| `grpc.connection.timeout.ms` | Long | `30000` | Connection timeout in milliseconds |
| `grpc.keepalive.time.ms` | Long | `30000` | Keepalive time (0 to disable) |
| `grpc.keepalive.timeout.ms` | Long | `10000` | Keepalive timeout |
| `grpc.max.inbound.message.size` | Integer | `4194304` | Maximum inbound message size in bytes (4MB default) |

**Queue Size Recommendations:**

| Use Case | Queue Size |
|----------|-----------|
| Low-volume (< 100 msg/sec) | 1000-5000 |
| Medium-volume (100-1000 msg/sec) | 10000-20000 |
| High-volume (> 1000 msg/sec) | 50000-100000 |

**Keepalive Recommendations:**

| Network Condition | Keepalive Time | Keepalive Timeout |
|-------------------|----------------|-------------------|
| Stable local network | 60000 (60s) | 10000 (10s) |
| Cloud-to-cloud (same region) | 30000 (30s) | 10000 (10s) |
| Cross-region | 20000 (20s) | 5000 (5s) |
| Unstable network | 10000 (10s) | 3000 (3s) |

## Complete Configuration Examples

### Basic Configuration

Minimal configuration for plaintext gRPC:

```json
{
  "name": "grpc-basic-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.EventService",
    "grpc.method.name": "StreamEvents",
    "kafka.topic": "grpc-events"
  }
}
```

### Production Configuration with TLS

Recommended configuration for production with TLS:

```json
{
  "name": "grpc-prod-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",

    "grpc.server.host": "grpc.production.example.com",
    "grpc.server.port": "443",
    "grpc.service.name": "com.example.production.EventService",
    "grpc.method.name": "StreamEvents",

    "grpc.tls.enabled": "true",
    "grpc.tls.ca.cert": "/etc/ssl/certs/production-ca.crt",

    "grpc.request.message": "{\"environment\":\"production\",\"version\":\"v1\"}",
    "grpc.proto.descriptor": "/etc/kafka/descriptors/service.desc",

    "grpc.metadata.authorization": "Bearer ${file:/etc/kafka/secrets.properties:api.token}",
    "grpc.metadata.x-environment": "production",

    "grpc.reconnect.enabled": "true",
    "grpc.reconnect.interval.ms": "5000",
    "grpc.reconnect.max.attempts": "-1",
    "grpc.reconnect.backoff.max.ms": "60000",

    "grpc.message.queue.size": "50000",
    "grpc.connection.timeout.ms": "30000",
    "grpc.keepalive.time.ms": "30000",
    "grpc.keepalive.timeout.ms": "10000",
    "grpc.max.inbound.message.size": "8388608",

    "kafka.topic": "production-grpc-events"
  }
}
```

### High-Security mTLS Configuration

Maximum security with mutual TLS:

```json
{
  "name": "grpc-mtls-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",

    "grpc.server.host": "secure-grpc.example.com",
    "grpc.server.port": "443",
    "grpc.service.name": "com.example.secure.DataService",
    "grpc.method.name": "StreamSecureData",

    "grpc.tls.enabled": "true",
    "grpc.tls.ca.cert": "/etc/ssl/certs/ca.crt",
    "grpc.tls.client.cert": "/etc/ssl/certs/client.crt",
    "grpc.tls.client.key": "${file:/etc/kafka/secrets.properties:client.key.path}",

    "grpc.metadata.authorization": "${file:/etc/kafka/secrets.properties:grpc.auth.token}",
    "grpc.metadata.x-client-id": "kafka-connect-prod-01",

    "grpc.proto.descriptor": "/etc/kafka/descriptors/secure-service.desc",

    "kafka.topic": "secure-data-stream",

    "config.providers": "file",
    "config.providers.file.class": "org.apache.kafka.common.config.provider.FileConfigProvider"
  }
}
```

### High-Throughput Configuration

Optimized for high message volume:

```json
{
  "name": "grpc-highthroughput-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",

    "grpc.server.host": "streaming.example.com",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.HighVolumeService",
    "grpc.method.name": "StreamHighVolume",

    "grpc.message.queue.size": "100000",
    "grpc.max.inbound.message.size": "16777216",

    "grpc.keepalive.time.ms": "15000",
    "grpc.keepalive.timeout.ms": "5000",

    "grpc.reconnect.enabled": "true",
    "grpc.reconnect.interval.ms": "1000",
    "grpc.reconnect.backoff.max.ms": "10000",

    "kafka.topic": "high-volume-stream"
  }
}
```

### Development/Testing Configuration

Minimal setup for local development:

```json
{
  "name": "grpc-dev-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "tasks.max": "1",

    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.TestService",
    "grpc.method.name": "StreamTest",

    "grpc.message.queue.size": "1000",
    "grpc.reconnect.interval.ms": "1000",
    "grpc.reconnect.max.attempts": "5",

    "kafka.topic": "test-grpc-stream"
  }
}
```

## Configuration Validation

The connector validates configuration on startup:

### Common Validation Errors

| Error | Cause | Solution |
|-------|-------|----------|
| "Missing required configuration" | Required parameter not set | Add the missing parameter |
| "Invalid port number" | Port outside 1-65535 | Use valid port range |
| "Cannot read TLS certificate" | Certificate file not accessible | Check file path and permissions |
| "Invalid proto descriptor" | Descriptor file corrupted or invalid | Regenerate with `protoc --descriptor_set_out` |
| "Service not found in descriptor" | Service name doesn't match | Verify service name with `grpcurl -protoset` |

### Validation Checklist

Before deploying:

- [ ] All required parameters are set
- [ ] gRPC server is accessible on specified host:port
- [ ] Service and method names match proto definition
- [ ] TLS certificates are valid and accessible
- [ ] Proto descriptor contains the service definition
- [ ] Kafka topic exists or auto-creation is enabled
- [ ] Request message JSON is valid (if specified)

## Dynamic Configuration Updates

Some parameters can be updated without restarting:

### Updatable Parameters

- `grpc.message.queue.size` (takes effect on reconnection)
- `grpc.reconnect.interval.ms`
- `grpc.reconnect.max.attempts`
- `kafka.topic` (new messages only)

### Non-Updatable Parameters (require restart)

- `grpc.server.host`
- `grpc.server.port`
- `grpc.service.name`
- `grpc.method.name`
- `grpc.tls.*` (all TLS settings)
- `grpc.proto.descriptor`

**Update configuration:**
```bash
curl -X PUT http://localhost:8083/connectors/grpc-connector/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json
```

## Best Practices

### Security

1. **Always use TLS in production**
   ```json
   "grpc.tls.enabled": "true"
   ```

2. **Store secrets securely**
   ```json
   "config.providers": "file",
   "grpc.tls.client.key": "${file:/etc/kafka/secrets.properties:key.path}"
   ```

3. **Use mTLS for sensitive data**
   ```json
   "grpc.tls.client.cert": "/path/to/client.crt",
   "grpc.tls.client.key": "/path/to/client.key"
   ```

### Performance

1. **Size queue appropriately**
   - Monitor `QueueUtilizationPercent` metric
   - Increase if consistently > 80%

2. **Tune keepalive for network conditions**
   - Shorter intervals for unstable networks
   - Longer intervals for stable connections

3. **Adjust message size limits**
   - Set `grpc.max.inbound.message.size` based on actual message size
   - Don't set unnecessarily high (wastes memory)

### Reliability

1. **Enable reconnection**
   ```json
   "grpc.reconnect.enabled": "true",
   "grpc.reconnect.max.attempts": "-1"
   ```

2. **Use exponential backoff**
   ```json
   "grpc.reconnect.interval.ms": "5000",
   "grpc.reconnect.backoff.max.ms": "60000"
   ```

3. **Monitor offset tracking**
   - Check logs for sequence gap warnings
   - Investigate repeated gaps

## Environment-Specific Configuration

### Development
- Small queue size (1000)
- Short reconnection interval (1000ms)
- Limited retry attempts (5-10)
- Plaintext connection acceptable

### Staging
- Medium queue size (10000)
- Normal reconnection interval (5000ms)
- TLS enabled
- Production-like settings

### Production
- Large queue size (50000+)
- Exponential backoff (5000-60000ms)
- Infinite retries (-1)
- TLS/mTLS required
- Keepalive tuned for network
- Secrets externalized

---

**Need help?** Check our [FAQ](../faq.md) or [open an issue](https://github.com/conduktor/kafka-connect-grpc/issues).
