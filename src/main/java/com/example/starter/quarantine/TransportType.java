package com.example.starter.quarantine;

/**
 * 运输类型：本地调运或过境（途经本地口岸、不在本地停留）。
 */
public enum TransportType {

    /** 本地调运：证书可用于本地销售或饲养流向登记。 */
    LOCAL,
    /** 过境：证书标注过境属性，不得用于本地销售或饲养流向登记，须在给定时限内完成抵达核销。 */
    TRANSIT
}
