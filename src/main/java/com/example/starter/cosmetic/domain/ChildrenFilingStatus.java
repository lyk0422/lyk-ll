package com.example.starter.cosmetic.domain;

/**
 * 儿童备案生命周期状态。
 *
 * <p>流转：提交/补正审查通过进入 PUBLIC（公示中，仅最新修订）；公示期满 EFFECTIVE；
 * 材料补正使旧修订 REVISION_SUPERSEDED（待审批准失效，终态）；实质变更新版本生效后
 * 旧备案 SUPERSEDED（终态）。</p>
 */
public enum ChildrenFilingStatus {

    /** 公示中：受理已冻结报告清单、分龄规则版本及标识文本摘要；公示只使用最新修订。 */
    PUBLIC(false),
    /** 生效：该产品当前唯一有效的儿童备案。 */
    EFFECTIVE(false),
    /** 已被实质变更的新版本替代（终态）。 */
    SUPERSEDED(true),
    /** 旧修订待审批准失效（终态）：材料补正生成新修订后，旧修订不再参与公示。 */
    REVISION_SUPERSEDED(true);

    private final boolean terminal;

    ChildrenFilingStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 是否终态：终态备案不再发生状态迁移。 */
    public boolean isTerminal() {
        return terminal;
    }
}
