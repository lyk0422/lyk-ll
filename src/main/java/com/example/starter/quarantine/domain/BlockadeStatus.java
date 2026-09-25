package com.example.starter.quarantine.domain;

/**
 * 疫点封锁状态。
 */
public enum BlockadeStatus {

    /** 封锁生效中；发布即即刻生效。 */
    ACTIVE,
    /** 解除封锁（本题不涉及解除流程，预留）。 */
    RELEASED
}
