package com.example.starter.domain;

/**
 * 关联证据类型，用于目标交叉、ID 切换、重识别等场景的可解释留痕。
 */
public enum EvidenceType {
    /** 目标交叉：关联时附近存在另一条轨迹也在关联门限内，记录双方距离依据。 */
    CROSSING,
    /** ID 切换：检测被判定属于新身份，旧身份轨迹回收，双向记录。 */
    ID_SWITCH,
    /** 重识别：LOST 轨迹被重新找回。 */
    REIDENTIFICATION,
    /** 普通观测关联（含门限、距离等依据）。 */
    OBSERVATION
}
