package com.example.starter.slaughter.domain;

/**
 * 判定后库存标识范围。
 */
public enum InventoryScope {

    /** 合格：普通可流通库存。 */
    GENERAL,
    /** 有条件食用：限定流通区域的高温处理库存。 */
    LIMITED_REGION,
    /** 化制/销毁：不入可流通库存，进入无害化处理。 */
    NONE
}
