package io.conduktor.connect.grpc;

/**
 * MBean interface for gRPC metrics.
 * All getter methods are exposed as JMX attributes.
 */
public interface GrpcMetricsMBean {

    // Counter metrics
    long getMessagesReceived();
    long getMessagesDropped();
    long getRecordsProduced();

    // Queue metrics
    int getQueueSize();
    int getQueueCapacity();
    double getQueueUtilizationPercent();

    // Connection metrics
    boolean isConnected();
    long getMillisSinceLastMessage();
    long getUptimeMillis();
    long getTotalReconnects();

    // Derived metrics
    long getLagCount();
    double getDropRate();

    // Metadata
    String getConnectorName();

    // Operations
    void resetCounters();
}
