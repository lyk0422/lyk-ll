package com.example.starter.forwarder;

/**
 * Topic 离线缓存策略。
 * DROP：离线时直接丢弃；LATEST：只保留最新一条；
 * FIFO：完整顺序保留；BOUNDED：最多保留 bound 条。
 */
public enum PolicyType {
    DROP,
    LATEST,
    FIFO,
    BOUNDED
}
