package com.example.starter.cosmetic.domain;

/**
 * 包装标签印次台账状态。
 *
 * <p>ACTIVE=可领用/可销毁；DISABLED=备案实质变更生效或注销后原子禁用，
 * 禁用为终态，不允许复活；禁用印次的未领剩余仍可登记销毁。</p>
 */
public enum PrintRunStatus {

    /** 可领用。 */
    ACTIVE,
    /** 已禁用（终态）：剩余标签不得再领用，仅可登记销毁。 */
    DISABLED
}
