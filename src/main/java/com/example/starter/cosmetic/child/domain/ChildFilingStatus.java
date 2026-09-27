package com.example.starter.cosmetic.child.domain;

/**
 * 儿童备案生命周期状态。
 *
 * <p>流转：提交(SUBMITTED，同步完成形式审查，实际不停留)→通过进公示(PUBLIC)，
 * 不通过 FORM_REJECTED；公示期满生效(EFFECTIVE)；材料补正生成新修订并使旧修订
 * 失效(VOID)；实质变更新版本生效后旧有效版本 SUPERSEDED。</p>
 */
public enum ChildFilingStatus {

    /** 已提交，形式审查尚未完成（中间态，提交时同步完成形式审查）。 */
    SUBMITTED(false),
    /** 形式审查驳回：分龄资料任一缺项一次列全，零生效，可在补正后生成新修订。 */
    FORM_REJECTED(true),
    /** 公示中：公示期为起算日起 10 个自然日，第 10 日 24:00(UTC) 期满；仅最新修订可公示。 */
    PUBLIC(false),
    /** 生效：该产品当前唯一有效的儿童备案。 */
    EFFECTIVE(false),
    /** 旧有效版本被实质变更新版本替代（同事务）。 */
    SUPERSEDED(true),
    /** 旧修订因材料补正生成新修订而失效（待审/被驳回修订原子置为失效）。 */
    VOID(true);

    private final boolean terminal;

    ChildFilingStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 是否终态：终态修订不再发生状态迁移。 */
    public boolean isTerminal() {
        return terminal;
    }
}
