package io.conduktor.connect.grpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JMX MBean registration and lifecycle.
 * Addresses SME review finding: "HIGH: No JMX MBean registration/lifecycle testing"
 *
 * Tests ensure:
 * - MBeans are properly registered on start
 * - MBeans are properly unregistered on stop
 * - Multiple connectors can coexist with unique MBean names
 * - Re-registration after restart works correctly
 */
class GrpcMetricsLifecycleTest {

    private static final String JMX_DOMAIN = "io.conduktor.connect.grpc";
    private static final String TEST_SERVER = "localhost:9090";
    private MBeanServer mbeanServer;

    @BeforeEach
    void setUp() {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
    }

    @AfterEach
    void tearDown() {
        // Clean up any lingering MBeans from failed tests
        try {
            Set<ObjectName> mbeans = mbeanServer.queryNames(
                    new ObjectName(JMX_DOMAIN + ":*"), null);
            for (ObjectName name : mbeans) {
                try {
                    mbeanServer.unregisterMBean(name);
                } catch (Exception e) {
                    // Ignore
                }
            }
        } catch (MalformedObjectNameException e) {
            // Ignore
        }
    }

    @Test
    void testMBeanRegistrationOnMetricsStart() throws Exception {
        // Given: No MBeans registered initially
        String connectorName = "test-registration-connector";
        Set<ObjectName> initialMBeans = queryConnectorMBeans(connectorName);
        assertTrue(initialMBeans.isEmpty(), "Should have no MBeans initially");

        // When: Creating and registering metrics
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        // Then: MBean should be registered
        Set<ObjectName> registeredMBeans = queryConnectorMBeans(connectorName);
        assertEquals(1, registeredMBeans.size(),
                "Should have exactly one MBean registered");

        ObjectName mbeanName = registeredMBeans.iterator().next();
        assertTrue(mbeanName.toString().contains(connectorName),
                "MBean name should contain connector name");

        // Cleanup
        metrics.close();
    }

    @Test
    void testMBeanUnregistrationOnMetricsClose() throws Exception {
        // Given: Metrics with registered MBean
        String connectorName = "test-unregister-connector";
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        Set<ObjectName> registeredMBeans = queryConnectorMBeans(connectorName);
        assertEquals(1, registeredMBeans.size(), "Should have MBean registered");

        // When: Closing metrics
        metrics.close();

        // Then: MBean should be unregistered
        Set<ObjectName> remainingMBeans = queryConnectorMBeans(connectorName);
        assertTrue(remainingMBeans.isEmpty(),
                "Should have no MBeans after close");
    }

    @Test
    void testMultipleConnectorMBeansCoexist() throws Exception {
        // Given: Multiple connectors
        String connector1 = "connector-1";
        String connector2 = "connector-2";
        String connector3 = "connector-3";

        // When: Creating metrics for each
        GrpcMetrics metrics1 = new GrpcMetrics(connector1, TEST_SERVER);
        GrpcMetrics metrics2 = new GrpcMetrics(connector2, TEST_SERVER);
        GrpcMetrics metrics3 = new GrpcMetrics(connector3, TEST_SERVER);

        // Then: Each should have its own MBean
        Set<ObjectName> allMBeans = mbeanServer.queryNames(
                new ObjectName(JMX_DOMAIN + ":*"), null);
        assertTrue(allMBeans.size() >= 3,
                "Should have at least 3 MBeans registered");

        assertEquals(1, queryConnectorMBeans(connector1).size());
        assertEquals(1, queryConnectorMBeans(connector2).size());
        assertEquals(1, queryConnectorMBeans(connector3).size());

        // Cleanup
        metrics1.close();
        metrics2.close();
        metrics3.close();
    }

    @Test
    void testMBeanReRegistrationAfterClose() throws Exception {
        // Given: Metrics that were previously registered and closed
        String connectorName = "test-re-register-connector";
        GrpcMetrics metrics1 = new GrpcMetrics(connectorName, TEST_SERVER);
        metrics1.close();

        Set<ObjectName> afterClose = queryConnectorMBeans(connectorName);
        assertTrue(afterClose.isEmpty(), "Should have no MBeans after close");

        // When: Creating new metrics with same name
        GrpcMetrics metrics2 = new GrpcMetrics(connectorName, TEST_SERVER);

        // Then: Should successfully re-register
        Set<ObjectName> afterReRegister = queryConnectorMBeans(connectorName);
        assertEquals(1, afterReRegister.size(),
                "Should have MBean after re-registration");

        // Cleanup
        metrics2.close();
    }

