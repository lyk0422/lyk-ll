package com.example.starter.calibration.domain;

/**
 * 标定版本生命周期状态（题目要求的五种状态之外，新增 SUPERSEDED 表示转正后被替代的旧基线）。
 * <ul>
 *   <li>DRAFT 草稿：已创建未审批；</li>
 *   <li>APPROVED 已批准：通过审批，可全量生效或发起灰度；</li>
 *   <li>CANARY 灰度中：存在进行中的灰度发布；</li>
 *   <li>ACTIVE 已生效：作为基线版本参与解析；</li>
 *   <li>ROLLED_BACK 已回滚：灰度被回滚，不再参与解析；</li>
 *   <li>SUPERSEDED 已替代：灰度转正后，被新版本完全替代的旧基线，不再参与解析。</li>
 * </ul>
 */
public enum CalibrationStatus {
    DRAFT,
    APPROVED,
    CANARY,
    ACTIVE,
    ROLLED_BACK,
    SUPERSEDED
}
