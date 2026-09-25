package com.example.starter.slaughter.domain;

/**
 * 头只判定分流类别。
 */
public enum JudgmentCategory {

    /** 合格：直接标识为普通可流通库存。 */
    QUALIFIED,
    /** 有条件食用：须标注高温处理并限定流通区域。 */
    CONDITIONAL,
    /** 化制：须取得化制类无害化处理回执闭环。 */
    RENDERED,
    /** 销毁：须取得销毁类无害化处理回执闭环。 */
    DESTROYED
}
