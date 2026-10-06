package com.example.starter.domain;

/**
 * 收运联单状态机：执行中 -> 已回执 / 异常待核查 -> 核查闭环。
 */
public enum ManifestStatus {
    /** 已登记进场称重，尚未到达处理设施回执。 */
    IN_TRANSIT,
    /** 回执净重偏差在 2%（含）以内，正常闭环。 */
    RECEIVED,
    /** 回执净重偏差超过 2%，转入异常核查。 */
    EXCEPTION,
    /** 异常核查已给出结论（设备误差或装载差异），留痕闭环。 */
    RESOLVED
}
