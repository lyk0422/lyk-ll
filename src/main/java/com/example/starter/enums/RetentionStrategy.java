package com.example.starter.enums;

/**
 * Topic 离线保留策略。
 */
public enum RetentionStrategy {
    /** 离线时新消息直接丢弃。 */
    DROP,
    /** 离线时只保留最新一条，旧消息被覆盖。 */
    LATEST,
    /** 离线时完整顺序保留。 */
    FIFO,
    /** 离线时最多保留 bound 条，超出后驱逐最旧消息。 */
    BOUNDED
}
