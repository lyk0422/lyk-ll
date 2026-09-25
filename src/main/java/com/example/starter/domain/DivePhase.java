package com.example.starter.domain;

/**
 * 水下记录分段类型：下潜、水底（作业）、出水（上升）。
 */
public enum DivePhase {
    /** 下潜段：由浅到深，速率不超过 18m/min。 */
    DESCEND,
    /** 水底作业段：不参与速率计算，计入水下时长。 */
    BOTTOM,
    /** 出水上升段：由深到浅，速率不超过 18m/min。 */
    ASCEND
}
