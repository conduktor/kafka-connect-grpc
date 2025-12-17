package io.conduktor.connect.grpc;

import org.apache.kafka.connect.connector.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GrpcSourceConnector.
 */
class GrpcSourceConnectorTest {

    private GrpcSourceConnector connector;
    private Map<String, String> props;

    @BeforeEach
    void setUp() {
        connector = new GrpcSourceConnector();
        props = new HashMap<>();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "localhost");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "9090");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG, "com.example.MyService");
        props.put(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG, "StreamData");
        props.put(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "grpc-data");
    }

    @Test
    void testVersion() {
        assertNotNull(connector.version());
    }

    @Test
    void testStart() {
        assertDoesNotThrow(() -> connector.start(props));
    }

    @Test
    void testTaskClass() {
        assertEquals(GrpcSourceTask.class, connector.taskClass());
    }

    @Test
    void testTaskConfigs() {
        connector.start(props);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);

        assertEquals(1, taskConfigs.size());
        assertEquals(props, taskConfigs.get(0));
    }

    @Test
    void testTaskConfigsMultipleTasks() {
        connector.start(props);
        // Even with maxTasks > 1, we should only get 1 task for gRPC streaming
        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        assertEquals(1, taskConfigs.size());
    }

    @Test
    void testStop() {
        connector.start(props);
        assertDoesNotThrow(() -> connector.stop());
    }

    @Test
    void testConfig() {
        assertNotNull(connector.config());
        assertEquals(GrpcSourceConnectorConfig.CONFIG_DEF, connector.config());
    }
}
