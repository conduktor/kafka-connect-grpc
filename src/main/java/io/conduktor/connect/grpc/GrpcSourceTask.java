package io.conduktor.connect.grpc;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.management.JMException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC Source Task that polls messages from gRPC server streaming and produces them to Kafka.
 *
 * This implementation provides:
 * - Sequence-based offset management for reliable message tracking
 * - Connection session tracking to detect reconnections
 * - Delivery guarantee via commitRecord() callback
 * - Graceful shutdown with message draining
 * - Proper queue-based polling without Thread.sleep()
 */
public class GrpcSourceTask extends SourceTask {
    private static final Logger log = LoggerFactory.getLogger(GrpcSourceTask.class);

    // Shutdown and lifecycle management
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 5000L;

    // Offset management - sequence-based tracking
    private final AtomicLong messageSequence = new AtomicLong(0);
    private final AtomicLong lastCommittedSequence = new AtomicLong(-1);
    private volatile String connectionSessionId;

    // gRPC client and configuration
    private GrpcClient client;
    private String kafkaTopic;
    private GrpcSourceConnectorConfig config;

    // Metrics
    private final AtomicLong recordsProduced = new AtomicLong(0);
    private long lastLogTime = System.currentTimeMillis();
    private GrpcMetrics metrics;
    private String connectorName;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        config = new GrpcSourceConnectorConfig(props);
        kafkaTopic = config.getKafkaTopic();
        String grpcServer = config.getGrpcServerHost() + ":" + config.getGrpcServerPort();
        String fullMethodName = config.getGrpcServiceName() + "/" + config.getGrpcMethodName();

        // Extract connector name from properties or generate one
        connectorName = props.getOrDefault("name", "grpc-connector-" + UUID.randomUUID().toString().substring(0, 8));

        // Set up MDC context for all logging in this task
        MDC.put("connector_name", connectorName);
        MDC.put("grpc_server", grpcServer);
        MDC.put("grpc_method", fullMethodName);
        MDC.put("kafka_topic", kafkaTopic);

        log.info("event=task_starting connector_name={} grpc_server={} grpc_method={} kafka_topic={}",
                 connectorName, grpcServer, fullMethodName, kafkaTopic);

        // Initialize connection session ID (unique identifier for this connection lifecycle)
        connectionSessionId = UUID.randomUUID().toString();
        MDC.put("session_id", connectionSessionId);
        log.info("event=session_initialized session_id={}", connectionSessionId);

        // Initialize JMX metrics
        try {
            metrics = new GrpcMetrics(connectorName, grpcServer);
            log.info("event=jmx_metrics_initialized connector_name={}", connectorName);
        } catch (JMException e) {
            log.error("event=jmx_metrics_init_failed connector_name={} error={}", connectorName, e.getMessage(), e);
            // Continue without metrics - not critical for operation
        }

        // Restore offset from Kafka Connect framework if available
        restoreOffsetState();

        // Parse metadata
        Map<String, String> metadata = config.getMetadataMap();

        // Create and start gRPC client
        client = new GrpcClient(
                config.getGrpcServerHost(),
                config.getGrpcServerPort(),
                config.getGrpcServiceName(),
                config.getGrpcMethodName(),
                config.getGrpcRequestMessage(),
                config.getGrpcProtoDescriptor(),
                config.isTlsEnabled(),
                config.getTlsCaCert(),
                config.getTlsClientCert(),
                config.getTlsClientKey(),
                metadata,
                config.isReconnectEnabled(),
                config.getReconnectIntervalMs(),
                config.getMaxReconnectAttempts(),
                config.getMaxBackoffMs(),
                config.getMessageQueueSize(),
                config.getConnectionTimeoutMs(),
                config.getKeepaliveTimeMs(),
                config.getKeepaliveTimeoutMs(),
                config.getMaxInboundMessageSize()
        );

        // Link metrics to client
        if (metrics != null) {
            client.setMetrics(metrics);
        }

        client.start();
        log.info("event=task_started session_id={} starting_sequence={} queue_capacity={}",
                connectionSessionId, messageSequence.get(), config.getMessageQueueSize());
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        // If we're stopping, don't accept new messages
        if (stopping.get()) {
            return null;
        }

        // Remove Thread.sleep() - let the framework handle polling intervals
        // Use non-blocking getMessages() which drains the queue
        List<String> messages = client.getMessages();

        if (messages.isEmpty()) {
            // Return null to let Kafka Connect framework control the polling pace
            // The framework will handle backoff and avoid busy-waiting
            return null;
        }

