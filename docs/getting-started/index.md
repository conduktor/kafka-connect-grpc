# Getting Started

This guide will help you install, configure, and deploy your first Kafka Connect gRPC connector.

## Overview

The Kafka Connect gRPC Source Connector enables you to stream real-time data from any gRPC server streaming endpoint directly into Apache Kafka topics. It handles connection management, reconnection logic, TLS/mTLS, and integrates seamlessly with Kafka Connect's distributed architecture.

## What You'll Learn

In this section, you'll learn how to:

1. **[Prerequisites](prerequisites.md)** - Verify your environment has the required dependencies
2. **Installation** - Install the connector in your Kafka Connect cluster (see main README)
3. **[Configuration](configuration.md)** - Configure all connector parameters
4. **Quick Start** - Deploy your first connector and verify it's working (see main README)

## Deployment Options

Choose the deployment method that best fits your environment:

### Distributed Mode (Production)

**Best for:**
- Production deployments
- High availability requirements
- Multiple connectors
- Horizontal scaling

**Configuration:**
```properties
# config/connect-distributed.properties
plugin.path=/usr/local/share/kafka/plugins
```

### Standalone Mode (Development)

**Best for:**
- Local development
- Testing
- Single connector instances
- Quick prototyping

**Configuration:**
```properties
# config/connect-standalone.properties
plugin.path=/usr/local/share/kafka/plugins
```

## System Requirements

### Minimum Requirements

- **Java**: 11 or higher
- **Kafka**: 3.9.0 or higher
- **Memory**: 512 MB RAM for connector
- **Network**: Access to gRPC server endpoint
- **Proto Descriptor**: .desc file for your gRPC service (optional but recommended)

### Recommended for Production

- **Java**: 17 (LTS)
- **Kafka**: Latest stable version
- **Memory**: 2 GB RAM for Kafka Connect worker
- **CPU**: 2+ cores
- **Network**: Low-latency connection to gRPC server

## Support Matrix

| Component | Minimum Version | Recommended Version | Tested Versions |
|-----------|----------------|---------------------|-----------------|
| Java | 11 | 17 | 11, 17, 21 |
| Kafka | 3.9.0 | 3.9.0+ | 3.9.0 |
| Maven | 3.6+ | 3.9+ | 3.6, 3.8, 3.9 |
| gRPC Java | 1.60.0 | 1.60.0 | 1.60.0 |
| Protobuf | 3.25.0 | 3.25.0 | 3.25.0 |

## Quick Links

### :material-book-open: [Main README](https://github.com/conduktor/kafka-connect-grpc/blob/main/README.md)
Complete documentation with installation, configuration, and usage examples.

### :material-cog: [Configuration Reference](configuration.md)
Detailed reference for all connector configuration parameters.

### :material-help-circle: [FAQ](../faq.md)
Frequently asked questions and troubleshooting tips.

### :material-history: [Changelog](../changelog.md)
Version history, new features, and bug fixes.

## Before You Begin

!!! tip "Check Your Environment"
    Before proceeding, ensure you have:

    - [ ] Java 11+ installed (`java -version`)
    - [ ] Kafka 3.9.0+ running
    - [ ] Access to build the connector (Maven 3.6+)
    - [ ] Network access to your gRPC server
    - [ ] Protocol Buffer descriptor file (.desc) for your service

!!! warning "gRPC Server Requirements"
    Your gRPC server must implement a **server streaming** method. This connector does not support:

    - Unary RPCs (request-response)
    - Client streaming
    - Bidirectional streaming

    Only server streaming methods are compatible with this connector.

## Generating Proto Descriptors

To use dynamic message handling, generate a Protocol Buffer descriptor file:

```bash
# Generate .desc file with all dependencies
protoc --descriptor_set_out=service.desc \
  --include_imports \
  your_service.proto

# Verify the descriptor was created
file service.desc
# Output: service.desc: data
```

The descriptor file contains all the schema information needed for the connector to serialize and deserialize messages without code generation.

## Next Steps

Ready to install? Start with:

1. [Prerequisites](prerequisites.md) - Verify your environment
2. [Main README](https://github.com/conduktor/kafka-connect-grpc/blob/main/README.md#installation) - Follow the installation guide
3. [Configuration](configuration.md) - Learn about all configuration options
4. [Examples](https://github.com/conduktor/kafka-connect-grpc/blob/main/README.md#usage-examples) - Deploy your first connector

---

Need help? Check our [FAQ](../faq.md) or [open an issue](https://github.com/conduktor/kafka-connect-grpc/issues).
