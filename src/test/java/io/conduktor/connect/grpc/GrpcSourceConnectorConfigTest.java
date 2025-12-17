package io.conduktor.connect.grpc;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GrpcSourceConnectorConfig.
 */
class GrpcSourceConnectorConfigTest {

    private Map<String, String> createMinimalConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "localhost");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "9090");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG, "com.example.MyService");
        props.put(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG, "StreamData");
        props.put(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "grpc-data");
        return props;
    }

    @Test
    void testValidConfiguration() {
        Map<String, String> props = createMinimalConfig();
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals("localhost", config.getGrpcServerHost());
        assertEquals(9090, config.getGrpcServerPort());
        assertEquals("com.example.MyService", config.getGrpcServiceName());
        assertEquals("StreamData", config.getGrpcMethodName());
        assertEquals("grpc-data", config.getKafkaTopic());
        assertEquals("{}", config.getGrpcRequestMessage());
        assertFalse(config.isTlsEnabled());
        assertTrue(config.isReconnectEnabled());
    }

    @Test
    void testMissingRequiredHost() {
        Map<String, String> props = createMinimalConfig();
        props.remove(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG);

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testMissingRequiredPort() {
        Map<String, String> props = createMinimalConfig();
        props.remove(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG);

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testMissingRequiredServiceName() {
        Map<String, String> props = createMinimalConfig();
        props.remove(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG);

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testMissingRequiredMethodName() {
        Map<String, String> props = createMinimalConfig();
        props.remove(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG);

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testMissingRequiredKafkaTopic() {
        Map<String, String> props = createMinimalConfig();
        props.remove(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG);

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testInvalidPortNotANumber() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "not-a-number");

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testTlsConfiguration() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG, "/path/to/ca.crt");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG, "/path/to/client.crt");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG, "/path/to/client.key");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertTrue(config.isTlsEnabled());
        assertEquals("/path/to/ca.crt", config.getTlsCaCert());
        assertEquals("/path/to/client.crt", config.getTlsClientCert());
        assertEquals("/path/to/client.key", config.getTlsClientKey());
    }

    @Test
    void testMetadataParsing() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_METADATA_CONFIG, "key1:value1,key2:value2");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        Map<String, String> metadata = config.getMetadataMap();
        assertEquals(2, metadata.size());
        assertEquals("value1", metadata.get("key1"));
        assertEquals("value2", metadata.get("key2"));
    }

    @Test
    void testMetadataParsingWithSpaces() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_METADATA_CONFIG, " key1 : value1 , key2 : value2 ");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        Map<String, String> metadata = config.getMetadataMap();
        assertEquals(2, metadata.size());
        assertEquals("value1", metadata.get("key1"));
        assertEquals("value2", metadata.get("key2"));
    }

    @Test
    void testMetadataParsingWithColonInValue() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_METADATA_CONFIG, "authorization:Bearer token:with:colons");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        Map<String, String> metadata = config.getMetadataMap();
        assertEquals(1, metadata.size());
        assertEquals("Bearer token:with:colons", metadata.get("authorization"));
    }

    @Test
    void testEmptyMetadata() {
        Map<String, String> props = createMinimalConfig();
        // Don't set metadata at all

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        Map<String, String> metadata = config.getMetadataMap();
        assertTrue(metadata.isEmpty());
    }

    @Test
    void testReconnectionConfiguration() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_ENABLED_CONFIG, "false");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_INTERVAL_MS_CONFIG, "10000");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG, "5");
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_BACKOFF_MAX_MS_CONFIG, "120000");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertFalse(config.isReconnectEnabled());
        assertEquals(10000L, config.getReconnectIntervalMs());
        assertEquals(5, config.getMaxReconnectAttempts());
        assertEquals(120000L, config.getMaxBackoffMs());
    }

    @Test
    void testDefaultValues() {
        Map<String, String> props = createMinimalConfig();
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        // Test defaults
        assertEquals(10000, config.getMessageQueueSize());
        assertEquals(30000L, config.getConnectionTimeoutMs());
        assertEquals(30000L, config.getKeepaliveTimeMs());
        assertEquals(10000L, config.getKeepaliveTimeoutMs());
        assertEquals(4 * 1024 * 1024, config.getMaxInboundMessageSize());
        assertTrue(config.isReconnectEnabled());
        assertEquals(5000L, config.getReconnectIntervalMs());
        assertEquals(-1, config.getMaxReconnectAttempts()); // -1 = infinite
        assertEquals(60000L, config.getMaxBackoffMs());
        assertFalse(config.isTlsEnabled());
        assertNull(config.getTlsCaCert());
        assertNull(config.getTlsClientCert());
        assertNull(config.getTlsClientKey());
        assertNull(config.getGrpcProtoDescriptor());
    }

    @Test
    void testRequestMessageConfiguration() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_REQUEST_MESSAGE_CONFIG, "{\"field\": \"value\"}");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals("{\"field\": \"value\"}", config.getGrpcRequestMessage());
    }

    @Test
    void testProtoDescriptorPath() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_PROTO_DESCRIPTOR_CONFIG, "/path/to/service.desc");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals("/path/to/service.desc", config.getGrpcProtoDescriptor());
    }

    @Test
    void testAdvancedConfiguration() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.MESSAGE_QUEUE_SIZE_CONFIG, "50000");
        props.put(GrpcSourceConnectorConfig.CONNECTION_TIMEOUT_MS_CONFIG, "60000");
        props.put(GrpcSourceConnectorConfig.GRPC_KEEPALIVE_TIME_MS_CONFIG, "15000");
        props.put(GrpcSourceConnectorConfig.GRPC_KEEPALIVE_TIMEOUT_MS_CONFIG, "5000");
        props.put(GrpcSourceConnectorConfig.GRPC_MAX_INBOUND_MESSAGE_SIZE_CONFIG, "16777216");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals(50000, config.getMessageQueueSize());
        assertEquals(60000L, config.getConnectionTimeoutMs());
        assertEquals(15000L, config.getKeepaliveTimeMs());
        assertEquals(5000L, config.getKeepaliveTimeoutMs());
        assertEquals(16777216, config.getMaxInboundMessageSize());
    }

    @Test
    void testInfiniteReconnectAttempts() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG, "-1");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals(-1, config.getMaxReconnectAttempts());
    }

    @Test
    void testDisabledKeepalive() {
        Map<String, String> props = createMinimalConfig();
        props.put(GrpcSourceConnectorConfig.GRPC_KEEPALIVE_TIME_MS_CONFIG, "0");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertEquals(0L, config.getKeepaliveTimeMs());
    }
}
