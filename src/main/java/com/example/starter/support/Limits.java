package com.example.starter.support;

/**
 * 安全评估与资格规则限值常量；所有边界均为闭区间，恰等限值通过。
 */
public final class Limits {

    /** 相邻站点最小间距（米），恰等通过 */
    public static final int MIN_STOP_DISTANCE_M = 500;
    /** 相邻站点最大间距（米），恰等通过 */
    public static final int MAX_STOP_DISTANCE_M = 3000;
    /** 道路等级下限，恰等通过 */
    public static final int MIN_ROAD_GRADE = 3;
    /** 车辆座位数上限，恰等通过 */
    public static final int MAX_VEHICLE_SEATS = 56;
    /** 资格有效期（年） */
    public static final int QUALIFICATION_YEARS = 1;
    /** 续期窗口：到期前 30 日内（含恰第 30 日与到期当日） */
    public static final int RENEWAL_WINDOW_DAYS = 30;

    private Limits() {
    }
}
