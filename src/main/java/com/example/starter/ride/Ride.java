package com.example.starter.ride;

import java.time.LocalDate;

/**
 * 游乐设施主表实体（合成数据）。
 *
 * @param id          设备主键
 * @param name        设备名称
 * @param status      当前状态，见 {@link RideStatus}
 * @param maxLoad     载荷阈值（人），恰等阈值合格
 * @param maxSpeed    速度阈值（rpm），恰等阈值合格
 * @param trialCount  当日已连续合格试运行次数，恰3次方可载客
 * @param reopenCount 当日复开次数，恰2次合法
 * @param checkDate   最近一次日检合格日期，NULL 表示未日检
 * @param reopenDate  最近一次复开日期，用于跨日重置复开计数
 * @param inspectionRequired 受检标志：true 表示开放/复开须同时满足有效年度证书与当前大修版本监督检验
 * @param overhaulVersion 检验周期版本（大修版本），登记大修时原子递增，旧监督检验随之失效
 * @param version     乐观锁版本号
 */
public record Ride(
        long id,
        String name,
        RideStatus status,
        int maxLoad,
        int maxSpeed,
        int trialCount,
        int reopenCount,
        LocalDate checkDate,
        LocalDate reopenDate,
        boolean inspectionRequired,
        long overhaulVersion,
        long version) {

    /** 安全装置检查项名称，任一失效整机立即停运。 */
    public static final String[] SAFETY_DEVICES = {"压杠", "安全带", "联锁装置"};

    /** 试运行合法次数：恰3次空载无异常方可载客。 */
    public static final int REQUIRED_TRIAL_COUNT = 3;

    /** 同日复开上限：恰2次合法，超出当日禁开。 */
    public static final int MAX_DAILY_REOPEN = 2;

    /** 年度证书默认有效期：合格时刻加365×24小时（合成规则，不引用真实法定周期）。 */
    public static final java.time.Duration CERT_DEFAULT_VALIDITY = java.time.Duration.ofHours(365L * 24);
}
