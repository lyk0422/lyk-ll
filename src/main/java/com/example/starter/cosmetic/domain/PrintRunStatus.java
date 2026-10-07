package com.example.starter.cosmetic.domain;

/**
 * 包装标签印次状态。
 *
 * <p>ACTIVE=可领用；DISABLED=已禁用（备案换版/注销后旧印次剩余标签不可再领，
 * 禁用为终态，不允许复活；未领剩余仍可登记销毁）。</p>
 */
public enum PrintRunStatus {
    ACTIVE,
    DISABLED
}
