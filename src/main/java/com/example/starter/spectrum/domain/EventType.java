package com.example.starter.spectrum.domain;

/**
 * 租约领域事件类型。
 */
public enum EventType {

    /** 创建。 */
    CREATED,

    /** 修改（产生新版本）。 */
    MODIFIED,

    /** 取消（只改变未来有效性）。 */
    CANCELLED
}
