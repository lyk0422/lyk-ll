package com.example.starter.domain;

/**
 * 可进行保管方交接的实体类型。
 */
public enum EntityType {
    /**
     * 原始样本。
     */
    SPECIMEN,
    /**
     * 分装管。
     */
    ALIQUOT,
    /**
     * 检测申请。
     */
    RESERVATION
}
