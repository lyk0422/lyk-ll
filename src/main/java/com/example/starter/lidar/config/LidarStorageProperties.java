package com.example.starter.lidar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 点云服务本地存储配置。
 *
 * @param rootDir 块文件、临时文件与隔离目录的根目录
 */
@ConfigurationProperties(prefix = "lidar.storage")
public record LidarStorageProperties(Path rootDir) {
}
