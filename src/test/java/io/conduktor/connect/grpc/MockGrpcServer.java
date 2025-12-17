package io.conduktor.connect.grpc;

import io.grpc.*;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock gRPC server for testing without external dependencies.
 * Addresses SME review finding: "CRITICAL: No real gRPC integration testing"
 *
 * Features:
 * - Controllable message streaming
 * - Configurable connection behavior (success, failure, delay)
 * - Request verification and assertion helpers
 * - In-process transport for fast, deterministic testing
 */
public class MockGrpcServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MockGrpcServer.class);

    private final Server server;
    private final String serverName;
    private final MockStreamingService service;
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Create a mock gRPC server listening on an in-process transport
     */
    public MockGrpcServer(String serverName, MockStreamingService service) throws IOException {
        this.serverName = serverName;
        this.service = service;
        this.server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build();
    }

    /**
     * Start the server
     */
    public void start() throws IOException {
        server.start();
        started.set(true);
        log.info("Mock gRPC server started: {}", serverName);
    }

    /**
     * Get the server name for in-process connection
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Get the host for connection (returns "localhost" for external-style config)
     */
    public String getHost() {
        return "localhost";
    }

    /**
     * Get a mock port (for test configurations that need a port number)
     */
    public int getPort() {
        return 50051; // Fake port for config purposes
    }

    /**
     * Queue a message to be streamed to connected clients
     */
    public void sendMessage(String jsonMessage) {
        service.queueMessage(jsonMessage);
    }

    /**
     * Queue multiple messages
     */
    public void sendMessages(String... messages) {
        for (String message : messages) {
            sendMessage(message);
        }
    }

    /**
     * Complete the stream (signals end of streaming)
     */
    public void completeStream() {
        service.completeStream();
    }

    /**
     * Send an error to the stream
     */
    public void sendError(Status status) {
        service.sendError(status);
    }

    /**
     * Check if there's an active client connection
     */
    public boolean hasActiveConnection() {
        return service.hasActiveConnection();
    }

    /**
     * Get the number of active connections
     */
    public int getActiveConnectionCount() {
        return service.getActiveConnectionCount();
    }

    /**
     * Get received request messages from clients
     */
    public List<String> getReceivedRequests() {
        return service.getReceivedRequests();
    }

    /**
     * Wait for a request to be received
     */
    public String waitForRequest(long timeout, TimeUnit unit) throws InterruptedException {
        return service.waitForRequest(timeout, unit);
    }

    /**
     * Clear received requests
     */
    public void clearReceivedRequests() {
        service.clearReceivedRequests();
    }

    /**
     * Get the number of messages sent
     */
    public int getMessagesSentCount() {
        return service.getMessagesSentCount();
    }

    /**
     * Check if server is started
     */
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void close() {
        service.shutdownStreams();
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        started.set(false);
        log.info("Mock gRPC server shut down: {}", serverName);
    }

    /**
     * Builder for fluent configuration
     */
    public static class Builder {
        private String serverName;
        private int initialMessageDelay = 0;
        private boolean autoSendOnConnect = false;
        private final List<String> initialMessages = new ArrayList<>();
        private boolean failOnConnect = false;
        private Status failStatus = Status.UNAVAILABLE;

        public Builder serverName(String name) {
            this.serverName = name;
            return this;
        }

        public Builder initialMessageDelay(int delayMs) {
            this.initialMessageDelay = delayMs;
            return this;
        }

        public Builder autoSendOnConnect() {
            this.autoSendOnConnect = true;
            return this;
        }

        public Builder sendOnConnect(String... messages) {
            for (String msg : messages) {
                initialMessages.add(msg);
            }
            return this;
        }

        public Builder failOnConnect(Status status) {
            this.failOnConnect = true;
            this.failStatus = status;
            return this;
        }

        public MockGrpcServer build() throws IOException {
            if (serverName == null) {
                serverName = "mock-grpc-server-" + System.nanoTime();
            }

            MockStreamingService service = new MockStreamingService(
                    initialMessageDelay,
                    autoSendOnConnect,
                    initialMessages,
                    failOnConnect,
                    failStatus
            );

            MockGrpcServer server = new MockGrpcServer(serverName, service);
            server.start();
            return server;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mock streaming service implementation
     */
    public static class MockStreamingService implements BindableService {
        private final int initialMessageDelay;
        private final boolean autoSendOnConnect;
        private final List<String> initialMessages;
        private final boolean failOnConnect;
        private final Status failStatus;

        private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> receivedRequests = new LinkedBlockingQueue<>();
        private final List<StreamObserver<byte[]>> activeStreams = new ArrayList<>();
        private final AtomicInteger connectionCount = new AtomicInteger(0);
        private final AtomicInteger messagesSent = new AtomicInteger(0);
        private final AtomicBoolean streamCompleted = new AtomicBoolean(false);
        private volatile Status errorToSend = null;

        public MockStreamingService(
                int initialMessageDelay,
                boolean autoSendOnConnect,
                List<String> initialMessages,
                boolean failOnConnect,
                Status failStatus
        ) {
            this.initialMessageDelay = initialMessageDelay;
            this.autoSendOnConnect = autoSendOnConnect;
            this.initialMessages = new ArrayList<>(initialMessages);
            this.failOnConnect = failOnConnect;
            this.failStatus = failStatus;
        }

        @Override
        public ServerServiceDefinition bindService() {
            // Create a generic server streaming method that accepts any service/method name
            ServerCallHandler<byte[], byte[]> handler = ServerCalls.asyncServerStreamingCall(
                    (request, responseObserver) -> handleStreamRequest(request, responseObserver)
            );

            // Use a wildcard service definition
            MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("test.StreamService/StreamData")
                    .setRequestMarshaller(new ByteArrayMarshaller())
                    .setResponseMarshaller(new ByteArrayMarshaller())
                    .build();

            return ServerServiceDefinition.builder("test.StreamService")
                    .addMethod(methodDescriptor, handler)
                    .build();
        }

        private void handleStreamRequest(byte[] request, StreamObserver<byte[]> responseObserver) {
            connectionCount.incrementAndGet();

            // Store the request
            String requestStr = new String(request);
            receivedRequests.offer(requestStr);
            log.info("Received streaming request: {}", requestStr);

            // Check if we should fail
            if (failOnConnect) {
                responseObserver.onError(failStatus.asRuntimeException());
                return;
            }

            // Add to active streams
            synchronized (activeStreams) {
                activeStreams.add(responseObserver);
            }

            // Apply initial delay if configured
            if (initialMessageDelay > 0) {
                try {
                    Thread.sleep(initialMessageDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Send initial messages if configured
            for (String msg : initialMessages) {
                sendToStream(responseObserver, msg);
            }

            // Start message delivery thread
            Thread deliveryThread = new Thread(() -> {
                try {
                    while (!streamCompleted.get() && errorToSend == null) {
                        String message = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (message != null) {
                            sendToStream(responseObserver, message);
                        }
                    }

                    if (errorToSend != null) {
                        responseObserver.onError(errorToSend.asRuntimeException());
                    } else if (streamCompleted.get()) {
                        responseObserver.onCompleted();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (activeStreams) {
                        activeStreams.remove(responseObserver);
                    }
                }
            }, "mock-grpc-delivery");
            deliveryThread.setDaemon(true);
            deliveryThread.start();
        }

        private void sendToStream(StreamObserver<byte[]> observer, String message) {
            try {
                observer.onNext(message.getBytes());
                messagesSent.incrementAndGet();
                log.debug("Sent message to stream: {}", message);
            } catch (Exception e) {
                log.warn("Failed to send message: {}", e.getMessage());
            }
        }

        public void queueMessage(String message) {
            messageQueue.offer(message);
        }

        public void completeStream() {
            streamCompleted.set(true);
        }

        public void sendError(Status status) {
            this.errorToSend = status;
        }

        public boolean hasActiveConnection() {
            synchronized (activeStreams) {
                return !activeStreams.isEmpty();
            }
        }

        public int getActiveConnectionCount() {
            synchronized (activeStreams) {
                return activeStreams.size();
            }
        }

        public List<String> getReceivedRequests() {
            return new ArrayList<>(receivedRequests);
        }

        public String waitForRequest(long timeout, TimeUnit unit) throws InterruptedException {
            return receivedRequests.poll(timeout, unit);
        }

        public void clearReceivedRequests() {
            receivedRequests.clear();
        }

        public int getMessagesSentCount() {
            return messagesSent.get();
        }

        public void shutdownStreams() {
            streamCompleted.set(true);
            synchronized (activeStreams) {
                for (StreamObserver<byte[]> stream : activeStreams) {
                    try {
                        stream.onCompleted();
                    } catch (Exception e) {
                        // Ignore errors during shutdown
                    }
                }
                activeStreams.clear();
            }
        }
    }

    /**
     * Simple byte array marshaller for generic message handling
     */
    private static class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
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
