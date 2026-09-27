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
    /** 设备登记。 */
    DEVICE_REGISTER,
    /** 设备能力调整。 */
    DEVICE_CAPABILITY,
    /** 连续分次批量排程发布。 */
    SCHEDULE_PUBLISH,
    /** 排程批次取消。 */
    SCHEDULE_CANCEL,
    /** 排程受控治疗记录。 */
    TREATMENT
}
