package io.conduktor.connect.grpc;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigException;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the gRPC Source Connector.
 */
public class GrpcSourceConnectorConfig extends AbstractConfig {

    // gRPC Server Configuration
    public static final String GRPC_SERVER_HOST_CONFIG = "grpc.server.host";
    private static final String GRPC_SERVER_HOST_DOC = "gRPC server hostname or IP address";

    public static final String GRPC_SERVER_PORT_CONFIG = "grpc.server.port";
    private static final String GRPC_SERVER_PORT_DOC = "gRPC server port";

    public static final String GRPC_SERVICE_NAME_CONFIG = "grpc.service.name";
    private static final String GRPC_SERVICE_NAME_DOC = "Fully qualified service name (e.g., com.example.MyService)";

    public static final String GRPC_METHOD_NAME_CONFIG = "grpc.method.name";
    private static final String GRPC_METHOD_NAME_DOC = "Server streaming method name";

    // Request Configuration
    public static final String GRPC_REQUEST_MESSAGE_CONFIG = "grpc.request.message";
    private static final String GRPC_REQUEST_MESSAGE_DOC = "JSON representation of the request message (will be converted to protobuf)";

    // Proto Descriptor Configuration
    public static final String GRPC_PROTO_DESCRIPTOR_CONFIG = "grpc.proto.descriptor";
    private static final String GRPC_PROTO_DESCRIPTOR_DOC = "Path to compiled .desc file OR base64-encoded proto descriptor";

    // Kafka Configuration
    public static final String KAFKA_TOPIC_CONFIG = "kafka.topic";
    private static final String KAFKA_TOPIC_DOC = "The Kafka topic to write gRPC messages to";

    // TLS Configuration
    public static final String GRPC_TLS_ENABLED_CONFIG = "grpc.tls.enabled";
    private static final String GRPC_TLS_ENABLED_DOC = "Enable TLS for gRPC connection";

    public static final String GRPC_TLS_CA_CERT_CONFIG = "grpc.tls.ca.cert";
    private static final String GRPC_TLS_CA_CERT_DOC = "Path to CA certificate file for TLS verification";

    public static final String GRPC_TLS_CLIENT_CERT_CONFIG = "grpc.tls.client.cert";
    private static final String GRPC_TLS_CLIENT_CERT_DOC = "Path to client certificate file for mutual TLS";

    public static final String GRPC_TLS_CLIENT_KEY_CONFIG = "grpc.tls.client.key";
    private static final String GRPC_TLS_CLIENT_KEY_DOC = "Path to client private key file for mutual TLS";

    // Metadata/Headers Configuration
    public static final String GRPC_METADATA_PREFIX = "grpc.metadata.";
    public static final String GRPC_METADATA_CONFIG = "grpc.metadata";
    private static final String GRPC_METADATA_DOC = "Custom gRPC metadata/headers (format: key1:value1,key2:value2)";

    // Reconnection Configuration
    public static final String GRPC_RECONNECT_ENABLED_CONFIG = "grpc.reconnect.enabled";
    private static final String GRPC_RECONNECT_ENABLED_DOC = "Enable automatic reconnection on disconnect";

    public static final String GRPC_RECONNECT_INTERVAL_MS_CONFIG = "grpc.reconnect.interval.ms";
    private static final String GRPC_RECONNECT_INTERVAL_MS_DOC = "Interval between reconnection attempts in milliseconds";

    public static final String GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG = "grpc.reconnect.max.attempts";
    private static final String GRPC_RECONNECT_MAX_ATTEMPTS_DOC = "Maximum number of reconnection attempts (-1 for infinite)";

    public static final String GRPC_RECONNECT_BACKOFF_MAX_MS_CONFIG = "grpc.reconnect.backoff.max.ms";
    private static final String GRPC_RECONNECT_BACKOFF_MAX_MS_DOC = "Maximum backoff delay for exponential backoff in milliseconds";

    // Advanced Configuration
    public static final String MESSAGE_QUEUE_SIZE_CONFIG = "grpc.message.queue.size";
    private static final String MESSAGE_QUEUE_SIZE_DOC = "Maximum size of the message buffer queue";

    public static final String CONNECTION_TIMEOUT_MS_CONFIG = "grpc.connection.timeout.ms";
    private static final String CONNECTION_TIMEOUT_MS_DOC = "Connection timeout in milliseconds";

    public static final String GRPC_KEEPALIVE_TIME_MS_CONFIG = "grpc.keepalive.time.ms";
    private static final String GRPC_KEEPALIVE_TIME_MS_DOC = "Keepalive time in milliseconds (0 to disable)";

