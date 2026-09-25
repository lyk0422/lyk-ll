package com.example.starter.torque;

/**
 * 业务规则错误码常量。
 */
public final class ErrorCodes {

    /** 同一 requestId 重放但业务参数不一致。 */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** 工位已有执行中工序卡。 */
    public static final String STATION_OCCUPIED = "STATION_OCCUPIED";
    /** 力矩工具当日校验失效。 */
    public static final String TOOL_EXPIRED = "TOOL_EXPIRED";
    /** 自检与互检为同一人员。 */
    public static final String SAME_INSPECTOR = "SAME_INSPECTOR";
    /** 工序状态不允许当前操作。 */
    public static final String ILLEGAL_STATE = "ILLEGAL_STATE";
    /** 资源不存在。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 参数非法。 */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    private ErrorCodes() {
    }
}
