package com.example.starter.calibration.domain;

/**
 * 灰度发布批次状态。
 */
public enum CanaryStatus {
    /** 灰度中：指定车辆解析新版本，其余车辆解析旧版本。 */
    IN_PROGRESS,
    /** 已转正：新版本成为基线。 */
    PROMOTED,
    /** 已回滚：灰度车辆恢复发布前版本。 */
    ROLLED_BACK
}
