package com.example.starter.lidar.model;

/**
 * 块级压缩方式。
 */
public enum Compression {
    /** 不压缩。 */
    NONE,
    /** GZIP 压缩；若压缩失败或压缩后不小于原数据，写入时回退为 {@link #NONE}。 */
    GZIP
}
