package com.example.starter.rosbridge.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关 -> 客户端 JSON 文本帧构造工具。字段顺序保持稳定，便于断言与示例。
 */
public final class Frames {

    private Frames() {
    }

    public static String connack(String clientId, String sessionId, int ratePerSecond, int queueCapacity) {
        Map<String, Object> m = base(Op.CONNACK);
        m.put("clientId", clientId);
        m.put("sessionId", sessionId);
        m.put("ratePerSecond", ratePerSecond);
        m.put("queueCapacity", queueCapacity);
        return write(m);
    }

    public static String subscribed(String topic, String type, String qosJson, ObjectMapper mapper) {
        Map<String, Object> m = base(Op.SUBSCRIBED);
        m.put("topic", topic);
        m.put("type", type);
        m.put("qos", readQos(qosJson, mapper));
        return write(m);
    }

    public static String unsubscribed(String topic) {
        Map<String, Object> m = base(Op.UNSUBSCRIBED);
        m.put("topic", topic);
        return write(m);
    }

    /** Topic 数据帧；cursor 为共享订阅单调游标，resume=true 表示断线重连后的补发。 */
    public static String topic(String topic, String type, long cursor, JsonNode data, boolean resume) {
        Map<String, Object> m = base(Op.TOPIC);
        m.put("topic", topic);
        m.put("type", type);
        m.put("cursor", cursor);
        m.put("resume", resume);
        m.put("data", data);
        return write(m);
    }

    /** 慢消费者丢帧通知；fromCursor/toCursor 为被丢弃的最旧到最新未确认游标闭区间。 */
    public static String resumeGap(String topic, long fromCursor, long toCursor) {
        Map<String, Object> m = base(Op.RESUME_GAP);
        m.put("topic", topic);
        m.put("fromCursor", fromCursor);
        m.put("toCursor", toCursor);
        return write(m);
    }

    public static String serviceResponse(String requestId, String service, CallStatus status,
                                         JsonNode result, String error) {
        Map<String, Object> m = base(Op.SERVICE_RESPONSE);
        m.put("requestId", requestId);
        m.put("service", service);
        m.put("status", status.name());
        if (result != null) {
            m.put("result", result);
        }
        if (error != null) {
            m.put("error", error);
        }
        return write(m);
    }

    public static String goalAccepted(String goalId, String action) {
        Map<String, Object> m = base(Op.GOAL_ACCEPTED);
        m.put("goalId", goalId);
        m.put("action", action);
        return write(m);
    }

    public static String goalCancelled(String goalId, String action, JsonNode result) {
        Map<String, Object> m = base(Op.GOAL_CANCELLED);
        m.put("goalId", goalId);
        m.put("action", action);
        if (result != null) {
            m.put("result", result);
        }
        return write(m);
    }

    public static String actionFeedback(String goalId, String action, JsonNode feedback) {
        Map<String, Object> m = base(Op.ACTION_FEEDBACK);
        m.put("goalId", goalId);
        m.put("action", action);
        m.put("feedback", feedback);
        return write(m);
    }

    public static String actionResult(String goalId, String action, GoalStatus status,
                                      JsonNode result, String error) {
        Map<String, Object> m = base(Op.ACTION_RESULT);
        m.put("goalId", goalId);
        m.put("action", action);
        m.put("status", status.name());
        if (result != null) {
            m.put("result", result);
        }
        if (error != null) {
            m.put("error", error);
        }
        return write(m);
    }

    public static String error(String inReplyTo, String code, String message) {
        Map<String, Object> m = base(Op.ERROR);
        if (inReplyTo != null) {
            m.put("inReplyTo", inReplyTo);
        }
        m.put("code", code);
        m.put("message", message);
        return write(m);
    }

    private static Map<String, Object> base(String op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        return m;
    }

    private static Object readQos(String qosJson, ObjectMapper mapper) {
        try {
            return mapper.readValue(qosJson, Object.class);
        } catch (JsonProcessingException e) {
            return qosJson;
        }
    }

    private static String write(Map<String, Object> m) {
        try {
            return ProtocolJson.MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode frame", e);
        }
    }
}
