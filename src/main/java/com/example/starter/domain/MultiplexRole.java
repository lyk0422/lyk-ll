package com.example.starter.domain;

/**
 * 信号在多路复用报文中的角色。
 */
public enum MultiplexRole {
    /** 普通信号：每帧都参与解码。 */
    NONE,
    /** 多路选择器：其原始值决定本帧激活哪个被选择信号分支。 */
    MULTIPLEXOR,
    /** 被选择信号：仅当选择器原始值等于 multiplexValue 时参与解码。 */
    MULTIPLEXED
}
