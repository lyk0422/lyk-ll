package com.example.starter.dbc;

/**
 * 信号多路复用角色。
 * NONE：普通信号；MULTIPLEXOR：多路选择信号（DBC 中的 M）；MUXED：按选择值条件生效的信号（DBC 中的 mN）。
 */
public enum MultiplexMode {
    NONE,
    MULTIPLEXOR,
    MUXED
}
