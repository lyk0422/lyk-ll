package com.example.starter.cosmetic.child.domain;

/**
 * 儿童备案聚合状态。
 *
 * <p>流转：提交/补正后同步审查，通过则 IN_PUBLICITY（受理并公示），
 * 不通过则 REVIEW_REJECTED（零生效，可补正）；公示期满 EFFECTIVE。</p>
 */
public enum ChildFilingStatus {

    /** 审查不通过（零生效）：缺项已一次列全，可材料补正生成新修订。 */
    REVIEW_REJECTED,
    /** 公示中：最新修订已受理并冻结，公示只能使用该最新修订。 */
    IN_PUBLICITY,
    /** 生效：当前最新修订已生效。 */
    EFFECTIVE
}
