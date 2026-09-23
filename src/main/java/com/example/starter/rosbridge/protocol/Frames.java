package com.example.starter.rosbridge.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关下发给客户端的帧构造器。所有帧均为有序 Map，序列化为 JSON 文本后发送。
 *
 * <p>帧 op 约定：SUBSCRIBED、UNSUBSCRIBED、PUBLISH、RESUME_GAP、
 * SERVICE_RESPONSE、SERVICE_ERROR、ACTION_FEEDBACK、ACTION_RESULT、ERROR。
 */
public final class Frames {

    public static final String OP_SUBSCRIBED = "SUBSCRIBED";
    public static final String OP_UNSUBSCRIBED = "UNSUBSCRIBED";
    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_RESUME_GAP = "RESUME_GAP";
    public static final String OP_SERVICE_RESPONSE = "SERVICE_RESPONSE";
    public static final String OP_SERVICE_ERROR = "SERVICE_ERROR";
    public static final String OP_ACTION_FEEDBACK = "ACTION_FEEDBACK";
    public static final String OP_ACTION_RESULT = "ACTION_RESULT";
    public static final String OP_ERROR = "ERROR";

    /** Service 调用超时错误码。 */
    public static final String CODE_TIMEOUT = "TIMEOUT";
    /** 通用错误码。 */
    public static final String CODE_ERROR = "ERROR";

    /** Action 最终状态：成功。 */
    public static final String ACTION_OK = "OK";
    /** Action 最终状态：已取消。 */
    public static final String ACTION_CANCELED = "CANCELED";

    private Frames() {
    }

    private static Map<String, Object> frame(String op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("op", op);
        return map;
    }

    /** 订阅已生效（幂等重复订阅也返回该帧）。 */
    public static Map<String, Object> subscribed(String topic) {
        Map<String, Object> f = frame(OP_SUBSCRIBED);
        f.put("topic", topic);
        return f;
    }

    /** 取消订阅已生效（幂等重复取消也返回该帧）。 */
    public static Map<String, Object> unsubscribed(String topic) {
        Map<String, Object> f = frame(OP_UNSUBSCRIBED);
        f.put("topic", topic);
        return f;
    }

    /** Topic 消息帧，cursor 为网关共享单调游标。 */
    public static Map<String, Object> publish(String topic, String type, long cursor, JsonNode msg) {
        Map<String, Object> f = frame(OP_PUBLISH);
        f.put("topic", topic);
        f.put("type", type);
        f.put("cursor", cursor);
        f.put("msg", msg);
        return f;
    }

    /**
     * 慢客户端队列溢出导致丢弃一段未确认游标时下发的间隙帧，
     * 范围 [fromCursor, toCursor] 均为闭区间。
     */
    public static Map<String, Object> resumeGap(long fromCursor, long toCursor, int dropped) {
        Map<String, Object> f = frame(OP_RESUME_GAP);
        f.put("fromCursor", fromCursor);
        f.put("toCursor", toCursor);
        f.put("dropped", dropped);
        return f;
    }

    /** Service 正常响应。 */
    public static Map<String, Object> serviceResponse(String requestId, String service, JsonNode result) {
        Map<String, Object> f = frame(OP_SERVICE_RESPONSE);
        f.put("requestId", requestId);
        f.put("service", service);
        f.put("values", result);
        return f;
    }

    /** Service 失败帧；超时使用 code=TIMEOUT。 */
    public static Map<String, Object> serviceError(String requestId, String service, String code, String message) {
        Map<String, Object> f = frame(OP_SERVICE_ERROR);
        f.put("requestId", requestId);
        f.put("service", service);
        f.put("code", code);
        f.put("message", message);
        return f;
    }

    /** Action 进度反馈。 */
    public static Map<String, Object> actionFeedback(String goalId, String action, JsonNode feedback) {
        Map<String, Object> f = frame(OP_ACTION_FEEDBACK);
        f.put("goalId", goalId);
        f.put("action", action);
        f.put("feedback", feedback);
        return f;
    }

    /** Action 最终结果帧，status 为 OK 或 CANCELED。 */
    public static Map<String, Object> actionResult(String goalId, String action, String status, JsonNode result) {
        Map<String, Object> f = frame(OP_ACTION_RESULT);
        f.put("goalId", goalId);
        f.put("action", action);
        f.put("status", status);
        f.put("result", result);
        return f;
    }

    /** 协议级通用错误帧。 */
    public static Map<String, Object> error(String code, String message) {
        Map<String, Object> f = frame(OP_ERROR);
        f.put("code", code);
        f.put("message", message);
        return f;
    }
}
