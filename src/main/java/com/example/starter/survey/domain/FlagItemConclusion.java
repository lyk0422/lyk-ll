package com.example.starter.survey.domain;

/** 外部检查逐项结论：仅 SATISFACTORY 可充当互认合格证据。 */
public enum FlagItemConclusion {
    /** 合格。 */
    SATISFACTORY,
    /** 一般缺陷。 */
    MINOR_DEFECT,
    /** 重大缺陷（不得充当合格项目）。 */
    MAJOR_DEFECT
}
