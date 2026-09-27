package com.example.starter.survey.domain;

/** 报告逐项结论：PASS 合格 / FAIL 不合格 / MAJOR_DEFECT 重大缺陷（不得当合格项目）。 */
public enum ItemConclusion {
    PASS,
    FAIL,
    MAJOR_DEFECT
}
