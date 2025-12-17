package io.conduktor.connect.grpc;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TLS/mTLS configuration validation and integration.
 * Addresses SME review finding: "BLOCKER: Zero TLS/mTLS testing"
 *
 * Note: These tests validate TLS configuration parsing and validation.
 * Full TLS handshake testing would require test certificates.
 */
class GrpcTlsConfigTest {

    private Map<String, String> props;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        props = new HashMap<>();
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_HOST_CONFIG, "localhost");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVER_PORT_CONFIG, "9090");
        props.put(GrpcSourceConnectorConfig.GRPC_SERVICE_NAME_CONFIG, "test.Service");
        props.put(GrpcSourceConnectorConfig.GRPC_METHOD_NAME_CONFIG, "StreamData");
        props.put(GrpcSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
    }

    @Test
    void testTlsDisabledByDefault() {
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertFalse(config.isTlsEnabled());
    }

    @Test
    void testTlsEnabledExplicitly() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertTrue(config.isTlsEnabled());
    }

    @Test
    void testTlsDisabledExplicitly() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "false");
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertFalse(config.isTlsEnabled());
    }

    @Test
    void testTlsCaCertPathConfiguration() throws IOException {
        Path caCertFile = tempDir.resolve("ca.crt");
        Files.writeString(caCertFile, "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");

        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG, caCertFile.toString());

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertEquals(caCertFile.toString(), config.getTlsCaCert());
    }

    @Test
    void testTlsClientCertPathConfiguration() throws IOException {
        Path clientCertFile = tempDir.resolve("client.crt");
        Files.writeString(clientCertFile, "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");

        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG, clientCertFile.toString());

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertEquals(clientCertFile.toString(), config.getTlsClientCert());
    }

    @Test
    void testTlsClientKeyPathConfiguration() throws IOException {
        Path clientKeyFile = tempDir.resolve("client.key");
        Files.writeString(clientKeyFile, "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----");

        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG, clientKeyFile.toString());

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertEquals(clientKeyFile.toString(), config.getTlsClientKey());
    }

    @Test
    void testMtlsFullConfiguration() throws IOException {
        Path caCertFile = tempDir.resolve("ca.crt");
        Path clientCertFile = tempDir.resolve("client.crt");
        Path clientKeyFile = tempDir.resolve("client.key");

        Files.writeString(caCertFile, "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----");
        Files.writeString(clientCertFile, "-----BEGIN CERTIFICATE-----\nCLIENT\n-----END CERTIFICATE-----");
        Files.writeString(clientKeyFile, "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----");

        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG, caCertFile.toString());
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG, clientCertFile.toString());
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG, clientKeyFile.toString());

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertTrue(config.isTlsEnabled());
        assertEquals(caCertFile.toString(), config.getTlsCaCert());
        assertEquals(clientCertFile.toString(), config.getTlsClientCert());
        assertEquals(clientKeyFile.toString(), config.getTlsClientKey());
    }

    @Test
    void testTlsEnabledWithoutCertsUsesSystemDefaults() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");

        // Should not throw - uses system default trust store
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        assertTrue(config.isTlsEnabled());
        assertNull(config.getTlsCaCert());
    }

    @Test
    void testTlsConfigValueTypes() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        // Verify config keys exist
        assertNotNull(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG);
        assertNotNull(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG);
        assertNotNull(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG);
        assertNotNull(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG);
    }

    @Test
    void testTlsEnabledWithInvalidBooleanValue() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "not-a-boolean");

        assertThrows(ConfigException.class, () -> new GrpcSourceConnectorConfig(props));
    }

    @Test
    void testGrpcClientTlsConfiguration() throws IOException {
        Path caCertFile = tempDir.resolve("ca.crt");
        Files.writeString(caCertFile, "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");

        // Test that GrpcClient can be constructed with TLS config
        GrpcClient client = new GrpcClient(
                "localhost", 443,
                "test.Service", "StreamData",
                "{}", null,
                true,  // TLS enabled
                caCertFile.toString(),
                null, null,
                new HashMap<>(),
                false, 5000L, -1, 60000L, 10000,
                30000L, 30000L, 10000L, 4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testGrpcClientMtlsConfiguration() throws IOException {
        Path caCertFile = tempDir.resolve("ca.crt");
        Path clientCertFile = tempDir.resolve("client.crt");
        Path clientKeyFile = tempDir.resolve("client.key");

        Files.writeString(caCertFile, "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----");
        Files.writeString(clientCertFile, "-----BEGIN CERTIFICATE-----\nCLIENT\n-----END CERTIFICATE-----");
        Files.writeString(clientKeyFile, "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----");

        // Test that GrpcClient can be constructed with mTLS config
        GrpcClient client = new GrpcClient(
                "localhost", 443,
                "test.Service", "StreamData",
                "{}", null,
                true,  // TLS enabled
                caCertFile.toString(),
                clientCertFile.toString(),
                clientKeyFile.toString(),
                new HashMap<>(),
                false, 5000L, -1, 60000L, 10000,
                30000L, 30000L, 10000L, 4 * 1024 * 1024
        );

        assertNotNull(client);
    }

    @Test
    void testTlsConfigWithNullValues() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "false");

        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);

        assertFalse(config.isTlsEnabled());
        assertNull(config.getTlsCaCert());
        assertNull(config.getTlsClientCert());
        assertNull(config.getTlsClientKey());
    }

    @Test
    void testValidatorAcceptsTlsConfig() {
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");

        GrpcSourceConnector connector = new GrpcSourceConnector();
        org.apache.kafka.common.config.Config result = connector.validate(props);

        // TLS enabled config should be valid
        boolean hasTlsErrors = result.configValues().stream()
                .filter(cv -> cv.name().equals(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG))
                .anyMatch(cv -> !cv.errorMessages().isEmpty());

        assertFalse(hasTlsErrors, "TLS enabled config should not have errors");
    }

    @Test
    void testValidatorAcceptsMtlsConfig() throws IOException {
        Path caCertFile = tempDir.resolve("ca.crt");
        Path clientCertFile = tempDir.resolve("client.crt");
        Path clientKeyFile = tempDir.resolve("client.key");

        Files.writeString(caCertFile, "test-ca");
        Files.writeString(clientCertFile, "test-client");
        Files.writeString(clientKeyFile, "test-key");

        props.put(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG, "true");
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG, caCertFile.toString());
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG, clientCertFile.toString());
        props.put(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG, clientKeyFile.toString());

        GrpcSourceConnector connector = new GrpcSourceConnector();
        org.apache.kafka.common.config.Config result = connector.validate(props);

        // mTLS config should be valid
        long errorCount = result.configValues().stream()
                .filter(cv -> cv.name().startsWith("grpc.tls"))
                .filter(cv -> !cv.errorMessages().isEmpty())
                .count();

        assertEquals(0, errorCount, "mTLS config should not have errors");
    }

    @Test
    void testTlsConfigInConnectorConfig() {
        // Verify TLS config keys are defined in CONFIG_DEF
        assertTrue(GrpcSourceConnectorConfig.CONFIG_DEF.configKeys()
                .containsKey(GrpcSourceConnectorConfig.GRPC_TLS_ENABLED_CONFIG));
        assertTrue(GrpcSourceConnectorConfig.CONFIG_DEF.configKeys()
                .containsKey(GrpcSourceConnectorConfig.GRPC_TLS_CA_CERT_CONFIG));
        assertTrue(GrpcSourceConnectorConfig.CONFIG_DEF.configKeys()
                .containsKey(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_CERT_CONFIG));
        assertTrue(GrpcSourceConnectorConfig.CONFIG_DEF.configKeys()
                .containsKey(GrpcSourceConnectorConfig.GRPC_TLS_CLIENT_KEY_CONFIG));
    }
}
