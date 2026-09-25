package com.example.starter.survey.domain;

/**
 * 船舶检验类型：年度检验、中间检验、特别检验。
 */
public enum SurveyType {
    /** 年度检验：窗口为周年日前后各 3 个月，可展期一次、最长 3 个月。 */
    ANNUAL,
    /** 中间检验：不得展期。 */
    INTERMEDIATE,
    /** 特别检验：不得展期。 */
    SPECIAL
}
