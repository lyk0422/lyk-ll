package com.example.starter.ride;

import java.time.LocalDate;

/**
 * 游乐设施主表实体（合成数据）。
 *
 * @param id                   设备主键
 * @param name                 设备名称
 * @param status               当前状态，见 {@link RideStatus}
 * @param maxLoad              载荷阈值（人），恰等阈值合格
 * @param maxSpeed             速度阈值（rpm），恰等阈值合格
 * @param trialCount           当日已连续合格试运行次数，恰3次方可载客
 * @param reopenCount          当日复开次数，恰2次合法；大修监督检验合格不重置
 * @param checkDate            最近一次日检合格日期，NULL 表示未日检
 * @param reopenDate           最近一次复开日期，用于跨日重置复开计数
 * @param inspectionRequired   合成受检标志：受检设备开放/复开须持当前有效年度证书
 * @param cycleVersion         合成检验周期版本，登记为1，不引用真实法定周期
 * @param overhaulVersion      设备大修版本，0 表示从未大修，登记大修原子加1
 * @param currentCertificateId 当前年度证书唯一指针，NULL 表示无当前证书
 * @param version              乐观锁版本号，从1开始，每次成功变更加1
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
        int cycleVersion,
        int overhaulVersion,
        Long currentCertificateId,
        long version) {

    /** 安全装置检查项名称，任一失效整机立即停运。 */
    public static final String[] SAFETY_DEVICES = {"压杠", "安全带", "联锁装置"};

    /** 试运行合法次数：恰3次空载无异常方可载客。 */
    public static final int REQUIRED_TRIAL_COUNT = 3;

    /** 同日复开上限：恰2次合法，超出当日禁开。 */
    public static final int MAX_DAILY_REOPEN = 2;

    /** 年度证书默认有效期：合格时刻加365×24小时（合成规则，非真实法定周期）。 */
    public static final long CERT_VALIDITY_HOURS = 365L * 24L;

    /** 受检设备初始检验周期版本。 */
    public static final int INITIAL_CYCLE_VERSION = 1;
}
