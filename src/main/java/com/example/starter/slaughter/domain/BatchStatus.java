package com.example.starter.slaughter.domain;

/**
 * 屠宰批次流转状态。
 */
public enum BatchStatus {

    /** 宰前申报已受理，待宰前检疫。 */
    DECLARED,
    /** 宰前检疫合格，6 小时（UTC）内未屠宰则失效，须重新申报。 */
    PRE_QUARANTINED,
    /** 宰前检疫不合格，禁止后续流转。 */
    QUARANTINE_REJECTED,
    /** 同步检验链已开启，尚未覆盖全部头只。 */
    INSPECTING,
    /** 同步检验完成，全部头只一一对应检验完毕，待判定分流。 */
    INSPECTED,
    /** 存在漏检头只，整批暂停流转。 */
    SUSPENDED,
    /** 判定分流完成，待无害化处理回执闭环与出厂核销。 */
    JUDGED,
    /** 出厂核销完成，终态。 */
    CHECKED_OUT
}
