package io.conduktor.connect.grpc;

import com.google.gson.JsonParser;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC client that handles connection, server streaming, reconnection, and message buffering.
 * Uses dynamic message handling with proto descriptors for flexibility.
 */
public class GrpcClient {
    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);
    private static final double QUEUE_WARNING_THRESHOLD = 0.80; // 80% threshold

    // Connection configuration
    private final String host;
    private final int port;
    private final String serviceName;
    private final String methodName;
    private final String requestMessageJson;
    private final String protoDescriptor;
    private final boolean tlsEnabled;
    private final String tlsCaCert;
    private final String tlsClientCert;
    private final String tlsClientKey;
    private final Map<String, String> metadata;

    // Reconnection configuration
    private final boolean reconnectEnabled;
    private final long reconnectIntervalMs;
    private final int maxReconnectAttempts;
    private final long maxBackoffMs;

    // Advanced configuration
    private final int queueSize;
    private final long connectionTimeoutMs;
    private final long keepaliveTimeMs;
    private final long keepaliveTimeoutMs;
    private final int maxInboundMessageSize;

    // gRPC components
    private ManagedChannel channel;
    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor;
    private Descriptor requestDescriptor;
    private Descriptor responseDescriptor;

    // Message queue and state
    private final LinkedBlockingDeque<String> messageQueue;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong reconnectAttempts = new AtomicLong(0);
    private volatile long lastMessageTimestamp = 0;
    private volatile boolean queueWarningLogged = false;
    private volatile StreamObserver<DynamicMessage> currentStream;

    // Executors
    private ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectTask;
    private GrpcMetrics metrics;

    public GrpcClient(
            String host,
            int port,
            String serviceName,
            String methodName,
            String requestMessageJson,
            String protoDescriptor,
            boolean tlsEnabled,
            String tlsCaCert,
            String tlsClientCert,
            String tlsClientKey,
            Map<String, String> metadata,
            boolean reconnectEnabled,
            long reconnectIntervalMs,
            int maxReconnectAttempts,
            long maxBackoffMs,
            int queueSize,
            long connectionTimeoutMs,
            long keepaliveTimeMs,
            long keepaliveTimeoutMs,
            int maxInboundMessageSize
    ) {
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.requestMessageJson = requestMessageJson;
        this.protoDescriptor = protoDescriptor;
        this.tlsEnabled = tlsEnabled;
        this.tlsCaCert = tlsCaCert;
        this.tlsClientCert = tlsClientCert;
        this.tlsClientKey = tlsClientKey;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.reconnectEnabled = reconnectEnabled;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.maxBackoffMs = maxBackoffMs;
        this.queueSize = queueSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.keepaliveTimeMs = keepaliveTimeMs;
        this.keepaliveTimeoutMs = keepaliveTimeoutMs;
        this.maxInboundMessageSize = maxInboundMessageSize;
        this.messageQueue = new LinkedBlockingDeque<>(queueSize);
    }

    /**
     * Start the gRPC connection.
     */
    public void start() {
        String serverAddress = host + ":" + port;
        MDC.put("grpc_server", serverAddress);
        log.info("event=grpc_client_starting host={} port={} service={} method={} queue_capacity={} tls_enabled={}",
                 host, port, serviceName, methodName, queueSize, tlsEnabled);

        // Initialize reconnect executor with a single thread
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "grpc-reconnect");
            thread.setDaemon(true);
            return thread;
        });

        if (metrics != null) {
            metrics.setQueueCapacity(queueSize);
        }

        // Load proto descriptors if provided
        if (protoDescriptor != null && !protoDescriptor.trim().isEmpty()) {
            try {
                loadProtoDescriptor();
                log.info("event=proto_descriptor_loaded descriptor={}", protoDescriptor);
            } catch (Exception e) {
                log.error("event=proto_descriptor_load_failed descriptor={} error={}", protoDescriptor, e.getMessage(), e);
                throw new RuntimeException("Failed to load proto descriptor: " + e.getMessage(), e);
            }
        } else {
            log.warn("event=no_proto_descriptor_provided using_generic_handling=true");
            // Note: Without proto descriptor, we'll use generic DynamicMessage handling
            // This requires the server to support reflection or we need to handle raw bytes
        }

        connect();
        log.info("event=grpc_client_started server={}", serverAddress);
        MDC.clear();
    }

    /**
     * Load proto descriptor from file or base64 string.
     */
    private void loadProtoDescriptor() throws Exception {
        FileDescriptorSet descriptorSet;

        // Check if it's a file path or base64 encoded
        if (new File(protoDescriptor).exists()) {
            // Load from file
            try (FileInputStream input = new FileInputStream(protoDescriptor)) {
                descriptorSet = FileDescriptorSet.parseFrom(input);
            }
        } else {
            // Try to decode as base64
            try {
                byte[] decodedBytes = Base64.getDecoder().decode(protoDescriptor);
                descriptorSet = FileDescriptorSet.parseFrom(decodedBytes);
            } catch (IllegalArgumentException e) {
                throw new Exception("Proto descriptor is neither a valid file path nor base64 encoded data", e);
            }
        }

        // Build file descriptors
        Map<String, FileDescriptor> fileDescriptorMap = new HashMap<>();
        for (DescriptorProtos.FileDescriptorProto fdProto : descriptorSet.getFileList()) {
            // Get dependencies
            List<FileDescriptor> dependencies = new ArrayList<>();
            for (String dep : fdProto.getDependencyList()) {
                FileDescriptor depDescriptor = fileDescriptorMap.get(dep);
                if (depDescriptor != null) {
                    dependencies.add(depDescriptor);
                }
            }

            FileDescriptor fileDescriptor = FileDescriptor.buildFrom(
                    fdProto,
                    dependencies.toArray(new FileDescriptor[0])
            );
            fileDescriptorMap.put(fileDescriptor.getName(), fileDescriptor);
        }

        // Find the service and method descriptors
        ServiceDescriptor serviceDescriptor = null;
        for (FileDescriptor fd : fileDescriptorMap.values()) {
            for (ServiceDescriptor sd : fd.getServices()) {
                if (sd.getFullName().equals(serviceName)) {
                    serviceDescriptor = sd;
                    break;
                }
            }
            if (serviceDescriptor != null) break;
        }

        if (serviceDescriptor == null) {
            throw new Exception("Service not found in descriptor: " + serviceName);
        }

        MethodDescriptor methodDesc = null;
        for (MethodDescriptor md : serviceDescriptor.getMethods()) {
            if (md.getName().equals(methodName)) {
                methodDesc = md;
                break;
            }
        }

        if (methodDesc == null) {
            throw new Exception("Method not found in service: " + methodName);
        }

        // Verify it's a server streaming method
        if (!methodDesc.isServerStreaming()) {
            log.warn("event=method_not_server_streaming method={} is_client_streaming={} is_server_streaming={}",
                    methodName, methodDesc.isClientStreaming(), methodDesc.isServerStreaming());
        }

        requestDescriptor = methodDesc.getInputType();
        responseDescriptor = methodDesc.getOutputType();

        // Create gRPC MethodDescriptor
        String fullMethodName = serviceName + "/" + methodName;
        this.grpcMethodDescriptor = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName(fullMethodName)
                .setRequestMarshaller(new DynamicMessageMarshaller(requestDescriptor))
                .setResponseMarshaller(new DynamicMessageMarshaller(responseDescriptor))
                .build();
    }

    /**
     * Connect to the gRPC server.
     */
    private void connect() {
        try {
            // Use InetSocketAddress directly to bypass NameResolver and avoid Unix socket issues
            // This ensures we always connect via TCP/IP instead of relying on NameResolver selection
            var socketAddress = new java.net.InetSocketAddress(host, port);
            NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(socketAddress);

            // Configure TLS or plaintext
            if (tlsEnabled) {
                SslContext sslContext = buildSslContext();
                channelBuilder.sslContext(sslContext);
            } else {
                channelBuilder.usePlaintext();
            }

            // Configure timeouts and keepalive
            channelBuilder
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .idleTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS);

            if (keepaliveTimeMs > 0) {
                channelBuilder
                        .keepAliveTime(keepaliveTimeMs, TimeUnit.MILLISECONDS)
                        .keepAliveTimeout(keepaliveTimeoutMs, TimeUnit.MILLISECONDS)
                        .keepAliveWithoutCalls(true);
            }

            channel = channelBuilder.build();

            // Start streaming
            startStreaming();

        } catch (Exception e) {
            log.error("event=connection_failed host={} port={} error={}", host, port, e.getMessage(), e);
            attemptReconnect();
        }
    }

    /**
     * Build SSL context for TLS.
     */
    private SslContext buildSslContext() throws Exception {
        var builder = GrpcSslContexts.forClient();

        if (tlsCaCert != null && !tlsCaCert.isEmpty()) {
            builder.trustManager(new File(tlsCaCert));
        }

        if (tlsClientCert != null && !tlsClientCert.isEmpty() &&
            tlsClientKey != null && !tlsClientKey.isEmpty()) {
            builder.keyManager(new File(tlsClientCert), new File(tlsClientKey));
        }

        return builder.build();
    }

    /**
     * Start the server streaming call.
     */
    private void startStreaming() {
        try {
            // Build request message
            DynamicMessage request = buildRequestMessage();

            // Build metadata
            Metadata grpcMetadata = new Metadata();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                Metadata.Key<String> key = Metadata.Key.of(entry.getKey(), Metadata.ASCII_STRING_MARSHALLER);
                grpcMetadata.put(key, entry.getValue());
            }

            // Create response observer
            StreamObserver<DynamicMessage> responseObserver = new StreamObserver<DynamicMessage>() {
                @Override
                public void onNext(DynamicMessage value) {
                    handleMessage(value);
                }

                @Override
                public void onError(Throwable t) {
                    handleError(t);
                }

                @Override
                public void onCompleted() {
                    handleCompleted();
                }
            };

            // Start the call
            if (grpcMethodDescriptor != null) {
                ClientCalls.asyncServerStreamingCall(
                        channel.newCall(grpcMethodDescriptor, CallOptions.DEFAULT),
                        request,
                        responseObserver
                );
            } else {
                // Fallback: use generic approach without proto descriptors
                log.warn("event=no_method_descriptor using_generic_call=true");
                // This would require server reflection or other mechanisms
                throw new IllegalStateException("Method descriptor not available - proto descriptor required");
            }

            currentStream = responseObserver;
            connected.set(true);
            if (metrics != null) {
                metrics.setConnected(true);
            }

            log.info("event=streaming_started service={} method={}", serviceName, methodName);

        } catch (Exception e) {
            log.error("event=streaming_start_failed error={}", e.getMessage(), e);
            attemptReconnect();
        }
    }

    /**
     * Build request message from JSON.
     */
    private DynamicMessage buildRequestMessage() throws Exception {
        if (requestDescriptor == null) {
            // Create empty message
            return DynamicMessage.getDefaultInstance(
                    DynamicMessage.newBuilder(
                            DescriptorProtos.DescriptorProto.getDescriptor()
                    ).getDescriptorForType()
            );
        }

        DynamicMessage.Builder builder = DynamicMessage.newBuilder(requestDescriptor);

        // Parse JSON and convert to DynamicMessage
        if (requestMessageJson != null && !requestMessageJson.trim().isEmpty() && !requestMessageJson.equals("{}")) {
            JsonFormat.parser().merge(requestMessageJson, builder);
        }

        return builder.build();
    }

    /**
     * Handle incoming message from stream.
     */
    private void handleMessage(DynamicMessage message) {
        messagesReceived.incrementAndGet();
        lastMessageTimestamp = System.currentTimeMillis();

        if (metrics != null) {
            metrics.incrementMessagesReceived();
        }

        try {
            // Convert DynamicMessage to JSON
            String json = JsonFormat.printer()
                    .includingDefaultValueFields()
                    .omittingInsignificantWhitespace()
                    .print(message);

            // Add to queue
            int currentSize = messageQueue.size();
            double utilization = (currentSize * 100.0) / queueSize;

            boolean added = messageQueue.offer(json);
            if (!added) {
                messagesDropped.incrementAndGet();
                if (metrics != null) {
                    metrics.incrementMessagesDropped();
                }
                MDC.put("grpc_server", host + ":" + port);
                log.warn("event=message_dropped reason=queue_full queue_size={} queue_capacity={} utilization_percent=100.0 messages_dropped_total={}",
                         queueSize, queueSize, messagesDropped.get());
                MDC.clear();
            } else {
                // Update metrics
                if (metrics != null) {
                    metrics.updateQueueSize(messageQueue.size());
                }

                // Log warning when queue is at 80% capacity
                if (utilization >= (QUEUE_WARNING_THRESHOLD * 100) && !queueWarningLogged) {
                    MDC.put("grpc_server", host + ":" + port);
                    log.warn("event=queue_high_utilization queue_size={} queue_capacity={} utilization_percent={} threshold_percent={}",
                             currentSize, queueSize, String.format("%.2f", utilization), (int)(QUEUE_WARNING_THRESHOLD * 100));
                    queueWarningLogged = true;
                    MDC.clear();
                } else if (utilization < (QUEUE_WARNING_THRESHOLD * 100)) {
                    queueWarningLogged = false;
                }
            }

            // Log metrics periodically
            if (log.isDebugEnabled() && messagesReceived.get() % 100 == 0) {
                MDC.put("grpc_server", host + ":" + port);
                log.debug("event=messages_received_milestone messages_received={} queue_size={} queue_utilization_percent={}",
                          messagesReceived.get(), messageQueue.size(), String.format("%.2f", utilization));
                MDC.clear();
            }
        } catch (Exception e) {
            log.error("event=message_conversion_failed error={}", e.getMessage(), e);
        }
    }

    /**
     * Handle stream error.
     */
    private void handleError(Throwable t) {
        MDC.put("grpc_server", host + ":" + port);
        Status status = Status.fromThrowable(t);
        log.error("event=stream_error status_code={} status_description={} error_message={}",
                  status.getCode(), status.getDescription(), t.getMessage(), t);
        connected.set(false);
        if (metrics != null) {
            metrics.setConnected(false);
        }
        MDC.clear();
        attemptReconnect();
    }

    /**
     * Handle stream completion.
     */
    private void handleCompleted() {
        MDC.put("grpc_server", host + ":" + port);
        log.info("event=stream_completed messages_received={}", messagesReceived.get());
        connected.set(false);
        if (metrics != null) {
            metrics.setConnected(false);
        }
        MDC.clear();
        attemptReconnect();
    }

    /**
     * Stop the gRPC connection.
     */
    public void stop() {
        String serverAddress = host + ":" + port;
        MDC.put("grpc_server", serverAddress);
        log.info("event=grpc_client_stopping server={}", serverAddress);
        shouldReconnect.set(false);

        // Cancel any pending reconnection task
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
            log.debug("event=reconnect_task_cancelled");
        }

        // Shutdown reconnect executor
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdown();
            try {
                if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("event=executor_shutdown_timeout executor=reconnect action=forcing_shutdown");
                    reconnectExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=executor_shutdown_interrupted executor=reconnect");
                reconnectExecutor.shutdownNow();
            }
        }

        // Shutdown channel
        if (channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("event=channel_shutdown_timeout action=forcing_shutdown");
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=channel_shutdown_interrupted");
                channel.shutdownNow();
            }
        }

        connected.set(false);
        log.info("event=grpc_client_stopped server={} messages_received={} messages_dropped={} reconnect_attempts={}",
                 serverAddress, messagesReceived.get(), messagesDropped.get(), reconnectAttempts.get());
        MDC.clear();
    }

    /**
     * Get available messages from the queue.
     */
    public List<String> getMessages() {
        List<String> messages = new ArrayList<>();
        messageQueue.drainTo(messages);
        return messages;
    }

    /**
     * Check if the client is connected.
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Get the number of messages received.
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    /**
     * Get the number of reconnection attempts.
     */
    public long getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    /**
     * Get the number of messages dropped.
     */
    public long getMessagesDropped() {
        return messagesDropped.get();
    }

    /**
     * Get the current queue size.
     */
    public int getQueueSize() {
        return messageQueue.size();
    }

    /**
     * Get the queue capacity.
     */
    public int getQueueCapacity() {
        return queueSize;
    }

    /**
     * Get the queue utilization as a percentage.
     */
    public double getQueueUtilization() {
        return (messageQueue.size() * 100.0) / queueSize;
    }

    /**
     * Get milliseconds since last message received.
     */
    public long getMillisSinceLastMessage() {
        if (lastMessageTimestamp == 0) return -1;
        return System.currentTimeMillis() - lastMessageTimestamp;
    }

    /**
     * Set the metrics tracker for this client.
     */
    public void setMetrics(GrpcMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Attempt to reconnect if enabled.
     * Uses exponential backoff and prevents concurrent reconnection attempts.
     */
    private void attemptReconnect() {
        if (!reconnectEnabled || !shouldReconnect.get()) {
            MDC.put("grpc_server", host + ":" + port);
            log.info("event=reconnect_skipped reason=disabled_or_shutdown");
            MDC.clear();
            return;
        }

        // Prevent concurrent reconnection attempts
        if (!reconnecting.compareAndSet(false, true)) {
            MDC.put("grpc_server", host + ":" + port);
            log.debug("event=reconnect_skipped reason=already_in_progress");
            MDC.clear();
            return;
        }

        long currentAttempt = reconnectAttempts.incrementAndGet();
        if (metrics != null) {
            metrics.incrementReconnects();
        }

        // Check max retry limit (-1 means infinite)
        if (maxReconnectAttempts > 0 && currentAttempt > maxReconnectAttempts) {
            MDC.put("grpc_server", host + ":" + port);
            log.error("event=reconnect_failed reason=max_attempts_reached max_attempts={} current_attempt={}",
                      maxReconnectAttempts, currentAttempt);
            MDC.clear();
            reconnecting.set(false);
            shouldReconnect.set(false);
            return;
        }

        // Calculate exponential backoff delay
        long backoffDelay = calculateBackoffDelay(currentAttempt);
        MDC.put("grpc_server", host + ":" + port);
        log.info("event=reconnect_scheduled attempt={} backoff_ms={}", currentAttempt, backoffDelay);
        MDC.clear();

        // Schedule reconnection on dedicated executor
        reconnectTask = reconnectExecutor.schedule(() -> {
            try {
                if (shouldReconnect.get()) {
                    MDC.put("grpc_server", host + ":" + port);
                    log.info("event=reconnect_executing attempt={}", currentAttempt);
                    MDC.clear();

                    // Shutdown old channel
                    if (channel != null) {
                        channel.shutdown();
                    }

                    connect();
                }
            } catch (Exception e) {
                MDC.put("grpc_server", host + ":" + port);
                log.error("event=reconnect_error attempt={} error_message={}", currentAttempt, e.getMessage(), e);
                MDC.clear();
            } finally {
                reconnecting.set(false);
            }
        }, backoffDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * Calculate exponential backoff delay with jitter.
     */
    private long calculateBackoffDelay(long attempt) {
        long exponentialDelay = reconnectIntervalMs * (long) Math.pow(2, attempt - 1);
        long cappedDelay = Math.min(exponentialDelay, maxBackoffMs);
        long jitter = (long) (cappedDelay * 0.25 * Math.random());
        return cappedDelay + jitter;
    }

    /**
     * Marshaller for DynamicMessage.
     */
    private static class DynamicMessageMarshaller implements io.grpc.MethodDescriptor.Marshaller<DynamicMessage> {
        private final Descriptor descriptor;

        public DynamicMessageMarshaller(Descriptor descriptor) {
            this.descriptor = descriptor;
        }

        public java.io.InputStream stream(DynamicMessage value) {
            return new java.io.ByteArrayInputStream(value.toByteArray());
        }

        public DynamicMessage parse(java.io.InputStream stream) {
            try {
                return DynamicMessage.parseFrom(descriptor, stream);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse DynamicMessage", e);
            }
        }
    }
}
