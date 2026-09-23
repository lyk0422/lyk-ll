package com.example.starter.token;

/**
 * 安全事件类型。
 */
public enum SecurityEventType {

    /** 检测到已消费令牌以不同请求键再次使用，触发全族撤销。每个族仅记录一条。 */
    REPLAY_DETECTED,

    /** 主动登出撤销整个令牌族。每个族仅记录一条。 */
    LOGOUT
}
