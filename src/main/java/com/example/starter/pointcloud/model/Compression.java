package com.example.starter.pointcloud.model;

/**
 * 块级压缩方式。
 */
public enum Compression {

    /** 不压缩。 */
    NONE,

    /** GZIP 压缩；若压缩失败或压缩结果不小于原始数据则回退为 {@link #NONE}。 */
    GZIP
}
