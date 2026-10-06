package com.example.starter.model;

/**
 * 线路状态。
 */
public enum LineStatus {
    /** 正常运行 */
    NORMAL,
    /** 缓停：禁止上客，在途吊厢逐段放行 */
    SLOW_STOP,
    /** 紧急停车：启动救援流程 */
    EMERGENCY_STOP
}
