package com.example.starter.domain;

/**
 * Topic 离线保留策略。
 * <ul>
 *   <li>{@link #DROP}：离线时直接丢弃；</li>
 *   <li>{@link #LATEST}：每个 Topic 只保留最新一条；</li>
 *   <li>{@link #FIFO}：完整顺序保留；</li>
 *   <li>{@link #BOUNDED}：最多保留 capacity 条。</li>
 * </ul>
 */
public enum PolicyType {
    DROP,
    LATEST,
    FIFO,
    BOUNDED
}
