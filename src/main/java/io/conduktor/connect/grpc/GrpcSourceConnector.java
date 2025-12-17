package io.conduktor.connect.grpc;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * gRPC Source Connector for Kafka Connect.
 * Streams data from gRPC server streaming endpoints into Kafka topics.
 */
public class GrpcSourceConnector extends SourceConnector {
    private static final Logger log = LoggerFactory.getLogger(GrpcSourceConnector.class);

    private Map<String, String> configProperties;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        log.info("Starting gRPC Source Connector");
        this.configProperties = props;

        // Validate configuration
        GrpcSourceConnectorConfig config = new GrpcSourceConnectorConfig(props);
        log.info("Connector configured for gRPC server: {}:{}", config.getGrpcServerHost(), config.getGrpcServerPort());
        log.info("Target service.method: {}.{}", config.getGrpcServiceName(), config.getGrpcMethodName());
        log.info("Target Kafka topic: {}", config.getKafkaTopic());
    }

    @Override
    public Class<? extends Task> taskClass() {
        return GrpcSourceTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        log.info("Creating task configurations for {} tasks", maxTasks);

        // gRPC streaming connections are single-threaded, so we only create one task
        List<Map<String, String>> taskConfigs = new ArrayList<>(1);
        taskConfigs.add(configProperties);

        return taskConfigs;
    }

    @Override
    public void stop() {
        log.info("Stopping gRPC Source Connector");
    }

    @Override
    public ConfigDef config() {
        return GrpcSourceConnectorConfig.CONFIG_DEF;
    }
}
