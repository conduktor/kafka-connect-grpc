# gRPC Source Connector - Operational Runbook

This runbook provides operational guidance for monitoring, troubleshooting, and maintaining the gRPC Source Connector in production.

## Table of Contents

- [Incident Response Decision Tree](#incident-response-decision-tree)
- [Monitoring](#monitoring)
- [Common Issues](#common-issues)
- [Performance Tuning](#performance-tuning)
- [Troubleshooting](#troubleshooting)
- [Recovery Procedures](#recovery-procedures)

## Incident Response Decision Tree

Use this decision tree when an alert fires or an issue is reported:

```
START: Alert or Issue Reported
│
├─ Is connector in RUNNING state?
│  │  curl http://localhost:8083/connectors/<name>/status | jq '.connector.state'
│  │
│  └─ NO → Go to [Connector Not Running](#issue-connector-not-running)
│  └─ YES → Continue ↓
│
├─ Is gRPC connected? (IsConnected = true)
│  │  Check JMX: io.conduktor.connect.grpc:*/IsConnected
│  │
│  └─ NO → Go to [Connection Issues](#issue-1-connection-keeps-dropping)
│  └─ YES → Continue ↓
│
├─ Are messages being received? (MessagesReceived increasing)
│  │  Check JMX: io.conduktor.connect.grpc:*/MessagesReceived
│  │
│  └─ NO → Go to [No Messages Received](#issue-3-no-messages-received)
│  └─ YES → Continue ↓
│
├─ Is queue near capacity? (QueueUtilizationPercent > 80%)
│  │  Check JMX: io.conduktor.connect.grpc:*/QueueUtilizationPercent
│  │
│  └─ YES → Go to [High Message Drop Rate](#issue-2-high-message-drop-rate)
│  └─ NO → Continue ↓
│
├─ Is Kafka write lagging? (RecordsProduced << MessagesReceived)
│  │  Check lag: MessagesReceived - RecordsProduced > 10000
│  │
│  └─ YES → Go to [Processing Lag](#issue-4-processing-lag-building-up)
│  └─ NO → Continue ↓
│
├─ Are sequence gaps detected?
│  │  grep "event=sequence_gap" $KAFKA_HOME/logs/connect.log
│  │
│  └─ YES → Go to [Sequence Gaps](#issue-5-sequence-gaps-detected)
│  └─ NO → Continue ↓
│
└─ Check logs for errors
   │  grep "ERROR\|WARN" $KAFKA_HOME/logs/connect.log | tail -50
   │
   └─ Errors found → Match error to [Common Issues](#common-issues)
   └─ No errors → Monitor, escalate if issue persists
```

### Quick Commands Reference

```bash
# Check connector status
curl -s http://localhost:8083/connectors/<name>/status | jq .

# Restart connector
curl -X POST http://localhost:8083/connectors/<name>/restart

# Restart specific task
curl -X POST http://localhost:8083/connectors/<name>/tasks/0/restart

# Check recent logs
tail -100 $KAFKA_HOME/logs/connect.log | grep -E "grpc|ERROR|WARN"

# Check JMX metrics (requires jmxterm)
echo "get -b io.conduktor.connect.grpc:* IsConnected" | \
  java -jar jmxterm.jar -l localhost:9999
```

### Issue: Connector Not Running

**Symptoms**: Connector state is FAILED or task state is FAILED

**Quick Fix**:
```bash
# Check the error message
curl -s http://localhost:8083/connectors/<name>/status | jq '.tasks[0].trace'

# Restart connector
curl -X POST http://localhost:8083/connectors/<name>/restart

# If still failing, check config and redeploy
curl -X DELETE http://localhost:8083/connectors/<name>
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @connector.json
```

**Common Causes**:
- Invalid gRPC server host/port
- TLS certificate issues
- Proto descriptor file not found or invalid
- Service/method name mismatch
- Missing dependencies (ClassNotFoundException)

## Monitoring

### Key JMX Metrics

The connector exposes JMX metrics under:
```
io.conduktor.connect.grpc:type=GrpcConnector,name=<connector-name>,server=<server>
```

#### Counter Metrics
- **MessagesReceived**: Total messages received from gRPC
  - **Alert**: Rate drops to 0 for > 5 minutes → Connection or server issue
  - **Action**: Check `IsConnected` metric, review logs for gRPC errors

- **MessagesDropped**: Messages dropped due to queue overflow
  - **Alert**: Drop rate > 1% → Queue capacity insufficient
  - **Action**: Increase `grpc.message.queue.size` or optimize Kafka throughput

- **RecordsProduced**: Total records written to Kafka
  - **Alert**: Lag (MessagesReceived - RecordsProduced) > 10000 → Processing backlog
  - **Action**: Check Kafka broker health, review producer performance

#### Queue Metrics
- **QueueSize**: Current number of messages in queue
- **QueueCapacity**: Maximum queue capacity
- **QueueUtilizationPercent**: (QueueSize / QueueCapacity) * 100
  - **Alert**: Utilization > 80% → Approaching capacity
  - **Action**: Monitor for drops, consider increasing queue size

#### Connection Metrics
- **IsConnected**: Boolean indicating gRPC connection status
  - **Alert**: false for > 2 minutes → Connection failure
  - **Action**: Check gRPC server availability, verify TLS configuration

- **MillisSinceLastMessage**: Time since last message received
  - **Alert**: > 300000ms (5 minutes) when messages expected → Stale stream
  - **Action**: Check server health, verify stream is active

- **UptimeMillis**: Connection uptime in milliseconds
  - **Info**: Use to track connection stability

- **TotalReconnects**: Total reconnection attempts
  - **Alert**: Rapid increase (>10 in 5 minutes) → Unstable connection
  - **Action**: Review server stability, check network/firewall

#### Derived Metrics
- **LagCount**: MessagesReceived - RecordsProduced
  - **Alert**: > 10000 → Processing backlog
  - **Action**: Review Kafka producer performance

- **DropRate**: (MessagesDropped / MessagesReceived) * 100
  - **Alert**: > 1% → Significant message loss
  - **Action**: Increase queue size or optimize throughput

### Recommended Alerts

```yaml
# Example Prometheus alerting rules
groups:
  - name: grpc_connector
    rules:
      - alert: GrpcDisconnected
        expr: grpc_isConnected == 0
        for: 2m
        annotations:
          summary: "gRPC connection lost for {{ $labels.connector_name }}"
          description: "Connection to {{ $labels.server }} has been down for 2 minutes"

      - alert: HighMessageDropRate
        expr: grpc_DropRate > 1
        for: 5m
        annotations:
          summary: "High message drop rate for {{ $labels.connector_name }}"
          description: "Dropping {{ $value }}% of messages - queue capacity insufficient"

      - alert: HighProcessingLag
        expr: grpc_LagCount > 10000
        for: 5m
        annotations:
          summary: "High processing lag for {{ $labels.connector_name }}"
          description: "Lag count: {{ $value }} - Kafka throughput issue"

      - alert: FrequentReconnections
        expr: increase(grpc_TotalReconnects[5m]) > 10
        annotations:
          summary: "Frequent reconnections for {{ $labels.connector_name }}"
          description: "{{ $value }} reconnections in 5 minutes - unstable connection"

      - alert: SequenceGapsDetected
        expr: rate(grpc_sequence_gaps[5m]) > 0
        annotations:
          summary: "Sequence gaps detected for {{ $labels.connector_name }}"
          description: "Data loss detected - investigate queue overflow or network issues"
```

## Common Issues

### Issue 1: Connection Keeps Dropping

**Symptoms:**
- `IsConnected` metric flipping between true/false
- `TotalReconnects` incrementing rapidly
- Logs showing "event=connection_closed" or "event=connection_failed"

**Root Causes:**
1. Network instability
2. gRPC server restarts or issues
3. Keepalive timeout mismatch
4. TLS certificate expiration
5. Firewall/proxy timeout

**Resolution:**
```bash
# Check connector status
curl -s http://localhost:8083/connectors/grpc-connector/status | jq .

# Review recent logs
kubectl logs -l app=kafka-connect --tail=100 | grep "event=connection"

# Test gRPC endpoint
grpcurl -plaintext <host>:<port> list

# Verify TLS (if enabled)
openssl s_client -connect <host>:<port>

# Actions:
1. Increase grpc.connection.timeout.ms (default: 30000)
2. Adjust keepalive settings:
   - grpc.keepalive.time.ms (default: 30000)
   - grpc.keepalive.timeout.ms (default: 10000)
3. Verify TLS certificates are valid
4. Check gRPC server logs for errors
5. Review network path for issues
```

### Issue 2: High Message Drop Rate

**Symptoms:**
- `DropRate` metric > 1%
- `MessagesDropped` incrementing
- `QueueUtilizationPercent` at 100%
- Logs showing "event=queue_full"

**Root Causes:**
1. Queue capacity too small for message rate
2. Kafka broker throughput bottleneck
3. Kafka producer configuration suboptimal
4. Consumer lag in downstream consumers

**Resolution:**
```bash
# Check queue metrics via JMX
jconsole # Connect and view queue metrics

# Actions:
1. Increase queue size:
   grpc.message.queue.size=50000 (default: 10000)

2. Optimize Kafka producer (in connect-distributed.properties):
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   producer.acks=1

3. Check Kafka broker health:
   kafka-broker-api-versions --bootstrap-server localhost:9092

4. Monitor topic metrics:
   kafka-topics --bootstrap-server localhost:9092 --describe --topic <topic>

5. Increase topic partitions if needed:
   kafka-topics --alter --topic <topic> --partitions 10
```

### Issue 3: No Messages Received

**Symptoms:**
- `MessagesReceived` not incrementing
- `IsConnected` = true
- No errors in logs

**Root Causes:**
1. gRPC server not sending messages
2. Stream method not implemented correctly
3. Request message filtering out all data
4. Wrong service/method name

**Resolution:**
```bash
# Test gRPC service manually
grpcurl -plaintext <host>:<port> <service>/<method>

# With proto descriptor
grpcurl -protoset service.desc <host>:<port> <service>/<method>

# With request message
grpcurl -d '{"filter":"test"}' -protoset service.desc <host>:<port> <service>/<method>

# Actions:
1. Verify gRPC server is actively streaming
2. Check server logs for issues
3. Verify service and method names are correct
4. Test request message format
5. Check MillisSinceLastMessage metric
6. Review server-side logs for connection info
```

### Issue 4: Processing Lag Building Up

**Symptoms:**
- `LagCount` increasing
- `QueueSize` growing
- `RecordsProduced` not keeping pace with `MessagesReceived`

**Root Causes:**
1. Kafka broker slowness
2. Network issues to Kafka
3. Insufficient topic partitions
4. Producer throughput limitation

**Resolution:**
```bash
# Check Kafka broker metrics
kafka-run-class kafka.tools.JmxTool \
  --object-name kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec \
  --jmx-url service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi

# Check topic configuration
kafka-topics --bootstrap-server localhost:9092 --describe --topic <topic>

# Actions:
1. Increase topic partitions for parallelism
2. Check Kafka broker disk I/O and network
3. Monitor Kafka producer metrics
4. Review connect-distributed.properties producer settings
5. Consider adding more Kafka brokers
```

### Issue 5: Sequence Gaps Detected

**Symptoms:**
- Logs showing "event=sequence_gap"
- Warnings about missing sequence numbers
- Potential data loss

**Root Causes:**
1. Queue overflow (messages dropped)
2. Network packet loss
3. gRPC server skipping sequences
4. Connector restart with offset mismatch

**Resolution:**
```bash
# Check for gap warnings
grep "event=sequence_gap" $KAFKA_HOME/logs/connect.log | tail -20

# Check queue utilization
# Monitor QueueUtilizationPercent metric

# Actions:
1. Increase grpc.message.queue.size if queue was full
2. Review network stability between connector and gRPC server
3. Check gRPC server logs for abnormalities
4. Verify offset management is working correctly
5. If gaps persist, investigate gRPC server implementation
```

## Performance Tuning

### Queue Sizing

**Default:** 10000 messages

**Recommendations:**
- Low-volume, low-latency: 1000-5000
- Medium-volume: 10000-20000 (default)
- High-volume (>1000 msg/sec): 50000-100000

**Trade-offs:**
- Larger queue = more memory usage, better burst handling
- Smaller queue = less memory, more drops under bursts

### Reconnection Settings

**Production Recommendations:**
```properties
grpc.reconnect.enabled=true
grpc.reconnect.interval.ms=5000
grpc.reconnect.max.attempts=-1  # infinite retries
grpc.reconnect.backoff.max.ms=60000
```

**Dev/Test Recommendations:**
```properties
grpc.reconnect.enabled=true
grpc.reconnect.interval.ms=1000
grpc.reconnect.max.attempts=10
grpc.reconnect.backoff.max.ms=10000
```

### Keepalive Settings

**Adjust based on network conditions:**

| Network Type | Keepalive Time | Keepalive Timeout |
|--------------|----------------|-------------------|
| Local network | 60000ms | 10000ms |
| Cloud (same region) | 30000ms | 10000ms |
| Cross-region | 20000ms | 5000ms |
| Unstable network | 10000ms | 3000ms |

### Message Size Limits

**Default:** 4MB (4194304 bytes)

**Adjust based on actual message size:**
```properties
# For larger messages
grpc.max.inbound.message.size=16777216  # 16MB

# For smaller messages (save memory)
grpc.max.inbound.message.size=1048576  # 1MB
```

## Troubleshooting

### Debug Logging

Enable debug logging for detailed troubleshooting:

```properties
# Connector logging
log4j.logger.io.conduktor.connect.grpc=DEBUG

# gRPC Java logging
log4j.logger.io.grpc=DEBUG

# Netty (gRPC transport)
log4j.logger.io.netty=DEBUG
```

### Common Log Messages

| Log Event | Severity | Meaning | Action |
|-----------|----------|---------|--------|
| "event=streaming_started" | INFO | gRPC stream active | Normal operation |
| "event=connection_closed" | INFO | Connection closed | Reconnection will occur if enabled |
| "event=connection_failed" | ERROR | Connection attempt failed | Check server availability, TLS config |
| "event=queue_full" | WARN | Queue overflow | Increase queue size |
| "event=sequence_gap" | WARN | Missing sequence number | Investigate data loss |
| "event=session_initialized" | INFO | New session started | Normal after reconnection |

### Health Check Script

```bash
#!/bin/bash
# health-check.sh

CONNECTOR_NAME="grpc-connector"
CONNECT_URL="http://localhost:8083"

# Check connector status
STATUS=$(curl -s $CONNECT_URL/connectors/$CONNECTOR_NAME/status | jq -r '.connector.state')

if [ "$STATUS" != "RUNNING" ]; then
  echo "CRITICAL: Connector not running (state: $STATUS)"
  exit 2
fi

# Check task status
TASK_STATUS=$(curl -s $CONNECT_URL/connectors/$CONNECTOR_NAME/status | jq -r '.tasks[0].state')

if [ "$TASK_STATUS" != "RUNNING" ]; then
  echo "CRITICAL: Task not running (state: $TASK_STATUS)"
  exit 2
fi

# Check JMX metrics (requires jmxterm)
CONNECTED=$(echo "get -b io.conduktor.connect.grpc:type=GrpcConnector,name=$CONNECTOR_NAME,server=* IsConnected" | \
  java -jar jmxterm.jar -l localhost:9999 -n)

if [ "$CONNECTED" != "true" ]; then
  echo "WARNING: gRPC not connected"
  exit 1
fi

echo "OK: Connector healthy"
exit 0
```

### gRPC Connection Testing

```bash
# Test plaintext connection
grpcurl -plaintext <host>:<port> list
grpcurl -plaintext <host>:<port> <service>/<method>

# Test TLS connection
grpcurl -cacert ca.crt <host>:<port> list

# Test mTLS connection
grpcurl -cacert ca.crt \
  -cert client.crt \
  -key client.key \
  <host>:<port> list

# With proto descriptor
grpcurl -protoset service.desc <host>:<port> list
grpcurl -protoset service.desc <host>:<port> describe <service>
```

## Recovery Procedures

### Restart Connector

```bash
# Pause connector
curl -X PUT http://localhost:8083/connectors/grpc-connector/pause

# Wait for current messages to flush
sleep 10

# Resume connector
curl -X PUT http://localhost:8083/connectors/grpc-connector/resume

# Verify status
curl -s http://localhost:8083/connectors/grpc-connector/status | jq .
```

### Reset Offsets

**WARNING:** This will restart streaming from current position.

```bash
# Delete connector
curl -X DELETE http://localhost:8083/connectors/grpc-connector

# Wait for cleanup
sleep 5

# Recreate connector with fresh configuration
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @connector-config.json
```

### Emergency Shutdown

If connector is misbehaving and needs immediate shutdown:

```bash
# Option 1: Pause connector (graceful)
curl -X PUT http://localhost:8083/connectors/grpc-connector/pause

# Option 2: Delete connector (removes from cluster)
curl -X DELETE http://localhost:8083/connectors/grpc-connector

# Option 3: Restart Connect worker (nuclear option)
kubectl delete pod -l app=kafka-connect
```

### Certificate Renewal

For TLS/mTLS certificate expiration:

```bash
# 1. Update certificate files
cp new-ca.crt /etc/ssl/certs/ca.crt
cp new-client.crt /etc/ssl/certs/client.crt
cp new-client.key /etc/ssl/private/client.key

# 2. Update connector configuration
curl -X PUT http://localhost:8083/connectors/grpc-connector/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json

# 3. Connector will restart automatically with new certificates
```

## Contact and Escalation

For issues not covered in this runbook:

1. Review connector logs at DEBUG level
2. Check Kafka Connect worker logs
3. Verify gRPC server health and logs
4. Test gRPC connection with grpcurl
5. Consult project documentation: https://github.com/conduktor/kafka-connect-grpc
6. Open issue with:
   - Connector configuration (sanitized)
   - JMX metrics snapshot
   - Relevant logs (last 1000 lines)
   - gRPC server details
   - Kafka Connect worker version
   - Kafka broker version
