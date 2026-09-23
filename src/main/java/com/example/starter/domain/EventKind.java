package com.example.starter.domain;

/**
 * 保管链事件类型。
 */
public enum EventKind {
    /**
     * 原始样本入库并确定首任保管人。
     */
    INTAKE,
    /**
     * 分装管或检测申请创建并确定首任保管人。
     */
    CREATE,
    /**
     * 保管方交接。
     */
    TRANSFER,
    /**
     * 污染隔离，实体保管方转入隔离保管人。
     */
    QUARANTINE
}
