package com.example.starter.service;

/**
 * 合成风险评估模型（确定性、可复算）：
 * 得分 = 倾斜角*1.2 + 病害等级*15 + min(树龄,60)*0.5 + 风口暴露等级*8；
 * 一级(最高风险) >= 70，二级 >= 45，三级 >= 25，其余为四级。
 */
public final class RiskModel {

    public static final int LEVEL_1_THRESHOLD = 70;
    public static final int LEVEL_2_THRESHOLD = 45;
    public static final int LEVEL_3_THRESHOLD = 25;

    private RiskModel() {
    }

    public static double score(double tiltDegrees, int diseaseLevel, int ageYears, int windExposure) {
        return tiltDegrees * 1.2 + diseaseLevel * 15.0
                + Math.min(ageYears, 60) * 0.5 + windExposure * 8.0;
    }

    public static int level(double score) {
        if (score >= LEVEL_1_THRESHOLD) {
            return 1;
        }
        if (score >= LEVEL_2_THRESHOLD) {
            return 2;
        }
        if (score >= LEVEL_3_THRESHOLD) {
            return 3;
        }
        return 4;
    }
}
