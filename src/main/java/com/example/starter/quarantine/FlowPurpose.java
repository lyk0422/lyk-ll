package com.example.starter.quarantine;

/**
 * 流向用途：系统按证书运输类型拦截不符用途的流向登记。
 */
public enum FlowPurpose {

    /** 本地销售（仅 LOCAL 证）。 */
    LOCAL_SALE,
    /** 饲养（仅 LOCAL 证）。 */
    BREEDING,
    /** 过境通行（仅 TRANSIT 证）。 */
    TRANSIT
}