    public static final String GRPC_KEEPALIVE_TIMEOUT_MS_CONFIG = "grpc.keepalive.timeout.ms";
    private static final String GRPC_KEEPALIVE_TIMEOUT_MS_DOC = "Keepalive timeout in milliseconds";

    public static final String GRPC_MAX_INBOUND_MESSAGE_SIZE_CONFIG = "grpc.max.inbound.message.size";
    private static final String GRPC_MAX_INBOUND_MESSAGE_SIZE_DOC = "Maximum inbound message size in bytes";

    public static final ConfigDef CONFIG_DEF = createConfigDef();

    private static ConfigDef createConfigDef() {
        return new ConfigDef()
                // gRPC Server Configuration
                .define(
                        GRPC_SERVER_HOST_CONFIG,
                        Type.STRING,
                        ConfigDef.NO_DEFAULT_VALUE,
                        Importance.HIGH,
                        GRPC_SERVER_HOST_DOC
                )
                .define(
                        GRPC_SERVER_PORT_CONFIG,
                        Type.INT,
                        ConfigDef.NO_DEFAULT_VALUE,
                        Importance.HIGH,
                        GRPC_SERVER_PORT_DOC
                )
                .define(
                        GRPC_SERVICE_NAME_CONFIG,
                        Type.STRING,
                        ConfigDef.NO_DEFAULT_VALUE,
                        Importance.HIGH,
                        GRPC_SERVICE_NAME_DOC
                )
                .define(
                        GRPC_METHOD_NAME_CONFIG,
                        Type.STRING,
                        ConfigDef.NO_DEFAULT_VALUE,
                        Importance.HIGH,
                        GRPC_METHOD_NAME_DOC
                )
                // Request Configuration
                .define(
                        GRPC_REQUEST_MESSAGE_CONFIG,
                        Type.STRING,
                        "{}",
                        Importance.MEDIUM,
                        GRPC_REQUEST_MESSAGE_DOC
                )
                // Proto Descriptor
                .define(
                        GRPC_PROTO_DESCRIPTOR_CONFIG,
                        Type.STRING,
                        null,
                        Importance.MEDIUM,
                        GRPC_PROTO_DESCRIPTOR_DOC
                )
                // Kafka Configuration
                .define(
                        KAFKA_TOPIC_CONFIG,
                        Type.STRING,
                        ConfigDef.NO_DEFAULT_VALUE,
                        Importance.HIGH,
                        KAFKA_TOPIC_DOC
                )
                // TLS Configuration
                .define(
                        GRPC_TLS_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        false,
                        Importance.MEDIUM,
                        GRPC_TLS_ENABLED_DOC
                )
                .define(
                        GRPC_TLS_CA_CERT_CONFIG,
                        Type.STRING,
                        null,
                        Importance.MEDIUM,
                        GRPC_TLS_CA_CERT_DOC
                )
                .define(
                        GRPC_TLS_CLIENT_CERT_CONFIG,
                        Type.STRING,
                        null,
                        Importance.LOW,
                        GRPC_TLS_CLIENT_CERT_DOC
                )
                .define(
                        GRPC_TLS_CLIENT_KEY_CONFIG,
                        Type.STRING,
                        null,
                        Importance.LOW,
                        GRPC_TLS_CLIENT_KEY_DOC
                )
                // Metadata
                .define(
                        GRPC_METADATA_CONFIG,
                        Type.STRING,
                        null,
                        Importance.LOW,
                        GRPC_METADATA_DOC
                )
                // Reconnection
                .define(
                        GRPC_RECONNECT_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        true,
                        Importance.MEDIUM,
                        GRPC_RECONNECT_ENABLED_DOC
                )
                .define(
                        GRPC_RECONNECT_INTERVAL_MS_CONFIG,
                        Type.LONG,
                        5000L,
                        Importance.MEDIUM,
                        GRPC_RECONNECT_INTERVAL_MS_DOC
                )
                .define(
                        GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG,
                        Type.INT,
                        -1,
                        Importance.MEDIUM,
                        GRPC_RECONNECT_MAX_ATTEMPTS_DOC
                )
                .define(
                        GRPC_RECONNECT_BACKOFF_MAX_MS_CONFIG,
                        Type.LONG,
                        60000L,
                        Importance.MEDIUM,
                        GRPC_RECONNECT_BACKOFF_MAX_MS_DOC
                )
                // Advanced Configuration
                .define(
                        MESSAGE_QUEUE_SIZE_CONFIG,
                        Type.INT,
                        10000,
                        Importance.LOW,
                        MESSAGE_QUEUE_SIZE_DOC
                )
                .define(
                        CONNECTION_TIMEOUT_MS_CONFIG,
                        Type.LONG,
                        30000L,
                        Importance.LOW,
                        CONNECTION_TIMEOUT_MS_DOC
                )
                .define(
                        GRPC_KEEPALIVE_TIME_MS_CONFIG,
                        Type.LONG,
                        30000L,
                        Importance.LOW,
                        GRPC_KEEPALIVE_TIME_MS_DOC
                )
                .define(
                        GRPC_KEEPALIVE_TIMEOUT_MS_CONFIG,
                        Type.LONG,
                        10000L,
                        Importance.LOW,
                        GRPC_KEEPALIVE_TIMEOUT_MS_DOC
                )
                .define(
                        GRPC_MAX_INBOUND_MESSAGE_SIZE_CONFIG,
                        Type.INT,
                        4 * 1024 * 1024, // 4MB default
                        Importance.LOW,
                        GRPC_MAX_INBOUND_MESSAGE_SIZE_DOC
                );
    }

