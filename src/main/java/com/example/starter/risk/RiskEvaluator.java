package com.example.starter.risk;

import org.springframework.stereotype.Component;

/**
 * 合成风险评估模型（确定性、可复算）：
 * <pre>
 * score = (100 - 健康度) * 0.5 + min(倾斜度, 30) * 1.0 + min(树龄, 100) * 0.1 + (空洞 ? 15 : 0)
 * </pre>
 * 四舍五入取整后分级：&ge;70 一级（最高），50-69 二级，30-49 三级，&lt;30 四级。
 * 一级风险须 7 日内完成修剪，二级 30 日，三级 90 日，四级不生成修剪任务。
 */
@Component
public class RiskEvaluator {

    public static final int LEVEL1_DEADLINE_DAYS = 7;
    public static final int LEVEL2_DEADLINE_DAYS = 30;
    public static final int LEVEL3_DEADLINE_DAYS = 90;

    /** 计算风险分。 */
    public int score(int healthScore, double leanDegree, int ageYears, boolean cavity) {
        double value = (100 - healthScore) * 0.5
                + Math.min(leanDegree, 30.0)
                + Math.min(ageYears, 100) * 0.1
                + (cavity ? 15.0 : 0.0);
        return (int) Math.round(value);
    }

    /** 按分数定级，一级为最高风险。 */
    public int levelForScore(int score) {
        if (score >= 70) {
            return 1;
        }
        if (score >= 50) {
            return 2;
        }
        if (score >= 30) {
            return 3;
        }
        return 4;
    }

    /** 是否需要生成修剪任务（一至三级）。 */
    public boolean needsPruning(int level) {
        return level >= 1 && level <= 3;
    }

    /** 各等级闭环期限（天）；一级为 7 天。 */
    public int deadlineDays(int level) {
        return switch (level) {
            case 1 -> LEVEL1_DEADLINE_DAYS;
            case 2 -> LEVEL2_DEADLINE_DAYS;
            case 3 -> LEVEL3_DEADLINE_DAYS;
            default -> throw new IllegalArgumentException("等级 " + level + " 无需修剪任务");
        };
    }
}
