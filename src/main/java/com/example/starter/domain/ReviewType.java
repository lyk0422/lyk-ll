package com.example.starter.domain;

/**
 * 审查类型：兼捕超标、卸渔偏差、结案后迟到日志。
 */
public enum ReviewType {

    /** 兼捕保护鱼种占比超过 5% 触发的日志审查。 */
    BYCATCH,

    /** 进港卸渔量与日志累计量偏差超过 3% 触发的人工复核。 */
    DEVIATION,

    /** 航次结案后补交日志触发的迟到事件审查。 */
    LATE_LOG
}
