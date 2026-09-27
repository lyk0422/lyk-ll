package com.example.starter.cosmetic.domain;

/**
 * 化妆品备案生命周期状态。
 *
 * <p>流转：备案提交(SUBMITTED)→形式审查(通过则进入 PUBLIC，不通过 FORM_REJECTED)
 * →公示(PUBLIC)→生效(EFFECTIVE)；公示期内收到异议转 OBJECTION_REVIEW；
 * 生效后可变更(旧版本 SUPERSEDED)或注销(WITHDRAWN，终态)；
 * 异议成立则 REJECTED_AFTER_OBJECTION(终态)。</p>
 */
public enum FilingStatus {

    /** 已提交，形式审查尚未通过（中间态，提交时同步完成形式审查，实际不会持久停留）。 */
    SUBMITTED(false),
    /** 形式审查驳回（终态）：资料/功效/报告/浓度等规则不满足。 */
    FORM_REJECTED(true),
    /** 公示中：公示期为起算日起 10 个自然日，第 10 日 24:00(UTC) 期满。 */
    PUBLIC(false),
    /** 异议审查中：公示期内收到异议后的唯一分支状态。 */
    OBJECTION_REVIEW(false),
    /** 生效：该产品当前唯一有效的备案。 */
    EFFECTIVE(false),
    /** 已被新版本替代（实质变更新版本生效、简易变更生效，或儿童材料补正生成新修订时旧修订待审批准失效）。 */
    SUPERSEDED(true),
    /** 已注销（终态）：重复注销幂等成功，注销后新宣称审查一律拒绝。 */
    WITHDRAWN(true),
    /** 异议经审查成立，备案驳回（终态）。 */
    REJECTED_AFTER_OBJECTION(true);

    private final boolean terminal;

    FilingStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 是否终态：终态备案不再发生状态迁移。 */
    public boolean isTerminal() {
        return terminal;
    }
}
