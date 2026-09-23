package com.example.starter.rosbridge.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * rosbridge 风格 WebSocket 协议常量与出/入帧工具。
 *
 * <p>入帧（客户端 -&gt; 网关）：</p>
 * <ul>
 *   <li>{@code connect}：声明 clientId、rateLimitPerSec、queueCap</li>
 *   <li>{@code subscribe}/{@code unsubscribe}：topic/type/qos</li>
 *   <li>{@code ack}：确认共享订阅游标</li>
 *   <li>{@code call_service}：requestId/service/type/args/timeoutMs</li>
 *   <li>{@code send_goal}/{@code cancel_goal}：goalId/action/type/goal</li>
 * </ul>
 *
 * <p>出帧（网关 -&gt; 客户端）：connected/subscribed/unsubscribed/topic/RESUME_GAP/
 * service_response/action_feedback/action_result/error。</p>
 */
public final class Protocol {

    private Protocol() {
    }

    public static final ObjectMapper MAPPER = new ObjectMapper();

    // 入帧 op
    public static final String OP_CONNECT = "connect";
    public static final String OP_SUBSCRIBE = "subscribe";
    public static final String OP_UNSUBSCRIBE = "unsubscribe";
    public static final String OP_ACK = "ack";
    public static final String OP_CALL_SERVICE = "call_service";
    public static final String OP_SEND_GOAL = "send_goal";
    public static final String OP_CANCEL_GOAL = "cancel_goal";
    public static final String OP_PING = "ping";

    // 出帧 op
    public static final String OP_CONNECTED = "connected";
    public static final String OP_SUBSCRIBED = "subscribed";
    public static final String OP_UNSUBSCRIBED = "unsubscribed";
    public static final String OP_PUBLISH = "publish";
    public static final String OP_RESUME_GAP = "RESUME_GAP";
    public static final String OP_SERVICE_RESPONSE = "service_response";
    public static final String OP_ACTION_FEEDBACK = "action_feedback";
    public static final String OP_ACTION_RESULT = "action_result";
    public static final String OP_PONG = "pong";
    public static final String OP_ERROR = "error";

    // Service 调用终态
    public static final String SERVICE_OK = "OK";
    public static final String SERVICE_TIMEOUT = "TIMEOUT";
    public static final String SERVICE_ERROR = "ERROR";

    // Action 终态
    public static final String GOAL_SUCCEEDED = "SUCCEEDED";
    public static final String GOAL_ABORTED = "ABORTED";
    public static final String GOAL_CANCELED = "CANCELED";

    private static Map<String, Object> frame(String op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        return m;
    }

    public static String connected() {
        return toJson(frame(OP_CONNECTED));
    }

    public static String subscribed(String topic, String type, String qosKey) {
        Map<String, Object> m = frame(OP_SUBSCRIBED);
        m.put("topic", topic);
        m.put("type", type);
        m.put("qos", qosKey);
        return toJson(m);
    }

    public static String unsubscribed(String topic, String type, String qosKey) {
        Map<String, Object> m = frame(OP_UNSUBSCRIBED);
        m.put("topic", topic);
        m.put("type", type);
        m.put("qos", qosKey);
        return toJson(m);
    }

    /**
     * 共享订阅消息帧。
     *
     * @param cursor 共享订阅单调游标
     */
    public static String topicMessage(String topic, String type, String qosKey,
                                      long cursor, JsonNode payload) {
        Map<String, Object> m = frame(OP_PUBLISH);
        m.put("topic", topic);
        m.put("type", type);
        m.put("qos", qosKey);
        m.put("cursor", cursor);
        m.put("msg", payload);
        return toJson(m);
    }

    /**
     * 慢消费者丢消息后的缺口通知；fromCursor 到 toCursor（含两端）之间的消息已被丢弃。
     */
    public static String resumeGap(String topic, String type, String qosKey,
                                   long fromCursor, long toCursor) {
        Map<String, Object> m = frame(OP_RESUME_GAP);
        m.put("topic", topic);
        m.put("type", type);
        m.put("qos", qosKey);
        m.put("fromCursor", fromCursor);
        m.put("toCursor", toCursor);
        return toJson(m);
    }

    public static String serviceResponse(String requestId, String service,
                                         String status, JsonNode value, String error) {
        Map<String, Object> m = frame(OP_SERVICE_RESPONSE);
        m.put("requestId", requestId);
        m.put("service", service);
        m.put("status", status);
        if (value != null) {
            m.put("values", value);
        }
        if (error != null) {
            m.put("error", error);
        }
        return toJson(m);
    }

    public static String actionFeedback(String goalId, String action, JsonNode feedback) {
        Map<String, Object> m = frame(OP_ACTION_FEEDBACK);
        m.put("goalId", goalId);
        m.put("action", action);
        m.put("feedback", feedback);
        return toJson(m);
    }

    public static String actionResult(String goalId, String action,
                                      String status, JsonNode result) {
        Map<String, Object> m = frame(OP_ACTION_RESULT);
        m.put("goalId", goalId);
        m.put("action", action);
        m.put("status", status);
        if (result != null) {
            m.put("result", result);
        }
        return toJson(m);
    }

    public static String pong(long nonce) {
        Map<String, Object> m = frame(OP_PONG);
        m.put("nonce", nonce);
        return toJson(m);
    }

    public static String error(String requestOp, String message) {
        Map<String, Object> m = frame(OP_ERROR);
        m.put("requestOp", requestOp);
        m.put("msg", message);
        return toJson(m);
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("帧序列化失败", e);
        }
    }
}
