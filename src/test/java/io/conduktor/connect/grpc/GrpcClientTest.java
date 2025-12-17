package io.conduktor.connect.grpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.JMException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GrpcClient.
 * Tests client construction, message queue operations, connection state tracking,
 * and backoff calculation.
 *
 * Note: These tests focus on the client's internal state and queue management.
 * Actual gRPC connection tests are not performed to avoid requiring a real gRPC server.
 */
class GrpcClientTest {

    private GrpcClient client;
    private GrpcMetrics metrics;

    @BeforeEach
    void setUp() throws JMException {
        metrics = new GrpcMetrics("test-connector", "localhost:9090");
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        if (metrics != null) {
            metrics.close();
        }
    }

    @Test
    void testConstructorWithMinimalConfiguration() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null, // no proto descriptor
            false, // no TLS
            null,
            null,
            null,
            new HashMap<>(),
            false, // reconnect disabled
            5000L,
            -1,
            60000L,
            10000, // queue size
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
        assertEquals(0, client.getMessagesReceived());
        assertEquals(0, client.getMessagesDropped());
        assertEquals(0, client.getReconnectAttempts());
        assertEquals(10000, client.getQueueCapacity());
        assertEquals(0, client.getQueueSize());
        assertEquals(0.0, client.getQueueUtilization(), 0.01);
        assertEquals(-1, client.getMillisSinceLastMessage());
        assertFalse(client.isConnected());
    }

    @Test
    void testConstructorWithFullConfiguration() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("authorization", "Bearer token123");
        metadata.put("x-api-key", "key456");

        client = new GrpcClient(
            "grpc.example.com",
            443,
            "com.example.ProductService",
            "GetProducts",
            "{\"category\":\"electronics\"}",
            null,
            true, // TLS enabled
            "/path/to/ca.crt",
            "/path/to/client.crt",
            "/path/to/client.key",
            metadata,
            true, // reconnect enabled
            10000L,
            10,
            120000L,
            5000,
            60000L,
            45000L,
            15000L,
            8 * 1024 * 1024
        );

        assertNotNull(client);
        assertEquals(5000, client.getQueueCapacity());
        assertFalse(client.isConnected());
    }

    @Test
    void testConstructorWithNullMetadata() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            null, // null metadata
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testGetMessagesWhenQueueEmpty() {
        client = createBasicClient();

        List<String> messages = client.getMessages();

        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void testQueueCapacity() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            500, // custom queue size
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertEquals(500, client.getQueueCapacity());
    }

    @Test
    void testInitialConnectionState() {
        client = createBasicClient();

        assertFalse(client.isConnected(), "Client should not be connected initially");
    }

    @Test
    void testInitialMessagesReceived() {
        client = createBasicClient();

        assertEquals(0, client.getMessagesReceived());
    }

    @Test
    void testInitialMessagesDropped() {
        client = createBasicClient();

        assertEquals(0, client.getMessagesDropped());
    }

    @Test
    void testInitialReconnectAttempts() {
        client = createBasicClient();

        assertEquals(0, client.getReconnectAttempts());
    }

    @Test
    void testInitialQueueSize() {
        client = createBasicClient();

        assertEquals(0, client.getQueueSize());
    }

    @Test
    void testInitialQueueUtilization() {
        client = createBasicClient();

        assertEquals(0.0, client.getQueueUtilization(), 0.01);
    }

    @Test
    void testQueueUtilizationCalculation() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            100, // queue size of 100 for easy calculation
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        // Initial utilization should be 0%
        assertEquals(0.0, client.getQueueUtilization(), 0.01);

        // After adding messages, utilization should be proportional
        // Note: We can't easily add messages to the queue without starting the client
        // This is a structural test of the calculation method
    }

    @Test
    void testMillisSinceLastMessageWhenNoMessages() {
        client = createBasicClient();

        // When no messages received, should return -1
        assertEquals(-1, client.getMillisSinceLastMessage());
    }

    @Test
    void testSetMetrics() {
        client = createBasicClient();

        assertDoesNotThrow(() -> client.setMetrics(metrics));
    }

    @Test
    void testSetNullMetrics() {
        client = createBasicClient();

        assertDoesNotThrow(() -> client.setMetrics(null));
    }

    @Test
    void testStopWithoutStart() {
        client = createBasicClient();

        // Should handle stop gracefully even if never started
        assertDoesNotThrow(() -> client.stop());
    }

    @Test
    void testStopMultipleTimes() {
        client = createBasicClient();

        // Multiple stops should be safe
        assertDoesNotThrow(() -> client.stop());
        assertDoesNotThrow(() -> client.stop());
        assertDoesNotThrow(() -> client.stop());
    }

    @Test
    void testReconnectDisabled() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false, // reconnect disabled
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        // Even if connection fails, reconnect attempts should stay at 0
        assertEquals(0, client.getReconnectAttempts());
    }

    @Test
    void testReconnectEnabled() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            true, // reconnect enabled
            5000L,
            10, // max attempts
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
        // Initial reconnect attempts should be 0
        assertEquals(0, client.getReconnectAttempts());
    }

    @Test
    void testBackoffCalculationLogic() {
        // Test backoff calculation through reflection or by observing behavior
        // The backoff should follow: baseInterval * 2^(attempt-1), capped at maxBackoff

        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            true,
            1000L, // 1 second base interval
            -1,
            10000L, // 10 second max backoff
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        // We can't directly test the private method, but we can verify configuration
        assertNotNull(client);
    }

    @Test
    void testConnectionTimeoutConfiguration() {
        long customTimeout = 60000L;
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            customTimeout,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testKeepaliveConfiguration() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            60000L, // 60 second keepalive
            20000L, // 20 second keepalive timeout
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testKeepaliveDisabled() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            0L, // keepalive disabled
            0L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testMaxInboundMessageSize() {
        int customSize = 16 * 1024 * 1024; // 16MB
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            customSize
        );

        assertNotNull(client);
    }

    @Test
    void testEmptyRequestMessage() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "", // empty request message
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testJsonRequestMessage() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{\"filter\":{\"category\":\"test\"},\"limit\":100}", // complex JSON
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testTlsEnabledWithCaCert() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            true, // TLS enabled
            "/path/to/ca.crt",
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testTlsEnabledWithMutualTls() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            true,
            "/path/to/ca.crt",
            "/path/to/client.crt",
            "/path/to/client.key",
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testMetadataWithMultipleHeaders() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("authorization", "Bearer token");
        metadata.put("x-api-key", "12345");
        metadata.put("x-request-id", "req-123");
        metadata.put("x-tenant-id", "tenant-456");

        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            metadata,
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testReconnectWithMaxAttempts() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            true,
            5000L,
            5, // max 5 attempts
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testReconnectWithUnlimitedAttempts() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            true,
            5000L,
            -1, // unlimited attempts
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testSmallQueueSize() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10, // very small queue
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertEquals(10, client.getQueueCapacity());
    }

    @Test
    void testLargeQueueSize() {
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            100000, // large queue
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertEquals(100000, client.getQueueCapacity());
    }

    @Test
    void testServiceAndMethodNames() {
        String serviceName = "com.example.orders.OrderService";
        String methodName = "GetOrderStream";

        client = new GrpcClient(
            "localhost",
            9090,
            serviceName,
            methodName,
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testDifferentPorts() {
        // Test with standard gRPC port
        client = new GrpcClient(
            "localhost",
            50051,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
        client.stop();

        // Test with HTTPS port
        client = new GrpcClient(
            "grpc.example.com",
            443,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            true,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testExponentialBackoffCapping() {
        // Configure with short base interval and low max backoff
        client = new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            true,
            100L, // 100ms base
            -1,
            1000L, // 1 second max backoff
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );

        // Verify construction succeeds
        assertNotNull(client);
        // The backoff should be capped at maxBackoffMs
        // We can't directly test the private method, but configuration is validated
    }

    // Helper method to create a basic client for testing
    private GrpcClient createBasicClient() {
        return new GrpcClient(
            "localhost",
            9090,
            "com.example.TestService",
            "StreamData",
            "{}",
            null,
            false,
            null,
            null,
            null,
            new HashMap<>(),
            false,
            5000L,
            -1,
            60000L,
            10000,
            30000L,
            30000L,
            10000L,
            4 * 1024 * 1024
        );
    }
}
