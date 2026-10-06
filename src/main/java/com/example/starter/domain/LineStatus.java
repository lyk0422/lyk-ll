package com.example.starter.domain;

/**
 * 线路运行状态。
 */
public enum LineStatus {
    /** 正常运行，允许上客。 */
    NORMAL,
    /** 缓停：禁止上客，在途吊厢按站点逐段放行。 */
    SOFT_HOLD,
    /** 紧急停车：启动救援流程。 */
    EMERGENCY_STOP
}