    public GrpcSourceConnectorConfig(Map<?, ?> originals) {
        super(CONFIG_DEF, originals);
    }

    // Getters
    public String getGrpcServerHost() {
        return getString(GRPC_SERVER_HOST_CONFIG);
    }

    public int getGrpcServerPort() {
        return getInt(GRPC_SERVER_PORT_CONFIG);
    }

    public String getGrpcServiceName() {
        return getString(GRPC_SERVICE_NAME_CONFIG);
    }

    public String getGrpcMethodName() {
        return getString(GRPC_METHOD_NAME_CONFIG);
    }

    public String getGrpcRequestMessage() {
        return getString(GRPC_REQUEST_MESSAGE_CONFIG);
    }

    public String getGrpcProtoDescriptor() {
        return getString(GRPC_PROTO_DESCRIPTOR_CONFIG);
    }

    public String getKafkaTopic() {
        return getString(KAFKA_TOPIC_CONFIG);
    }

    public boolean isTlsEnabled() {
        return getBoolean(GRPC_TLS_ENABLED_CONFIG);
    }

    public String getTlsCaCert() {
        return getString(GRPC_TLS_CA_CERT_CONFIG);
    }

    public String getTlsClientCert() {
        return getString(GRPC_TLS_CLIENT_CERT_CONFIG);
    }

    public String getTlsClientKey() {
        return getString(GRPC_TLS_CLIENT_KEY_CONFIG);
    }

    public String getMetadata() {
        return getString(GRPC_METADATA_CONFIG);
    }

    public boolean isReconnectEnabled() {
        return getBoolean(GRPC_RECONNECT_ENABLED_CONFIG);
    }

    public long getReconnectIntervalMs() {
        return getLong(GRPC_RECONNECT_INTERVAL_MS_CONFIG);
    }

    public int getMaxReconnectAttempts() {
        return getInt(GRPC_RECONNECT_MAX_ATTEMPTS_CONFIG);
    }

    public long getMaxBackoffMs() {
        return getLong(GRPC_RECONNECT_BACKOFF_MAX_MS_CONFIG);
    }

    public int getMessageQueueSize() {
        return getInt(MESSAGE_QUEUE_SIZE_CONFIG);
    }

    public long getConnectionTimeoutMs() {
        return getLong(CONNECTION_TIMEOUT_MS_CONFIG);
    }

    public long getKeepaliveTimeMs() {
        return getLong(GRPC_KEEPALIVE_TIME_MS_CONFIG);
    }

    public long getKeepaliveTimeoutMs() {
        return getLong(GRPC_KEEPALIVE_TIMEOUT_MS_CONFIG);
    }

    public int getMaxInboundMessageSize() {
        return getInt(GRPC_MAX_INBOUND_MESSAGE_SIZE_CONFIG);
    }

    /**
     * Parse metadata from configuration string.
     * Format: key1:value1,key2:value2
     */
    public Map<String, String> getMetadataMap() {
        Map<String, String> metadata = new HashMap<>();

        String metadataStr = getMetadata();
        if (metadataStr != null && !metadataStr.trim().isEmpty()) {
            String[] metadataPairs = metadataStr.split(",");
            for (String pair : metadataPairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    metadata.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }

        return metadata;
    }
}
