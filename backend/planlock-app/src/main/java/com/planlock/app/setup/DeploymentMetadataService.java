package com.planlock.app.setup;

import com.planlock.common.DeploymentMetadataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 个人后端只读实例元数据提供者：直接只读查询 {@code deployment_instance}，不依赖 {@code AuthService} 以确保 Spring Bean 依赖单向无环。
 */
@Component
public class DeploymentMetadataService implements DeploymentMetadataProvider {

    private final JdbcTemplate jdbc;
    private final boolean deploymentEnabled;
    private final String version;

    public DeploymentMetadataService(
            JdbcTemplate jdbc,
            @Value("${planlock.deployment.enabled:false}") boolean deploymentEnabled,
            @Value("${planlock.version:2.2.0}") String version) {
        this.jdbc = jdbc;
        this.deploymentEnabled = deploymentEnabled;
        this.version = (version == null || version.isBlank()) ? "2.2.0" : version;
    }

    @Override
    public DeploymentMetadata getMetadata() {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT initialized, instance_label FROM deployment_instance WHERE id = 1");
            boolean initialized = toBoolean(row.get("initialized"));
            Object rawLabel = row.get("instance_label");
            String label = rawLabel instanceof String text && !text.isBlank() ? text : null;
            return new DeploymentMetadata(label, deploymentEnabled, initialized, version);
        } catch (Exception ignored) {
            // Never expose JDBC or persistence diagnostics through metadata snapshots.
            return new DeploymentMetadata(null, deploymentEnabled, false, version);
        }
    }

    private static boolean toBoolean(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }
}