    @Test
    void testMBeanAttributesAccessible() throws Exception {
        // Given: Registered metrics
        String connectorName = "test-attributes-connector";
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        // When: Querying MBean attributes
        Set<ObjectName> mbeans = queryConnectorMBeans(connectorName);
        assertEquals(1, mbeans.size());
        ObjectName mbeanName = mbeans.iterator().next();

        // Then: Should be able to read attributes
        Object messagesReceived = mbeanServer.getAttribute(mbeanName, "MessagesReceived");
        assertNotNull(messagesReceived, "MessagesReceived should be accessible");
        assertEquals(0L, messagesReceived);

        Object messagesDropped = mbeanServer.getAttribute(mbeanName, "MessagesDropped");
        assertNotNull(messagesDropped, "MessagesDropped should be accessible");
        assertEquals(0L, messagesDropped);

        Object connected = mbeanServer.getAttribute(mbeanName, "Connected");
        assertNotNull(connected, "Connected should be accessible");
        assertEquals(false, connected);

        Object queueSize = mbeanServer.getAttribute(mbeanName, "QueueSize");
        assertNotNull(queueSize, "QueueSize should be accessible");

        // Cleanup
        metrics.close();
    }

    @Test
    void testMBeanAttributesUpdateCorrectly() throws Exception {
        // Given: Registered metrics
        String connectorName = "test-update-connector";
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        Set<ObjectName> mbeans = queryConnectorMBeans(connectorName);
        ObjectName mbeanName = mbeans.iterator().next();

        // When: Updating metrics
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesReceived();
        metrics.incrementMessagesDropped();
        metrics.setConnected(true);

        // Then: MBean should reflect updates
        assertEquals(2L, mbeanServer.getAttribute(mbeanName, "MessagesReceived"));
        assertEquals(1L, mbeanServer.getAttribute(mbeanName, "MessagesDropped"));
        assertEquals(true, mbeanServer.getAttribute(mbeanName, "Connected"));

        // Cleanup
        metrics.close();
    }

    @Test
    void testDoubleCloseIsIdempotent() throws Exception {
        // Given: Registered metrics
        String connectorName = "test-double-close-connector";
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        // When: Closing twice
        metrics.close();

        // Then: Second close should not throw
        assertDoesNotThrow(() -> metrics.close(),
                "Double close should be idempotent");

        Set<ObjectName> remaining = queryConnectorMBeans(connectorName);
        assertTrue(remaining.isEmpty(), "No MBeans should remain");
    }

    @Test
    void testMBeanNameFormat() throws Exception {
        // Given: Metrics with specific connector name
        String connectorName = "my-grpc-connector";
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        // When: Querying MBean
        Set<ObjectName> mbeans = queryConnectorMBeans(connectorName);

        // Then: Name should follow expected format
        assertEquals(1, mbeans.size());
        ObjectName mbeanName = mbeans.iterator().next();

        String fullName = mbeanName.toString();
        assertTrue(fullName.startsWith(JMX_DOMAIN + ":"),
                "Should start with domain: " + fullName);
        assertTrue(fullName.contains("type=") || fullName.contains("name="),
                "Should contain type or name key: " + fullName);

        // Cleanup
        metrics.close();
    }

    @Test
    void testSpecialCharactersInConnectorName() throws Exception {
        // Given: Connector name with special characters
        String connectorName = "connector-with.dots_and-dashes";

        // When: Creating metrics
        GrpcMetrics metrics = new GrpcMetrics(connectorName, TEST_SERVER);

        // Then: Should handle special characters
        Set<ObjectName> mbeans = queryConnectorMBeans(connectorName);
        assertEquals(1, mbeans.size(),
                "Should register MBean even with special characters");

        // Cleanup
        metrics.close();
    }

    /**
     * Helper to query MBeans for a specific connector
     */
    private Set<ObjectName> queryConnectorMBeans(String connectorName) throws MalformedObjectNameException {
        // Query all MBeans in our domain that match the connector name
        Set<ObjectName> allMBeans = mbeanServer.queryNames(
                new ObjectName(JMX_DOMAIN + ":*"), null);

        return allMBeans.stream()
                .filter(name -> {
                    String nameStr = name.toString();
                    return nameStr.contains(connectorName) ||
                           nameStr.contains("connector=" + connectorName);
                })
                .collect(java.util.stream.Collectors.toSet());
    }
}
