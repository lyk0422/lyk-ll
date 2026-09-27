package com.example.starter.survey.domain;

/**
 * 外部检查报告逐项结论。
 * 仅 {@link #PASS} 可作为互认合格项目；重大缺陷项目不得当合格项目。
 */
public enum ItemConclusion {
    PASS,
    FAIL,
    MAJOR_DEFECT
}
