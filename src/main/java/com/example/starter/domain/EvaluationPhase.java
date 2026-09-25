package com.example.starter.domain;

/**
 * 评估阶段。
 * <ul>
 *   <li>{@link #JUDGMENT} 失衡判定：调节前依据回水温度分布判定欠流/过流；</li>
 *   <li>{@link #RE_EVAL} 效果评估：调节稳定满 4 小时后的复算评估。</li>
 * </ul>
 */
public enum EvaluationPhase {
    JUDGMENT,
    RE_EVAL
}
