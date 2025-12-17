# Changelog

All notable changes to the Kafka Connect gRPC Source Connector will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned Features

- [ ] Support for custom message key extraction
- [ ] Dead letter queue support for failed messages
- [ ] Enhanced compression support for Kafka producer
- [ ] Metrics dashboard templates (Grafana)
- [ ] Helm chart for Kubernetes deployments
- [ ] Support for gRPC reflection (no descriptor file needed)

## [1.0.0] - 2025-12-17

### Added

#### Core Features
- Initial release of Kafka Connect gRPC Source Connector
- Support for gRPC server streaming RPCs
- Dynamic message handling via Protocol Buffer descriptors
- Automatic reconnection with exponential backoff
- Sequence-based offset tracking for reliable delivery
- Comprehensive logging with structured events
- JMX metrics for production monitoring

#### gRPC Features
- TLS support for secure connections
- Mutual TLS (mTLS) for client authentication
- Custom metadata/headers support
- Configurable keepalive settings
- Adjustable message size limits (default 4MB)
- Request message support (JSON to Protobuf conversion)

#### Configuration
- Required parameters:
  - `grpc.server.host` - gRPC server hostname
  - `grpc.server.port` - gRPC server port
  - `grpc.service.name` - Fully qualified service name
  - `grpc.method.name` - Server streaming method name
  - `kafka.topic` - Target Kafka topic
- Optional parameters:
  - `grpc.request.message` - JSON request message
  - `grpc.proto.descriptor` - Path to .desc file or base64-encoded descriptor
  - `grpc.tls.enabled` - Enable TLS
  - `grpc.tls.ca.cert` - CA certificate path
  - `grpc.tls.client.cert` - Client certificate for mTLS
  - `grpc.tls.client.key` - Client private key for mTLS
  - `grpc.metadata` - Custom metadata/headers
  - `grpc.reconnect.enabled` - Enable auto-reconnection (default: true)
  - `grpc.reconnect.interval.ms` - Reconnection interval (default: 5000ms)
  - `grpc.reconnect.max.attempts` - Max retry attempts (default: -1, infinite)
  - `grpc.reconnect.backoff.max.ms` - Max backoff delay (default: 60000ms)
  - `grpc.message.queue.size` - Queue size (default: 10000)
  - `grpc.connection.timeout.ms` - Connection timeout (default: 30000ms)
  - `grpc.keepalive.time.ms` - Keepalive interval (default: 30000ms)
  - `grpc.keepalive.timeout.ms` - Keepalive timeout (default: 10000ms)
  - `grpc.max.inbound.message.size` - Max message size (default: 4MB)

#### Monitoring & Operations
- JMX metrics exposure via Kafka Connect framework:
  - `MessagesReceived` - Total messages from gRPC
  - `MessagesDropped` - Messages dropped due to queue overflow
  - `RecordsProduced` - Total records written to Kafka
  - `QueueSize` - Current queue size
  - `QueueUtilizationPercent` - Queue utilization percentage
  - `IsConnected` - Connection status
  - `MillisSinceLastMessage` - Time since last message
  - `UptimeMillis` - Connection uptime
  - `TotalReconnects` - Total reconnection attempts
  - `LagCount` - Messages received but not produced
  - `DropRate` - Message drop rate percentage
- Structured logging with MDC context:
  - `event=task_starting` - Task initialization
  - `event=session_initialized` - New session started
  - `event=streaming_started` - gRPC stream active
  - `event=message_received` - Message received
  - `event=sequence_gap` - Gap in sequence detected
  - `event=task_metrics` - Periodic metrics log
- Integration with Prometheus via JMX Exporter

#### Documentation
- Comprehensive README with installation and usage
- Quick start guide with multiple examples:
  - Basic plaintext gRPC connection
  - TLS-secured connection
  - Mutual TLS (mTLS) configuration
  - Custom metadata/headers
  - Request message with proto descriptor
- Detailed troubleshooting section
- Architecture documentation with diagrams
- Production deployment recommendations
- MkDocs-based documentation website

#### Testing
- Unit tests for connector and configuration
- Integration tests with mock gRPC server
- Configuration validation tests
- Test coverage for all major components

#### Dependencies
- Apache Kafka Connect API 3.9.0
- gRPC Java 1.60.0
- Protocol Buffers 3.25.0
- SLF4J logging API 1.7.36
- JUnit 5.9.2 (testing)
- Mockito 5.2.0 (testing)

### Technical Details

