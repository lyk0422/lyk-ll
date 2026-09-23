package com.example.starter.forwarder;

/**
 * ACK 处理结果。重复 ACK 不得改变计数。
 */
public enum AckOutcome {
    /** 首次确认，消息不再转发。 */
    ACKED,
    /** 重复 ACK，状态与计数不变。 */
    DUPLICATE,
    /** 未找到对应消息。 */
    NOT_FOUND
}