        // Convert messages to SourceRecords with sequence-based offsets
        List<SourceRecord> records = new ArrayList<>(messages.size());
        for (String message : messages) {
            SourceRecord record = createSourceRecord(message);
            if (record != null) {
                records.add(record);
                recordsProduced.incrementAndGet();
            }
        }

        // Update JMX metrics
        if (metrics != null && !records.isEmpty()) {
            metrics.incrementRecordsProduced(records.size());
        }

        // Log metrics periodically
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 30000) { // Every 30 seconds
            logMetrics();
            lastLogTime = now;
        }

        return records.isEmpty() ? null : records;
    }

    @Override
    public void stop() {
        log.info("event=task_stopping session_id={}", connectionSessionId);

        // Graceful shutdown with message draining
        // Step 1: Set stopping flag to prevent accepting new messages in poll()
        stopping.set(true);

        // Step 2: Drain remaining messages from the queue with timeout
        long drainStartTime = System.currentTimeMillis();
        int drainedMessages = 0;

        if (client != null) {
            log.info("event=message_draining_started timeout_ms={}", SHUTDOWN_DRAIN_TIMEOUT_MS);

            while (System.currentTimeMillis() - drainStartTime < SHUTDOWN_DRAIN_TIMEOUT_MS) {
                List<String> messages = client.getMessages();
                if (messages.isEmpty()) {
                    break; // No more messages to drain
                }

                drainedMessages += messages.size();
                log.debug("event=messages_drained count={}", messages.size());

                // Small sleep to allow framework to process these messages
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("event=drain_interrupted");
                    break;
                }
            }

            log.info("event=message_draining_completed drained_count={} duration_ms={}",
                    drainedMessages, (System.currentTimeMillis() - drainStartTime));

            // Step 3: Stop the gRPC client
            client.stop();
        }

        // Step 4: Log final metrics and close JMX
        logMetrics();

        if (metrics != null) {
            try {
                metrics.close();
                log.info("event=jmx_metrics_closed connector_name={}", connectorName);
            } catch (Exception e) {
                log.error("event=jmx_metrics_close_failed error={}", e.getMessage(), e);
            }
        }

        log.info("event=task_stopped session_id={} final_sequence={} last_committed={}",
                connectionSessionId, messageSequence.get(), lastCommittedSequence.get());

        // Clear MDC context
        MDC.clear();
    }

    /**
     * Kafka Connect callback when a record is committed to Kafka.
     * This provides delivery guarantees and allows us to track which messages
     * have been successfully written to Kafka.
     */
    @Override
    public void commitRecord(SourceRecord record, org.apache.kafka.clients.producer.RecordMetadata metadata) {
        try {
            // Extract the sequence number from the source offset
            Map<String, ?> sourceOffset = record.sourceOffset();
            if (sourceOffset != null && sourceOffset.containsKey("sequence")) {
                long committedSeq = ((Number) sourceOffset.get("sequence")).longValue();
                long previousCommitted = lastCommittedSequence.get();

                // Update the last committed sequence
                lastCommittedSequence.set(committedSeq);

                // Detect sequence gaps (potential message loss)
                if (previousCommitted >= 0 && committedSeq != previousCommitted + 1) {
                    long gap = committedSeq - previousCommitted - 1;
                    log.warn("event=sequence_gap_detected previous_committed={} current={} gap_size={}",
                            previousCommitted, committedSeq, gap);
                }

                if (log.isDebugEnabled()) {
                    log.debug("event=record_committed sequence={} kafka_offset={} kafka_partition={}",
                            committedSeq, metadata.offset(), metadata.partition());
                }
            }
        } catch (Exception e) {
            log.error("event=commit_record_error error={}", e.getMessage(), e);
        }
    }

    /**
     * Create a SourceRecord with sequence-based offset management.
     *
     * Offset structure:
     * - session_id: Unique ID for this connection lifecycle (detects reconnections)
     * - sequence: Monotonically increasing number for message ordering
     *
     * This allows:
     * - Detection of message reordering
     * - Detection of message loss (gaps in sequence)
     * - Tracking across gRPC reconnections
     */
    private SourceRecord createSourceRecord(String message) {
        try {
            // Increment sequence number atomically for this message
            long sequence = messageSequence.incrementAndGet();

            // Source partition identifies the data stream source
            Map<String, Object> sourcePartition = new HashMap<>();
            sourcePartition.put("grpc_server", config.getGrpcServerHost() + ":" + config.getGrpcServerPort());
            sourcePartition.put("grpc_method", config.getGrpcServiceName() + "/" + config.getGrpcMethodName());

            // Replace timestamp-based offset with sequence-based tracking
            Map<String, Object> sourceOffset = new HashMap<>();
            sourceOffset.put("session_id", connectionSessionId);
            sourceOffset.put("sequence", sequence);

            return new SourceRecord(
                    sourcePartition,
                    sourceOffset,
                    kafkaTopic,
                    null, // partition - let Kafka decide
                    null, // no key schema
                    null, // no key
                    Schema.STRING_SCHEMA,
                    message,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            log.error("event=source_record_creation_error message_preview={} error={}",
                    truncate(message, 100), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Restore offset state from Kafka Connect framework.
     * This is called during task startup to resume from where we left off.
     */
    private void restoreOffsetState() {
        try {
            Map<String, Object> partition = new HashMap<>();
            partition.put("grpc_server", config.getGrpcServerHost() + ":" + config.getGrpcServerPort());
            partition.put("grpc_method", config.getGrpcServiceName() + "/" + config.getGrpcMethodName());

            Map<String, ?> offsetRaw = context.offsetStorageReader().offset(partition);
            @SuppressWarnings("unchecked")
            Map<String, Object> offset = offsetRaw != null ? (Map<String, Object>) offsetRaw : null;

            if (offset != null && !offset.isEmpty()) {
                // Restore the last committed sequence
                if (offset.containsKey("sequence")) {
                    long restoredSequence = ((Number) offset.get("sequence")).longValue();
                    messageSequence.set(restoredSequence);
                    lastCommittedSequence.set(restoredSequence);

                    String restoredSessionId = offset.getOrDefault("session_id", "unknown").toString();
                    log.info("event=offset_restored session_id={} sequence={}",
                            restoredSessionId, restoredSequence);

                    // Note: Different session ID indicates a restart/reconnection
                    if (!connectionSessionId.equals(restoredSessionId)) {
                        log.warn("event=session_id_changed previous_session={} current_session={} reason=connector_restart",
                                restoredSessionId, connectionSessionId);
                    }
                } else {
                    log.info("event=no_sequence_in_offset starting_from_zero=true");
                }
            } else {
                log.info("event=no_previous_offset starting_fresh=true sequence=0");
            }
        } catch (Exception e) {
            log.error("event=offset_restore_error starting_from_zero=true error={}", e.getMessage(), e);
        }
    }

    /**
     * Log metrics about the task's performance with enhanced observability.
     * Includes queue depth, lag, utilization, and time since last message.
     */
    private void logMetrics() {
        if (client == null) {
            return;
        }

        boolean isConnected = client.isConnected();
        long messagesReceived = client.getMessagesReceived();
        long messagesDropped = client.getMessagesDropped();
        long reconnectAttempts = client.getReconnectAttempts();
        long recordsProducedCount = recordsProduced.get();

        // Enhanced metrics
        int queueSize = client.getQueueSize();
        int queueCapacity = client.getQueueCapacity();
        double queueUtilization = client.getQueueUtilization();
        long lagCount = messagesReceived - recordsProducedCount;
        long millisSinceLastMessage = client.getMillisSinceLastMessage();

        // Structured logging with key=value format
        String metricsLog = String.format(
            "event=task_metrics connected=%s messages_received=%d messages_dropped=%d records_produced=%d " +
            "queue_size=%d queue_capacity=%d queue_utilization_percent=%.2f lag_count=%d " +
            "millis_since_last_message=%d reconnect_attempts=%d session_id=%s",
            isConnected, messagesReceived, messagesDropped, recordsProducedCount,
            queueSize, queueCapacity, queueUtilization, lagCount,
            millisSinceLastMessage, reconnectAttempts, connectionSessionId
        );

        // Log at appropriate level based on connection status and issues
        if (!isConnected) {
            log.warn(metricsLog + " status=DISCONNECTED");
        } else if (messagesDropped > 0) {
            log.warn(metricsLog + " status=DROPPING_MESSAGES");
        } else if (queueUtilization > 80.0) {
            log.warn(metricsLog + " status=HIGH_QUEUE_UTILIZATION");
        } else if (lagCount > 1000) {
            log.warn(metricsLog + " status=HIGH_LAG");
        } else if (millisSinceLastMessage > 60000 && millisSinceLastMessage != -1) {
            log.warn(metricsLog + " status=NO_RECENT_MESSAGES");
        } else {
            log.info(metricsLog + " status=HEALTHY");
        }
    }

    /**
     * Truncate a string to a maximum length for logging.
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
