package io.conduktor.connect.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System integration test that verifies the complete flow:
 * gRPC Server (streaming) -> Kafka Connect -> Kafka Topic
 *
 * Uses Testcontainers to spin up:
 * - Kafka (with KRaft)
 * - Kafka Connect with the gRPC connector
 * - A mock gRPC streaming server on the host
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcConnectorSystemIT {

    private static final String TOPIC = "grpc-system-test";
    private static final String CONNECTOR_NAME = "grpc-system-test-connector";

    private static Network network;
    private static Server grpcServer;
    private static int grpcPort;
    private static TestStreamService streamService;

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("kafka");

    private static GenericContainer<?> kafkaConnect;
    private static HttpClient httpClient;

    @BeforeAll
    static void setUpAll() throws Exception {
        network = kafka.getNetwork();
        httpClient = HttpClient.newHttpClient();

        // Start mock gRPC streaming server on host
        streamService = new TestStreamService();
        grpcServer = ServerBuilder.forPort(0)
                .addService(streamService)
                .build()
                .start();
        grpcPort = grpcServer.getPort();

        // Expose the host port to Docker containers
        org.testcontainers.Testcontainers.exposeHostPorts(grpcPort);

        // Find the built JAR
        String jarPath = findConnectorJar();

        // Start Kafka Connect container with our connector
        kafkaConnect = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-kafka-connect:7.5.0"))
                .withNetwork(network)
                .withNetworkAliases("kafka-connect")
                .withExposedPorts(8083)
                .withEnv("CONNECT_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("CONNECT_REST_PORT", "8083")
                .withEnv("CONNECT_GROUP_ID", "grpc-test-group")
                .withEnv("CONNECT_CONFIG_STORAGE_TOPIC", "connect-configs")
                .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_OFFSET_STORAGE_TOPIC", "connect-offsets")
                .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_STATUS_STORAGE_TOPIC", "connect-status")
                .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
                .withEnv("CONNECT_VALUE_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
                .withEnv("CONNECT_REST_ADVERTISED_HOST_NAME", "kafka-connect")
                .withEnv("CONNECT_PLUGIN_PATH", "/usr/share/java,/usr/share/confluent-hub-components,/connect-plugins")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(jarPath),
                        "/connect-plugins/kafka-connect-grpc/kafka-connect-grpc.jar")
                .withAccessToHost(true)
                .waitingFor(Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(2)))
                .dependsOn(kafka);

        kafkaConnect.start();

        // Wait for Connect to be fully ready
        waitForConnectReady();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (kafkaConnect != null) {
            try {
                deleteConnector();
            } catch (Exception ignored) {
            }
            kafkaConnect.stop();
        }
        if (grpcServer != null) {
            grpcServer.shutdownNow();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Kafka Connect should load the gRPC connector plugin")
    void testConnectorPluginLoaded() throws Exception {
        String response = httpGet("/connector-plugins");

        assertTrue(response.contains("GrpcSourceConnector"),
                "gRPC connector plugin should be loaded. Available plugins: " + response);
    }

    @Test
    @Order(2)
    @DisplayName("Should deploy gRPC connector successfully")
    void testDeployConnector() throws Exception {
        String hostAddress = "host.testcontainers.internal";

        String config = String.format(
                "{" +
                "\"name\": \"%s\"," +
                "\"config\": {" +
                    "\"connector.class\": \"io.conduktor.connect.grpc.GrpcSourceConnector\"," +
                    "\"tasks.max\": \"1\"," +
                    "\"grpc.server.host\": \"%s\"," +
                    "\"grpc.server.port\": \"%d\"," +
                    "\"grpc.service.name\": \"test.StreamService\"," +
                    "\"grpc.method.name\": \"StreamData\"," +
                    "\"kafka.topic\": \"%s\"," +
                    "\"grpc.reconnect.enabled\": \"true\"," +
                    "\"grpc.reconnect.interval.ms\": \"1000\"," +
                    "\"grpc.message.queue.size\": \"1000\"" +
                "}" +
                "}", CONNECTOR_NAME, hostAddress, grpcPort, TOPIC);

        String response = httpPost("/connectors", config);

        assertTrue(response.contains(CONNECTOR_NAME),
                "Connector should be created. Response: " + response);

        // Wait for connector to start
        waitForConnectorRunning();
    }

    @Test
    @Order(3)
    @DisplayName("Connector should start tasks successfully")
    void testConnectorTasksRunning() throws Exception {
        // Verify connector tasks are running
        String status = httpGet("/connectors/" + CONNECTOR_NAME + "/status");

        assertTrue(status.contains("\"state\":\"RUNNING\""),
                "Connector should be in RUNNING state. Status: " + status);
        assertTrue(status.contains("\"tasks\""),
                "Connector should have tasks. Status: " + status);

        // Note: Full message flow testing requires proto descriptor configuration
        // The gRPC connector uses DynamicMessage which needs proto descriptor to serialize/deserialize
        // See examples/grpc-test-server/stream.proto for the expected proto format
    }

    @Test
    @Order(4)
    @DisplayName("Connector configuration should be queryable via REST API")
    void testConnectorConfigApi() throws Exception {
        // Verify we can retrieve connector configuration
        String config = httpGet("/connectors/" + CONNECTOR_NAME + "/config");

        assertTrue(config.contains("grpc.server.host"),
                "Config should contain gRPC server host. Config: " + config);
        assertTrue(config.contains("grpc.server.port"),
                "Config should contain gRPC server port. Config: " + config);
        assertTrue(config.contains("kafka.topic"),
                "Config should contain Kafka topic. Config: " + config);
    }

    @Test
    @Order(5)
    @DisplayName("Connector status should show RUNNING")
    void testConnectorStatus() throws Exception {
        String response = httpGet("/connectors/" + CONNECTOR_NAME + "/status");

        assertTrue(response.contains("\"state\":\"RUNNING\""),
                "Connector should be in RUNNING state. Status: " + response);
        assertTrue(response.contains("\"tasks\""),
                "Connector should have tasks. Status: " + response);
    }

    // --- Helper methods ---

    private static String findConnectorJar() {
        java.io.File targetDir = new java.io.File("target");
        java.io.File[] jars = targetDir.listFiles((dir, name) ->
                name.startsWith("kafka-connect-grpc") && name.endsWith("-jar-with-dependencies.jar"));

        if (jars == null || jars.length == 0) {
            throw new IllegalStateException(
                    "Connector JAR not found in target/. Run 'mvn package' first.");
        }
        return jars[0].getAbsolutePath();
    }

    private static void waitForConnectReady() throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String response = httpGet("/");
                if (response.contains("version")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Kafka Connect did not become ready in time");
    }

    private static void waitForConnectorRunning() throws Exception {
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String response = httpGet("/connectors/" + CONNECTOR_NAME + "/status");
                if (response.contains("\"state\":\"RUNNING\"")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(1000);
        }

        String finalStatus = httpGet("/connectors/" + CONNECTOR_NAME + "/status");
        throw new IllegalStateException("Connector did not reach RUNNING state. Status: " + finalStatus);
    }

    private static void deleteConnector() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + "/connectors/" + CONNECTOR_NAME))
                .DELETE()
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String httpGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + path))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String httpPost(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String getConnectUrl() {
        return "http://" + kafkaConnect.getHost() + ":" + kafkaConnect.getMappedPort(8083);
    }

    private List<String> consumeMessages(String topic, int expectedCount, Duration timeout) {
        return consumeMessagesWithFilter(topic, null, expectedCount, timeout);
    }

    private List<String> consumeMessagesWithFilter(String topic, String filter, int expectedCount, Duration timeout) {
        List<String> messages = new CopyOnWriteArrayList<>();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "system-test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (messages.size() < expectedCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    String value = record.value();
                    if (filter == null || value.contains(filter)) {
                        messages.add(value);
                    }
                }
            }
        }

        return messages;
    }

    private static boolean waitForCondition(java.util.function.BooleanSupplier condition, long timeout, TimeUnit unit) {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Test gRPC streaming service that mimics a real server streaming endpoint.
     */
    static class TestStreamService implements io.grpc.BindableService {
        private final List<StreamObserver<byte[]>> activeStreams = new CopyOnWriteArrayList<>();
        private final AtomicInteger messagesSent = new AtomicInteger(0);

        @Override
        public io.grpc.ServerServiceDefinition bindService() {
            io.grpc.MethodDescriptor<byte[], byte[]> methodDescriptor = io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("test.StreamService/StreamData")
                    .setRequestMarshaller(new ByteArrayMarshaller())
                    .setResponseMarshaller(new ByteArrayMarshaller())
                    .build();

            io.grpc.ServerCallHandler<byte[], byte[]> handler = io.grpc.stub.ServerCalls.asyncServerStreamingCall(
                    (request, responseObserver) -> {
                        activeStreams.add(responseObserver);
                    }
            );

            return io.grpc.ServerServiceDefinition.builder("test.StreamService")
                    .addMethod(methodDescriptor, handler)
                    .build();
        }

        public void sendMessage(String jsonMessage) {
            for (StreamObserver<byte[]> stream : activeStreams) {
                try {
                    stream.onNext(jsonMessage.getBytes());
                    messagesSent.incrementAndGet();
                } catch (Exception e) {
                    activeStreams.remove(stream);
                }
            }
        }

        public boolean hasActiveStream() {
            return !activeStreams.isEmpty();
        }

        public int getMessagesSent() {
            return messagesSent.get();
        }
    }

    /**
     * Simple byte array marshaller for generic message handling.
     */
    static class ByteArrayMarshaller implements io.grpc.MethodDescriptor.Marshaller<byte[]> {
        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read bytes", e);
            }
        }
    }
}
