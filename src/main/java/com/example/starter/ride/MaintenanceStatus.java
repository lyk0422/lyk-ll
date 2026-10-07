package com.example.starter.ride;

/**
 * 检修挂牌单状态机：建档→启动（进行中）→结束。
 * 结束只清除挂牌阻断，不改变设备原状态链。
 */
public enum MaintenanceStatus {

    /** 已建档（初始状态），人员清单可查到但尚未冻结，未占用设备 */
    PREPARED,

    /** 进行中：整机已停运，人员清单冻结，每把锁待双人签认撤除 */
    IN_PROGRESS,

    /** 已结束：全部锁撤除后结束，仅清除挂牌阻断，不自动开放 */
    FINISHED
}
