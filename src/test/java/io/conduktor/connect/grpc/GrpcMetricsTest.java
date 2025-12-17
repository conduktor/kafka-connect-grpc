package io.conduktor.connect.grpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.JMException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GrpcMetrics.
 */
class GrpcMetricsTest {

    private GrpcMetrics metrics;

    @BeforeEach
    void setUp() throws JMException {
        metrics = new GrpcMetrics("test-connector", "localhost:9090");
    }

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.close();
        }
    }

    @Test
    void testInitialValues() {
        assertEquals(0, metrics.getMessagesReceived());
        assertEquals(0, metrics.getMessagesDropped());
        assertEquals(0, metrics.getRecordsProduced());
        assertEquals(0, metrics.getQueueSize());
        assertEquals(0, metrics.getQueueCapacity());
        assertFalse(metrics.isConnected());
        assertEquals(-1, metrics.getMillisSinceLastMessage());
        assertEquals(0, metrics.getUptimeMillis());
        assertEquals(0, metrics.getTotalReconnects());
    }

    @Test
    void testIncrementMessagesReceived() {
        metrics.incrementMessagesReceived();
        assertEquals(1, metrics.getMessagesReceived());
        assertTrue(metrics.getMillisSinceLastMessage() >= 0);

        metrics.incrementMessagesReceived();
        assertEquals(2, metrics.getMessagesReceived());
    }

    @Test
    void testIncrementMessagesDropped() {
        metrics.incrementMessagesDropped();
        assertEquals(1, metrics.getMessagesDropped());

        metrics.incrementMessagesDropped();
        assertEquals(2, metrics.getMessagesDropped());
    }

    @Test
    void testIncrementRecordsProduced() {
        metrics.incrementRecordsProduced(5);
        assertEquals(5, metrics.getRecordsProduced());

        metrics.incrementRecordsProduced(3);
        assertEquals(8, metrics.getRecordsProduced());
    }

    @Test
    void testQueueMetrics() {
        metrics.setQueueCapacity(100);
        assertEquals(100, metrics.getQueueCapacity());

        metrics.updateQueueSize(50);
        assertEquals(50, metrics.getQueueSize());
        assertEquals(50.0, metrics.getQueueUtilizationPercent(), 0.01);

        metrics.updateQueueSize(80);
        assertEquals(80.0, metrics.getQueueUtilizationPercent(), 0.01);
    }

    @Test
    void testConnectionMetrics() {
        assertFalse(metrics.isConnected());
        assertEquals(0, metrics.getUptimeMillis());

        metrics.setConnected(true);
        assertTrue(metrics.isConnected());
        assertTrue(metrics.getUptimeMillis() >= 0);

        metrics.setConnected(false);
        assertFalse(metrics.isConnected());
        assertEquals(0, metrics.getUptimeMillis());
    }

    @Test
    void testReconnectMetrics() {
        assertEquals(0, metrics.getTotalReconnects());

        metrics.incrementReconnects();
        assertEquals(1, metrics.getTotalReconnects());

        metrics.incrementReconnects();
        assertEquals(2, metrics.getTotalReconnects());
    }

    @Test
    void testLagCount() {
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();

        metrics.incrementRecordsProduced(2);

        assertEquals(1, metrics.getLagCount());
    }

    @Test
    void testDropRate() {
        assertEquals(0.0, metrics.getDropRate(), 0.01);

        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();

        metrics.incrementMessagesDropped();

        assertEquals(25.0, metrics.getDropRate(), 0.01);
    }

    @Test
    void testResetCounters() {
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesDropped();
        metrics.incrementRecordsProduced(5);

        assertEquals(1, metrics.getMessagesReceived());
        assertEquals(1, metrics.getMessagesDropped());
        assertEquals(5, metrics.getRecordsProduced());

        metrics.resetCounters();

        assertEquals(0, metrics.getMessagesReceived());
        assertEquals(0, metrics.getMessagesDropped());
        assertEquals(0, metrics.getRecordsProduced());
    }

    @Test
    void testConnectorName() {
        assertEquals("test-connector", metrics.getConnectorName());
    }
}
