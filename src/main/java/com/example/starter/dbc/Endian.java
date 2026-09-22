package com.example.starter.dbc;

/**
 * 信号字节序。LITTLE 对应 DBC 的 @1（Intel），BIG 对应 @0（Motorola）。
 */
public enum Endian {
    LITTLE,
    BIG
}
