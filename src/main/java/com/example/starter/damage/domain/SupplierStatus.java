package com.example.starter.damage.domain;

/**
 * 合成供应商资格状态。
 */
public enum SupplierStatus {
    /** 资格有效 */
    ACTIVE,
    /** 已撤销资格（撤销时刻与开标竞争判断口径一致） */
    REVOKED
}
