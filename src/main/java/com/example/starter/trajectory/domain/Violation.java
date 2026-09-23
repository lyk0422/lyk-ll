package com.example.starter.trajectory.domain;

import java.util.List;

/**
 * 单条违规证据。后续重复触发不得替换首次证据，
 * 因此每条记录只保留规则+对象维度上第一次触发的现场。
 *
 * @param ruleNumber  规则编号
 * @param firstTime   首次触发时间，单位秒
 * @param objectId    关联对象 ID：碰撞为障碍物 ID，其余为车辆自身 "EGO"
 * @param threshold   规则阈值
 * @param actual      实际值
 * @param evidence    用于复核的相邻轨迹点或障碍物预测点（JSON 文本）
 */
public record Violation(
        RuleNumber ruleNumber,
        double firstTime,
        String objectId,
        double threshold,
        double actual,
        String evidence
) {

    /**
     * 车辆自身违规使用的对象 ID。
     */
    public static final String EGO = "EGO";

    /**
     * 按规则编号、首次触发时间、对象 ID 的固定排序。
     */
    public static int fixedOrder(Violation a, Violation b) {
        int byRule = Integer.compare(a.ruleNumber.code(), b.ruleNumber.code());
        if (byRule != 0) {
            return byRule;
        }
        int byTime = Double.compare(a.firstTime, b.firstTime);
        if (byTime != 0) {
            return byTime;
        }
        return a.objectId.compareTo(b.objectId);
    }

    /**
     * 组合键，用于保证同一规则+对象只保留首次证据。
     */
    public static String keyOf(RuleNumber ruleNumber, String objectId) {
        return ruleNumber.code() + "|" + objectId;
    }

    /**
     * 将有序违规列表序列化为稳定文本，用于结果哈希。
     */
    public static String stableLines(List<Violation> items) {
        StringBuilder sb = new StringBuilder();
        for (Violation v : items) {
            sb.append(v.ruleNumber.code()).append('|')
                    .append(Double.toHexString(v.firstTime)).append('|')
                    .append(v.objectId).append('|')
                    .append(Double.toHexString(v.threshold)).append('|')
                    .append(Double.toHexString(v.actual)).append('|')
                    .append(v.evidence).append('\n');
        }
        return sb.toString();
    }
}
