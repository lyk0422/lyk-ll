package com.example.starter.domain;

/**
 * 判定分流类别：合格、有条件食用、化制、销毁四类。
 */
public enum JudgmentCategory {
    /** 合格，判定事务内同步完成库存标识。 */
    QUALIFIED,
    /** 有条件食用，须标注高温处理并限定流通区域。 */
    CONDITIONAL,
    /** 化制，须取得无害化处理回执闭环。 */
    RENDERING,
    /** 销毁，须取得无害化处理回执闭环。 */
    DESTRUCTION
}
