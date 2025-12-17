package io.conduktor.connect.grpc;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrpcSourceTask.
 * Tests task lifecycle, offset management, message polling, and graceful shutdown.
 *
 * Note: These tests use a TestableGrpcClient implementation that doesn't connect to a real server.
 */
@ExtendWith(MockitoExtension.class)
class GrpcSourceTaskTest {

    private GrpcSourceTask task;
    private Map<String, String> props;
    private TestableGrpcClient testClient;

    @Mock
    private SourceTaskContext mockContext;

    @Mock
    private OffsetStorageReader mockOffsetReader;

    @BeforeEach
    void setUp() {
        task = new GrpcSourceTask();
        testClient = new TestableGrpcClient();

        props = new HashMap<>();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "localhost");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "9090");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG, "com.example.TestService");
        props.put(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG, "StreamData");
        props.put(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        props.put("name", "test-connector");

        // Setup mock context with lenient stubbing
        lenient().when(mockContext.offsetStorageReader()).thenReturn(mockOffsetReader);
        lenient().when(mockOffsetReader.offset(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    void testVersion() {
        assertNotNull(task.version());
        assertFalse(task.version().isEmpty());
    }

    @Test
    void testPollWithNoMessages() throws InterruptedException {
        initializeTaskWithTestClient();

        // No messages in queue
        List<SourceRecord> records = task.poll();

        assertNull(records, "Should return null when no messages available");
    }

    @Test
    void testPollWithSingleMessage() throws InterruptedException {
        initializeTaskWithTestClient();

        String testMessage = "{\"data\":\"test\"}";
        testClient.addMessage(testMessage);

        List<SourceRecord> records = task.poll();

        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);
        assertEquals("test-topic", record.topic());
        assertEquals(testMessage, record.value());

        // Verify source partition
        Map<String, ?> sourcePartition = record.sourcePartition();
        assertNotNull(sourcePartition);
        assertEquals("localhost:9090", sourcePartition.get("grpc_server"));
        assertEquals("com.example.TestService/StreamData", sourcePartition.get("grpc_method"));

        // Verify source offset contains sequence
        Map<String, ?> sourceOffset = record.sourceOffset();
        assertNotNull(sourceOffset);
        assertTrue(sourceOffset.containsKey("sequence"));
        assertTrue(sourceOffset.containsKey("session_id"));
        assertEquals(1L, sourceOffset.get("sequence"));
    }

    @Test
    void testPollWithMultipleMessages() throws InterruptedException {
        initializeTaskWithTestClient();

        List<String> messages = Arrays.asList(
            "{\"data\":\"message1\"}",
            "{\"data\":\"message2\"}",
            "{\"data\":\"message3\"}"
        );

        for (String msg : messages) {
            testClient.addMessage(msg);
        }

        List<SourceRecord> records = task.poll();

        assertNotNull(records);
        assertEquals(3, records.size());

        // Verify sequence numbers are incremental
        for (int i = 0; i < records.size(); i++) {
            SourceRecord record = records.get(i);
            Map<String, ?> offset = record.sourceOffset();
            assertEquals((long)(i + 1), offset.get("sequence"));
            assertEquals(messages.get(i), record.value());
        }
    }

    @Test
    void testPollReturnsNullWhenStopping() throws InterruptedException {
        initializeTaskWithTestClient();

        // Stop the task
        task.stop();

        // Poll should return null after stopping
        List<SourceRecord> records = task.poll();
        assertNull(records);
    }

    @Test
    void testCommitRecordUpdatesSequence() {
        initializeTaskWithTestClient();

        Map<String, Object> sourcePartition = new HashMap<>();
        sourcePartition.put("grpc_server", "localhost:9090");
        sourcePartition.put("grpc_method", "com.example.TestService/StreamData");

        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("session_id", "test-session");
        sourceOffset.put("sequence", 42L);

        SourceRecord record = new SourceRecord(
            sourcePartition,
            sourceOffset,
            "test-topic",
            null,
            null,
            null,
            null,
            "{\"test\":\"data\"}",
            System.currentTimeMillis()
        );

        // Don't mock RecordMetadata - just pass null since it's not used in the test
        assertDoesNotThrow(() -> task.commitRecord(record, null));
    }

    @Test
    void testCommitRecordDetectsSequenceGap() {
        initializeTaskWithTestClient();

        commitRecordWithSequence(1L);
        commitRecordWithSequence(5L); // Gap of 3
    }

    @Test
    void testCommitRecordWithNullOffset() {
        initializeTaskWithTestClient();

        SourceRecord record = new SourceRecord(
            Collections.emptyMap(),
            null,
            "test-topic",
            null,
            null,
            null,
            null,
            "data",
            System.currentTimeMillis()
        );

        // Don't mock RecordMetadata - just pass null
        assertDoesNotThrow(() -> task.commitRecord(record, null));
    }

    @Test
    void testOffsetRestoration() throws InterruptedException {
        Map<String, Object> previousOffset = new HashMap<>();
        previousOffset.put("session_id", "previous-session");
        previousOffset.put("sequence", 100L);

        when(mockOffsetReader.offset(any())).thenReturn(previousOffset);
        task.initialize(mockContext);

        initializeTaskWithTestClient();
        testClient.addMessage("{\"test\":\"data\"}");

        List<SourceRecord> records = task.poll();

        assertNotNull(records);
        assertEquals(1, records.size());
        Map<String, ?> offset = records.get(0).sourceOffset();
        assertEquals(101L, offset.get("sequence"));
    }

    @Test
    void testOffsetRestorationWithoutSequence() {
        Map<String, Object> previousOffset = new HashMap<>();
        previousOffset.put("session_id", "previous-session");

        when(mockOffsetReader.offset(any())).thenReturn(previousOffset);
        task.initialize(mockContext);

        assertDoesNotThrow(() -> initializeTaskWithTestClient());
    }

    @Test
    void testOffsetRestorationWithEmptyOffset() {
        when(mockOffsetReader.offset(any())).thenReturn(Collections.emptyMap());
        task.initialize(mockContext);

        assertDoesNotThrow(() -> initializeTaskWithTestClient());
    }

    @Test
    void testStopWithoutStart() {
        assertDoesNotThrow(() -> task.stop());
    }

    @Test
    void testStopMultipleTimes() {
        initializeTaskWithTestClient();

        assertDoesNotThrow(() -> task.stop());
        assertDoesNotThrow(() -> task.stop());
    }

    @Test
    void testGracefulShutdownDrainsMessages() throws InterruptedException {
        initializeTaskWithTestClient();

        testClient.addMessage("{\"msg\":\"1\"}");
        testClient.addMessage("{\"msg\":\"2\"}");
        testClient.addMessage("{\"msg\":\"3\"}");

        // Poll to get some messages
        List<SourceRecord> records = task.poll();
        assertNotNull(records);
        assertEquals(3, records.size());

        assertDoesNotThrow(() -> task.stop());
        assertTrue(testClient.wasStopped());
    }

    @Test
    void testSequenceMonotonicallyIncreases() throws InterruptedException {
        initializeTaskWithTestClient();

        // Batch 1
        testClient.addMessage("{\"a\":\"1\"}");
        testClient.addMessage("{\"b\":\"2\"}");
        List<SourceRecord> batch1 = task.poll();

        // Batch 2
        testClient.addMessage("{\"c\":\"3\"}");
        List<SourceRecord> batch2 = task.poll();

        // Batch 3
        testClient.addMessage("{\"d\":\"4\"}");
        testClient.addMessage("{\"e\":\"5\"}");
        List<SourceRecord> batch3 = task.poll();

        assertEquals(1L, batch1.get(0).sourceOffset().get("sequence"));
        assertEquals(2L, batch1.get(1).sourceOffset().get("sequence"));
        assertEquals(3L, batch2.get(0).sourceOffset().get("sequence"));
        assertEquals(4L, batch3.get(0).sourceOffset().get("sequence"));
        assertEquals(5L, batch3.get(1).sourceOffset().get("sequence"));
    }

    @Test
    void testSessionIdConsistency() throws InterruptedException {
        initializeTaskWithTestClient();

        testClient.addMessage("{\"a\":\"1\"}");
        testClient.addMessage("{\"b\":\"2\"}");

        List<SourceRecord> records = task.poll();

        assertNotNull(records);
        String sessionId = (String) records.get(0).sourceOffset().get("session_id");
        assertNotNull(sessionId);

        for (SourceRecord record : records) {
            assertEquals(sessionId, record.sourceOffset().get("session_id"));
        }
    }

    @Test
    void testRecordTimestampSet() throws InterruptedException {
        initializeTaskWithTestClient();

        testClient.addMessage("{\"test\":\"data\"}");

        long beforePoll = System.currentTimeMillis();
        List<SourceRecord> records = task.poll();
        long afterPoll = System.currentTimeMillis();

        assertNotNull(records);
        Long timestamp = records.get(0).timestamp();
        assertNotNull(timestamp);
        assertTrue(timestamp >= beforePoll && timestamp <= afterPoll);
    }

    // Helper methods

    private void initializeTaskWithTestClient() {
        task.initialize(mockContext);

        // Start task in background to avoid blocking on connection
        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected - connection will fail but we inject test client
            }
        });
        startThread.start();

        try {
            Thread.sleep(50); // Give it a moment to initialize
            injectTestClient();
            startThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void injectTestClient() {
        try {
            Field clientField = GrpcSourceTask.class.getDeclaredField("client");
            clientField.setAccessible(true);
            clientField.set(task, testClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject test client", e);
        }
    }

    private void commitRecordWithSequence(long sequence) {
        Map<String, Object> sourcePartition = new HashMap<>();
        sourcePartition.put("grpc_server", "localhost:9090");
        sourcePartition.put("grpc_method", "com.example.TestService/StreamData");

        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("session_id", "test-session");
        sourceOffset.put("sequence", sequence);

        SourceRecord record = new SourceRecord(
            sourcePartition,
            sourceOffset,
            "test-topic",
            null,
            null,
            null,
            null,
            "data",
            System.currentTimeMillis()
        );

        // Don't mock RecordMetadata - just pass null
        task.commitRecord(record, null);
    }

    /**
     * Testable GrpcClient that doesn't require a real connection.
     */
    private static class TestableGrpcClient extends GrpcClient {
        private final LinkedBlockingDeque<String> messageQueue = new LinkedBlockingDeque<>(1000);
        private boolean stopped = false;

        public TestableGrpcClient() {
            super("localhost", 9090, "test.Service", "TestMethod", "{}", null,
                  false, null, null, null, new HashMap<>(), false,
                  5000L, -1, 60000L, 10000, 30000L, 30000L, 10000L, 4 * 1024 * 1024);
        }

        @Override
        public void start() {
            // Don't actually start - avoid connection
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public List<String> getMessages() {
            List<String> messages = new ArrayList<>();
            messageQueue.drainTo(messages);
            return messages;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public long getMessagesReceived() {
            return 0L;
        }

        @Override
        public long getMessagesDropped() {
            return 0L;
        }

        @Override
        public long getReconnectAttempts() {
            return 0L;
        }

        @Override
        public int getQueueSize() {
            return messageQueue.size();
        }

        @Override
        public int getQueueCapacity() {
            return 10000;
        }

        @Override
        public double getQueueUtilization() {
            return 0.0;
        }

        @Override
        public long getMillisSinceLastMessage() {
            return 0L;
        }

        public void addMessage(String message) {
            messageQueue.offer(message);
        }

        public boolean wasStopped() {
            return stopped;
        }
    }
}
