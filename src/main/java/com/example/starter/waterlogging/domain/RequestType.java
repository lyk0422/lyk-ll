package com.example.starter.waterlogging.domain;

/**
 * requestId 幂等请求的业务类型，幂等键为（requestId, requestType）。
 */
public final class RequestType {

    /** 气象预警启动预降。 */
    public static final String WARNING_START = "WARNING_START";
    /** 泵站单机启停指令。 */
    public static final String PUMP_COMMAND = "PUMP_COMMAND";
    /** 液位复测数据上报。 */
    public static final String LEVEL_READING = "LEVEL_READING";
    /** 失联判定派单检查。 */
    public static final String LOSS_CHECK = "LOSS_CHECK";
    /** 复盘归档。 */
    public static final String REVIEW_ARCHIVE = "REVIEW_ARCHIVE";

    private RequestType() {
    }
}
