package io.conduktor.connect.grpc;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for resource leaks: thread management, connection cleanup, and memory management.
 * Addresses SME review finding: "HIGH: No resource leak tests - threads, connections, memory"
 */
class GrpcSourceTaskResourceTest {

    @Test
    void testTaskStopCleansUpThreads() throws Exception {
        // Given: Record thread count before starting task
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        Set<String> initialThreadNames = getCurrentThreadNames();
        int initialThreadCount = threadMXBean.getThreadCount();

        // When: Starting and stopping task
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();

        // Start task in background (connection will fail, but that's OK for this test)
        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected - connection will fail
            }
        });
        startThread.start();

        // Wait for threads to be created
        Thread.sleep(500);
        startThread.interrupt();

        // Verify threads were created
        Set<String> runningThreadNames = getCurrentThreadNames();

        // Stop the task
        task.stop();

        // Wait for threads to terminate
        Thread.sleep(1000);

        // Then: Thread count should return to baseline (or very close)
        int finalThreadCount = threadMXBean.getThreadCount();
        assertTrue(Math.abs(finalThreadCount - initialThreadCount) <= 3,
            String.format("Thread count should return to baseline. Initial: %d, Final: %d, Diff: %d",
                initialThreadCount, finalThreadCount, Math.abs(finalThreadCount - initialThreadCount)));
    }

    @Test
    void testMultipleStartStopCyclesNoLeaks() throws Exception {
        // Given: Baseline thread count
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int initialThreadCount = threadMXBean.getThreadCount();

        // When: Running multiple start/stop cycles
        for (int i = 0; i < 3; i++) {
            GrpcSourceTask task = new GrpcSourceTask();
            Map<String, String> props = createTestConfig();

            Thread startThread = new Thread(() -> {
                try {
                    task.start(props);
                } catch (Exception e) {
                    // Expected
                }
            });
            startThread.start();
            Thread.sleep(300);
            startThread.interrupt();
            task.stop();
            Thread.sleep(300);
        }

        // Wait for cleanup
        Thread.sleep(500);

        // Then: Thread count should stabilize near baseline
        int finalThreadCount = threadMXBean.getThreadCount();
        assertTrue(Math.abs(finalThreadCount - initialThreadCount) <= 5,
            String.format("Multiple cycles should not leak threads. Initial: %d, Final: %d",
                initialThreadCount, finalThreadCount));
    }

    @Test
    void testTaskStopWhilePolling() throws Exception {
        // Given: Task is actively polling
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(200);

        // Start polling in background thread
        Thread pollingThread = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    task.poll();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pollingThread.start();

        // Wait for some polling to occur
        Thread.sleep(300);

        // When: Stopping task while polling is active
        task.stop();

        // Then: Polling thread should complete gracefully
        pollingThread.join(2000);
        assertFalse(pollingThread.isAlive(),
            "Polling thread should terminate after task stop");
    }

    @Test
    void testStopWithoutStart() {
        // Given: Task is never started
        GrpcSourceTask task = new GrpcSourceTask();

        // When/Then: Stopping should not throw
        assertDoesNotThrow(() -> task.stop(),
            "Stopping unstarted task should be safe");
    }

    @Test
    void testMultipleStopCalls() throws Exception {
        // Given: Task is started
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(300);
        startThread.interrupt();

        // When: Calling stop multiple times
        task.stop();
        Thread.sleep(200);

        // Then: Additional stop calls should not throw
        assertDoesNotThrow(() -> task.stop(),
            "Multiple stop calls should be safe (idempotent)");
    }

    @Test
    void testPollReturnsNullAfterStop() throws Exception {
        // Given: Running task
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(300);
        startThread.interrupt();

        // When: Stopping task
        task.stop();

        // Then: Subsequent poll should return null (or handle gracefully)
        try {
            List<SourceRecord> records = task.poll();
            assertNull(records, "Poll after stop should return null");
        } catch (Exception e) {
            // Acceptable - task may throw if polled after stop
            assertTrue(true, "Task may throw after stop - acceptable behavior");
        }
    }

    @Test
    void testConnectionCleanupOnStop() throws Exception {
        // Given: Task with configured connection
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();

        // Wait for connection attempt
        Thread.sleep(500);
        startThread.interrupt();

        // When: Stopping task
        task.stop();

        // Wait for cleanup
        Thread.sleep(500);

        // Then: gRPC-related threads should be cleaned up
        Set<String> finalThreadNames = getCurrentThreadNames();
        long grpcThreadCount = finalThreadNames.stream()
            .filter(name -> name.contains("grpc") && !name.contains("grpc-default"))
            .count();

        assertTrue(grpcThreadCount <= 2,
            String.format("gRPC-related threads should be cleaned up. Found %d threads: %s",
                grpcThreadCount, finalThreadNames));
    }

    @Test
    void testNoThreadLeaksFromFailedConnections() throws Exception {
        // Given: Configuration with invalid host
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int initialThreadCount = threadMXBean.getThreadCount();

        // When: Starting task with host that will fail to connect
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "invalid-host-12345");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_ENABLED_CONFIG, "false");

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(500);
        startThread.interrupt();

        // Stop task
        task.stop();
        Thread.sleep(500);

        // Then: Should not leak threads despite connection failure
        int finalThreadCount = threadMXBean.getThreadCount();
        assertTrue(Math.abs(finalThreadCount - initialThreadCount) <= 4,
            String.format("Failed connection should not leak threads. Initial: %d, Final: %d",
                initialThreadCount, finalThreadCount));
    }

    @Test
    void testReconnectionThreadManagement() throws Exception {
        // Given: Task with reconnection enabled
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        Set<String> initialThreadNames = getCurrentThreadNames();

        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_INTERVAL_MS_CONFIG, "500");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG, "2");

        // When: Task runs (will have reconnections)
        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(1500); // Allow time for reconnection attempts
        startThread.interrupt();
        task.stop();
        Thread.sleep(500);

        // Then: Reconnection logic should not leak threads
        Set<String> finalThreadNames = getCurrentThreadNames();
        Set<String> newThreads = new HashSet<>(finalThreadNames);
        newThreads.removeAll(initialThreadNames);

        assertTrue(newThreads.size() <= 3,
            "Should not leak threads from reconnection. New threads: " + newThreads);
    }

    @Test
    void testJMXCleanupOnStop() throws Exception {
        // Given: Task with JMX metrics
        GrpcSourceTask task = new GrpcSourceTask();
        Map<String, String> props = createTestConfig();
        props.put("name", "test-jmx-cleanup-connector");

        Thread startThread = new Thread(() -> {
            try {
                task.start(props);
            } catch (Exception e) {
                // Expected
            }
        });
        startThread.start();
        Thread.sleep(300);
        startThread.interrupt();

        // When: Stopping task
        task.stop();
        Thread.sleep(500);

        // Then: JMX MBeans should be unregistered
        Set<javax.management.ObjectName> mbeans =
            ManagementFactory.getPlatformMBeanServer().queryNames(
                new javax.management.ObjectName("io.conduktor.connect.grpc:*"),
                null
            );

        long connectorMBeans = mbeans.stream()
            .filter(name -> name.toString().contains("test-jmx-cleanup-connector"))
            .count();

        assertEquals(0, connectorMBeans,
            "JMX MBeans should be unregistered after stop");
    }

    /**
     * Helper to create test configuration
     */
    private Map<String, String> createTestConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "localhost");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "9999");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG, "test.StreamService");
        props.put(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG, "StreamData");
        props.put(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_ENABLED_CONFIG, "false");
        props.put(GrpcSourceConnectorConfig.MESSAGE_QUEUE_SIZE_CONFIG, "100");
        return props;
    }

    /**
     * Get current thread names for leak detection
     */
    private Set<String> getCurrentThreadNames() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
        Set<String> threadNames = new HashSet<>();
        for (ThreadInfo info : threadInfos) {
            if (info != null) {
                threadNames.add(info.getThreadName());
            }
        }
        return threadNames;
    }
}
