package com.example.starter.domain;

/**
 * 交班单状态机：
 * 申请 → PENDING（接班预占已建立，原引航员占用保留）；
 * 双方签认且当前时刻达到约定交班时刻 → SWITCHED（原子切换）；
 * 切换前可撤回 → WITHDRAWN（释放接班预占，保留原人）；
 * 核销等与切换竞争时关闭 → CLOSED（释放接班预占，不可再切入）。
 */
public enum HandoverStatus {
    /** 待双方签认：接班引航员已对 [交班时刻, end) 预占，原引航员占用保留。 */
    PENDING,
    /** 已原子切换：责任段在交班时刻分割完成，不可撤回。 */
    SWITCHED,
    /** 已撤回：切换前由出班方撤回，接班预占释放，原人继续负责。 */
    WITHDRAWN,
    /** 已关闭：计划核销等终态竞争导致未结束交班关闭，接班预占释放。 */
    CLOSED
}
