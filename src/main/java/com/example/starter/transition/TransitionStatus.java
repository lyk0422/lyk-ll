package com.example.starter.transition;

/**
 * 改品种单状态常量：申请→方式判定→执行→过渡料处置→完成→归档（终态）。
 */
public final class TransitionStatus {

    public static final String APPLIED = "APPLIED";
    public static final String DECIDED = "DECIDED";
    public static final String EXECUTED = "EXECUTED";
    public static final String DISPOSED = "DISPOSED";
    public static final String COMPLETED = "COMPLETED";
    /** 归档：终态。 */
    public static final String ARCHIVED = "ARCHIVED";

    private TransitionStatus() {
    }
}
