package com.example.starter.domain;

/**
 * 潜次事件类型。
 */
public final class EventTypes {

    /** 观察期出现症状，启动加压舱预案 */
    public static final String CHAMBER_PROTOCOL = "CHAMBER_PROTOCOL";
    /** 结案后迟到的体征数据，只记迟到事件 */
    public static final String LATE_VITALS = "LATE_VITALS";
    /** 减压方案变更，旧方案与其记录被冻结 */
    public static final String PLAN_FROZEN = "PLAN_FROZEN";
    /** 受控出水原子结算两人积分账并释放潜水员占用槽 */
    public static final String SCORE_SETTLED = "SCORE_SETTLED";
    /** 已关闭潜次的历史水下记录更正，独立差异不追改原记录 */
    public static final String RECORD_CORRECTION = "RECORD_CORRECTION";

    private EventTypes() {
    }
}
