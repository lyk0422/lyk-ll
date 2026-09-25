package com.example.starter.domain;

/**
 * 油机服务状态。
 */
public enum GeneratorStatus {

    /** 空闲可派。 */
    IDLE,
    /** 已派单、在途。 */
    DISPATCHED,
    /** 发电中。 */
    GENERATING
}
