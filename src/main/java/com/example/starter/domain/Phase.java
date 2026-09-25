package com.example.starter.domain;

/**
 * 水下记录阶段。
 */
public enum Phase {
    /** 下潜：深度 0→目标深度，按深度差/历时复算速率 */
    DESCENT,
    /** 水底停留：速率为 0，历时计入 24 小时累计水下时间台账 */
    BOTTOM,
    /** 上升出水：目标深度→0，按深度差/历时复算速率 */
    ASCENT
}
