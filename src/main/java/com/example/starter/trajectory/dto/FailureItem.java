package com.example.starter.trajectory.dto;

import com.example.starter.trajectory.RuleNumber;

/**
 * 单条规则失败项，只记录该规则（碰撞规则细化到障碍物 ID）的首次触发证据。
 *
 * @param ruleId         规则编号标识，如 {@code "R6"}
 * @param ruleName       规则中文名
 * @param firstTriggerTime 首次触发时间，单位秒
 * @param objectId       关联对象 ID（障碍物 ID；非碰撞规则为 {@code null}）
 * @param threshold      规则阈值，单位随规则而定
 * @param actualValue    实际测量值，单位与阈值一致
 * @param evidence       用于复核的相邻轨迹点/预测点
 */
public record FailureItem(
        String ruleId,
        String ruleName,
        double firstTriggerTime,
        String objectId,
        double threshold,
        double actualValue,
        FailureEvidence evidence) {

    /** 便于引擎内部按枚举构造失败项。 */
    public static FailureItem of(RuleNumber rule, double firstTriggerTime, String objectId,
                                 double threshold, double actualValue, FailureEvidence evidence) {
        return new FailureItem(rule.id(), rule.getDisplayName(), firstTriggerTime, objectId,
                threshold, actualValue, evidence);
    }
}
