package com.example.starter.bag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * rosbag 风格存储业务配置。
 *
 * @param maxSize 每个逻辑分段允许的最大消息数
 */
@ConfigurationProperties(prefix = "bag.segment")
public record BagProperties(int maxSize) {

    public BagProperties {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("bag.segment.max-size 必须为正数");
        }
    }
}
