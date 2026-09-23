package com.example.starter.calibration.domain;

/**
 * 灰度发布单状态。
 */
public enum ReleaseStatus {
    /** 已发布：灰度车辆切换到新版本。 */
    ROLLED_OUT,
    /** 已回滚：灰度车辆恢复到各自发布前版本。 */
    ROLLED_BACK
}
