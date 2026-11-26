package com.planlock.common;

/**
 * 个人后端实例只读元数据提供者 SPI。
 * <p>
 * 由 {@code planlock-app} 实现并提供实例标签、部署模式与版本号，由 {@code planlock-account}
 * 可选消费，保持 {@code app -> account -> common} 单向无环依赖。
 */
public interface DeploymentMetadataProvider {

    /**
     * 返回当前实例非敏感元信息快照；实现方应吞掉底层持久层异常并安全降级，不泄露诊断详情。
     */
    DeploymentMetadata getMetadata();

    record DeploymentMetadata(
            String instanceLabel,
            boolean deploymentEnabled,
            boolean initialized,
            Long ownerUserId,
            String version
    ) {
        public DeploymentMetadata(
                String instanceLabel,
                boolean deploymentEnabled,
                boolean initialized,
                String version) {
            this(instanceLabel, deploymentEnabled, initialized, null, version);
        }
    }
}
