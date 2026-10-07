package com.example.starter.ride;

/**
 * 检修单状态：PREPARED 已建单（清单已登记未冻结）→ IN_PROGRESS 已启动（整机停运、挂牌上锁）
 * → FINISHED 已结束（挂牌阻断清除，不自动开放）。
 */
public enum MaintenanceStatus {

    /** 已建单：绑定设备与1至20名不同人员，尚未启动，清单可复算 */
    PREPARED,

    /** 进行中：整机已停运，每人一把唯一未撤锁，同设备同时刻只允许一张 */
    IN_PROGRESS,

    /** 已结束：全部锁已撤除，仅清除挂牌阻断；历史清单与双人签认不可覆盖 */
    FINISHED
}
