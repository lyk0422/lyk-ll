package com.example.starter.pointcloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 点云本地数据目录配置。
 *
 * @param dataDir 块文件、隔离块与审计日志的根目录
 */
@ConfigurationProperties(prefix = "pointcloud")
public record PointCloudProperties(String dataDir) {
}
