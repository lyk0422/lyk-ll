package com.example.starter.calibration.domain;

/**
 * 标定版本审批与发布状态。
 */
public enum CalibrationStatus {
    /** 草稿：刚创建、未通过校验或未审批，不参与解析。 */
    DRAFT,
    /** 已批准：校验通过并完成审批，可用于灰度或全量生效。 */
    APPROVED,
    /** 灰度中：仅对灰度发布单指定车辆集合生效。 */
    IN_CANARY,
    /** 已生效：全量车辆可解析的常规版本。 */
    EFFECTIVE,
    /** 已回滚：灰度终止后的版本，不再参与解析。 */
    ROLLED_BACK
}
