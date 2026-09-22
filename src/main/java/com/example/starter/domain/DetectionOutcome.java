package com.example.starter.domain;

/**
 * 单次检测关联处理结果。
 */
public enum DetectionOutcome {
    /** 检测创建了新轨迹。 */
    CREATED,
    /** 检测关联到已有轨迹。 */
    ASSOCIATED,
    /** 检测被拒绝（低置信度/乱序/超门限/非法状态），不产生任何有效变化。 */
    REJECTED,
    /** 重复 detectionId：幂等返回首次处理结果，不产生任何变化。 */
    DUPLICATE
}
