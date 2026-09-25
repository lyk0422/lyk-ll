package com.example.starter.domain;

/**
 * 幂等请求的业务类型，用于指纹分区与排查。
 */
public enum RequestType {
    /** 温度数据上报 */
    TEMPERATURE,
    /** 调节令（含初调节与再调节步进） */
    ADJUST,
    /** 失衡判定 / 效果评估 */
    EVALUATE,
    /** 评估归档 */
    ARCHIVE
}
