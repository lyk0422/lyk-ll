package com.example.starter.damage.domain;

/**
 * 供应商资格版本状态。开标时仅按开标时刻处于有效区间的 ACTIVE 版本认可资格；
 * 撤销以追加 REVOKED 新版本实现，历史资格版本保留作为中标证据。
 */
public enum QualificationStatus {
    /** 有效：在 [validFrom, validTo) 区间内（validTo 为 null 表示长期有效） */
    ACTIVE,
    /** 已撤销：自 validFrom 时刻起失效 */
    REVOKED
}
