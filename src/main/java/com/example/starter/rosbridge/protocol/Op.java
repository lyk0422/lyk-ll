package com.example.starter.rosbridge.protocol;

/**
 * WebSocket 文本帧的操作码（op 字段取值）。
 */
public final class Op {

    // 客户端 -> 网关
    public static final String CONNECT = "CONNECT";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";
    public static final String ACK = "ACK";
    public static final String CALL_SERVICE = "CALL_SERVICE";
    public static final String SEND_GOAL = "SEND_GOAL";
    public static final String CANCEL_GOAL = "CANCEL_GOAL";

    // 网关 -> 客户端
    public static final String CONNACK = "CONNACK";
    public static final String SUBSCRIBED = "SUBSCRIBED";
    public static final String UNSUBSCRIBED = "UNSUBSCRIBED";
    public static final String TOPIC = "TOPIC";
    public static final String RESUME_GAP = "RESUME_GAP";
    public static final String SERVICE_RESPONSE = "SERVICE_RESPONSE";
    public static final String GOAL_ACCEPTED = "GOAL_ACCEPTED";
    public static final String GOAL_CANCELLED = "GOAL_CANCELLED";
    public static final String ACTION_FEEDBACK = "ACTION_FEEDBACK";
    public static final String ACTION_RESULT = "ACTION_RESULT";
    public static final String ERROR = "ERROR";

    private Op() {
    }
}
