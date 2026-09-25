package com.example.starter.ventilation;

/**
 * 幂等请求类型。
 */
public enum RequestType {
    /** 火灾模式激活 */
    ACTIVATE,
    /** 火警解除复位 */
    RESET,
    /** 模式归档 */
    ARCHIVE,
    /** 设备到位反馈 */
    FEEDBACK
}