#### Architecture
- **GrpcSourceConnector**: Main connector class managing configuration and task lifecycle
- **GrpcSourceTask**: Task implementation handling gRPC streaming and message polling
- **GrpcClient**: Manages gRPC channel, StreamObserver, and reconnection logic
- **GrpcSourceConnectorConfig**: Configuration definition with validation
- **GrpcMetrics**: JMX metrics bean for monitoring

#### Data Flow
```
gRPC Server (StreamObserver) → LinkedBlockingDeque Queue →
SourceTask.poll() → SourceRecord → Kafka Topic
```

#### Offset Management
- Session-based tracking with unique session ID per connection
- Sequence numbers for each message
- Gap detection for identifying lost messages
- Offset format: `{"sessionId":"uuid","sequence":123}`

#### Limitations Documented
- Single task per connector (gRPC streaming constraint)
- Server streaming only (no unary, client streaming, or bidirectional)
- Cannot replay from gRPC server (streaming limitation)
- At-least-once semantics (with gap detection)
- In-memory queue data lost on shutdown

### Known Issues

- None reported in initial release

### Breaking Changes

N/A - Initial release

---

## Version History Format

### [X.Y.Z] - YYYY-MM-DD

#### Added
Features or capabilities that were added in this release.

#### Changed
Changes in existing functionality or behavior.

#### Deprecated
Features that will be removed in future releases.

#### Removed
Features that were removed in this release.

#### Fixed
Bug fixes and error corrections.

#### Security
Security vulnerability fixes and improvements.

---

## Upgrade Guide

### From Pre-Release to 1.0.0

This is the initial stable release. No migration required.

### Future Upgrades

Upgrade instructions will be provided here for future releases.

---

## Support Policy

### Version Support

- **Latest stable version**: Full support with bug fixes and security updates
- **Previous minor version**: Security fixes only
- **Older versions**: Community support via GitHub Issues

### Compatibility Matrix

| Connector Version | Min Kafka Version | Max Kafka Version | Java Version | gRPC Java Version |
|-------------------|-------------------|-------------------|--------------|-------------------|
| 1.0.0 | 3.9.0 | Latest | 11+ | 1.60.0 |

---

## Release Notes Archive

### Release Highlights

#### 1.0.0 - Initial Stable Release (2025-12-17)

**Production-Ready gRPC Streaming to Kafka**

The Kafka Connect gRPC Source Connector is now production-ready with comprehensive features for streaming real-time data from gRPC server streaming endpoints into Apache Kafka.

**Key Capabilities:**
- Stream from any gRPC server streaming RPC
- Dynamic message handling via Protocol Buffer descriptors
- Full TLS and mutual TLS support
- Automatic reconnection with exponential backoff
- Sequence-based offset tracking

**Production Features:**
- Comprehensive JMX metrics
- Structured logging with MDC context
- Built-in monitoring and alerting support
- Prometheus/Grafana integration

**Getting Started:**
```bash
mvn clean package
cp target/kafka-connect-grpc-1.0.0-jar-with-dependencies.jar \
  $KAFKA_HOME/plugins/kafka-connect-grpc/
```

**Example Configuration:**
```json
{
  "name": "grpc-streaming-connector",
  "config": {
    "connector.class": "io.conduktor.connect.grpc.GrpcSourceConnector",
    "grpc.server.host": "localhost",
    "grpc.server.port": "9090",
    "grpc.service.name": "com.example.EventService",
    "grpc.method.name": "StreamEvents",
    "kafka.topic": "grpc-events"
  }
}
```

**Important Notes:**
- Server streaming only (not unary or bidirectional)
- Sequence-based offset tracking with gap detection
- At-least-once semantics
- In-memory queue data lost on shutdown

---

## Contributing to Changelog

When submitting a PR, please update the `[Unreleased]` section with your changes under the appropriate category (Added, Changed, Fixed, etc.).

**Format:**
```markdown
### Category
- Brief description of change ([#PR_NUMBER](link))
```

**Example:**
```markdown
### Added
- Support for gRPC reflection API ([#42](https://github.com/conduktor/kafka-connect-grpc/pull/42))

### Fixed
- Memory leak in queue overflow scenario ([#38](https://github.com/conduktor/kafka-connect-grpc/pull/38))
```

---

## Links

- [GitHub Repository](https://github.com/conduktor/kafka-connect-grpc)
- [Issue Tracker](https://github.com/conduktor/kafka-connect-grpc/issues)
- [Documentation](https://conduktor.github.io/kafka-connect-grpc/)
- [Conduktor Website](https://conduktor.io)

[Unreleased]: https://github.com/conduktor/kafka-connect-grpc/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/conduktor/kafka-connect-grpc/releases/tag/v1.0.0
