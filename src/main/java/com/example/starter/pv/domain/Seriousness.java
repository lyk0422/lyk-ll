package com.example.starter.pv.domain;

/**
 * 个例严重程度，决定初报时限：致死/危及生命 7 日，其他严重 15 日（均自获知日 T0 起，UTC 日界）。
 */
public enum Seriousness {

    /** 致死。 */
    FATAL,
    /** 危及生命。 */
    LIFE_THREATENING,
    /** 其他严重。 */
    OTHER_SERIOUS
}
