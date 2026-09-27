package com.example.starter.survey.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 合成船级检查项目目录（仅合成数据，不用于生产）。
 * 发证门禁要求：检验类型的全部必需项目须由登轮完成证明或有效互认绑定覆盖。
 */
public enum SurveyItem {
    HULL_STRUCTURE,
    MACHINERY,
    SAFETY_EQUIPMENT,
    RADIO_EQUIPMENT,
    LOAD_LINE;

    /** 各检验类型的必需项目集合（合成规则）。 */
    public static Set<SurveyItem> requiredFor(SurveyType type) {
        return switch (type) {
            case ANNUAL -> EnumSet.of(HULL_STRUCTURE, MACHINERY, SAFETY_EQUIPMENT);
            case INTERMEDIATE -> EnumSet.of(HULL_STRUCTURE, MACHINERY, SAFETY_EQUIPMENT, LOAD_LINE);
            case SPECIAL -> EnumSet.allOf(SurveyItem.class);
        };
    }
}
