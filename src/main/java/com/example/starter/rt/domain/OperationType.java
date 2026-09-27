package com.example.starter.rt.domain;

/**
 * 幂等操作类型，参与 requestId 指纹计算。
 */
public enum OperationType {

    /** 物理审核。 */
    AUDIT,
    /** 医师批准。 */
    APPROVAL,
    /** 首次治疗前模体验证。 */
    VERIFICATION,
    /** 分次执行记录。 */
    FRACTION,
    /** 疗程结案。 */
    CLOSURE,
    /** 设备能力变更。 */
    DEVICE_CAPABILITY,
    /** 分次排程发布。 */
    SCHEDULE_PUBLISH,
    /** 分次排程取消。 */
    SCHEDULE_CANCEL,
    /** 受控治疗记录（排程内分次执行）。 */
    TREATMENT
}
