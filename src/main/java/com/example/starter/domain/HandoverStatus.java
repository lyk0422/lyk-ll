package com.example.starter.domain;

/**
 * 执行中引航交班状态机：未结束 → 已切换 / 已撤回 / 核销关闭（均为终态）。
 */
public enum HandoverStatus {
    /** 未结束：已申请，等待双方签认并到达约定交班时刻。 */
    OPEN,
    /** 已切换：双方签认且到达交班时刻，责任已原子切换给接班员。 */
    SWITCHED,
    /** 已撤回：切换前撤回，接班预占已释放，原引航员保留。 */
    WITHDRAWN,
    /** 核销关闭：核销与切换竞争中由核销关闭，接班预占已释放。 */
    CLOSED
}
