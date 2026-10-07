package com.example.starter.domain;

/**
 * 冷链运输台账当前版本温证状态。
 * QUALIFIED：当前版本证据完整合格并冻结；UNQUALIFIED：当前版本存在缺口或越界点，温证无效。
 */
public enum ColdChainStatus {
    /** 当前版本证据合格，温证冻结有效。 */
    QUALIFIED,
    /** 当前版本证据存在缺口或越界点，温证无效，须补录更正后重核。 */
    UNQUALIFIED
}
