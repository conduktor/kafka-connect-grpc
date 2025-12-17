package io.conduktor.connect.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX Metrics for gRPC Source Connector.
 * Exposes operational metrics via JMX MBeans for monitoring.
 */
public class GrpcMetrics implements GrpcMetricsMBean, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GrpcMetrics.class);

    private final String connectorName;
    private final ObjectName objectName;
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong recordsProduced = new AtomicLong(0);
    private volatile int currentQueueSize = 0;
    private volatile int queueCapacity = 0;
    private volatile boolean isConnected = false;
    private volatile long lastMessageTimestamp = 0;
    private volatile long connectionStartTime = 0;
    private volatile long totalReconnects = 0;

    public GrpcMetrics(String connectorName, String grpcServer) throws JMException {
        this.connectorName = connectorName;

        // Create JMX ObjectName
        String sanitizedServer = sanitizeServer(grpcServer);
        this.objectName = new ObjectName(
            String.format("io.conduktor.connect.grpc:type=GrpcConnector,name=%s,server=%s",
                ObjectName.quote(connectorName),
                ObjectName.quote(sanitizedServer))
        );

        // Register MBean
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            if (mbs.isRegistered(objectName)) {
                log.warn("event=mbean_already_registered action=unregistering_old_instance object_name={}", objectName);
                mbs.unregisterMBean(objectName);
            }
            mbs.registerMBean(this, objectName);
            log.info("event=mbean_registered object_name={}", objectName);
        } catch (Exception e) {
            log.error("event=mbean_registration_failed object_name={} error={}", objectName, e.getMessage(), e);
            throw new JMException("Failed to register MBean: " + e.getMessage());
        }
    }

    private String sanitizeServer(String server) {
        if (server == null) return "unknown";
        // Remove special characters for JMX name
        String sanitized = server.replaceAll("[^a-zA-Z0-9._:-]", "_");
        // Limit length
        return sanitized.substring(0, Math.min(50, sanitized.length()));
    }

    // Metric update methods

    public void incrementMessagesReceived() {
        messagesReceived.incrementAndGet();
        lastMessageTimestamp = System.currentTimeMillis();
    }

    public void incrementMessagesDropped() {
        messagesDropped.incrementAndGet();
    }

    public void incrementRecordsProduced(long count) {
        recordsProduced.addAndGet(count);
    }

    public void updateQueueSize(int size) {
        this.currentQueueSize = size;
    }

    public void setQueueCapacity(int capacity) {
        this.queueCapacity = capacity;
    }

    public void setConnected(boolean connected) {
        if (connected && !this.isConnected) {
            // Connection established
            connectionStartTime = System.currentTimeMillis();
        }
        this.isConnected = connected;
    }

    public void incrementReconnects() {
        totalReconnects++;
    }

    // JMX MBean interface implementation

    @Override
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    @Override
    public long getMessagesDropped() {
        return messagesDropped.get();
    }

    @Override
    public long getRecordsProduced() {
        return recordsProduced.get();
    }

    @Override
    public int getQueueSize() {
        return currentQueueSize;
    }

    @Override
    public int getQueueCapacity() {
        return queueCapacity;
    }

    @Override
    public double getQueueUtilizationPercent() {
        if (queueCapacity == 0) return 0.0;
        return (currentQueueSize * 100.0) / queueCapacity;
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    @Override
    public long getMillisSinceLastMessage() {
        if (lastMessageTimestamp == 0) return -1;
        return System.currentTimeMillis() - lastMessageTimestamp;
    }

    @Override
    public long getLagCount() {
        return messagesReceived.get() - recordsProduced.get();
    }

    @Override
    public long getUptimeMillis() {
        if (connectionStartTime == 0 || !isConnected) return 0;
        return System.currentTimeMillis() - connectionStartTime;
    }

    @Override
    public long getTotalReconnects() {
        return totalReconnects;
    }

    @Override
    public double getDropRate() {
        long received = messagesReceived.get();
        if (received == 0) return 0.0;
        return (messagesDropped.get() * 100.0) / received;
    }

    @Override
    public void resetCounters() {
        messagesReceived.set(0);
        messagesDropped.set(0);
        recordsProduced.set(0);
        log.info("event=metrics_counters_reset connector_name={}", connectorName);
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public void close() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            if (mbs.isRegistered(objectName)) {
                mbs.unregisterMBean(objectName);
                log.info("event=mbean_unregistered object_name={}", objectName);
            }
        } catch (Exception e) {
            log.error("event=mbean_unregistration_failed object_name={} error={}", objectName, e.getMessage(), e);
        }
    }
}
