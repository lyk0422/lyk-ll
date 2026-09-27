package com.example.starter.damage.domain;

/**
 * 报价冻结状态。开标前只有 CURRENT/SUPERSEDED；开标后冻结为 FROZEN_VALID/FROZEN_EXCLUDED。
 */
public enum BidStatus {
    /** 当前有效报价（一供应商一询价版本仅一个） */
    CURRENT,
    /** 已被修订取代，历史保留 */
    SUPERSEDED,
    /** 开标冻结且资格有效，参与比价 */
    FROZEN_VALID,
    /** 开标冻结但按开标时刻资格判断被排除 */
    FROZEN_EXCLUDED
}
