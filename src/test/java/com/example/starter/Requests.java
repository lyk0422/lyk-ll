package com.example.starter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用请求体构造助手。
 */
public final class Requests {

    private Requests() {
    }

    public static String apply(ObjectMapper m, String requestId, String a, String b,
                               int depth, int bottom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diverACode", a);
        body.put("diverBCode", b);
        body.put("plannedDepthM", depth);
        body.put("plannedBottomMin", bottom);
        return write(m, body);
    }

    public static String plan(ObjectMapper m, String requestId, String diveRequestId,
                              int depth, int bottom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("depthM", depth);
        body.put("bottomMin", bottom);
        return write(m, body);
    }

    public static String record(ObjectMapper m, String requestId, String diveRequestId,
                                String diver, String phase, int from, int to, int duration,
                                Instant eventTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("diverCode", diver);
        body.put("phase", phase);
        body.put("fromDepthM", from);
        body.put("toDepthM", to);
        body.put("durationMin", duration);
        body.put("eventTime", eventTime.toString());
        return write(m, body);
    }

    public static String surface(ObjectMapper m, String requestId, String diveRequestId,
                                 Instant surfacedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        if (surfacedAt != null) {
            body.put("surfacedAt", surfacedAt.toString());
        }
        return write(m, body);
    }

    public static String vitals(ObjectMapper m, String requestId, String diveRequestId,
                                boolean symptomatic, Instant observedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("symptomatic", symptomatic);
        if (observedAt != null) {
            body.put("observedAt", observedAt.toString());
        }
        return write(m, body);
    }

    public static String close(ObjectMapper m, String requestId, String diveRequestId,
                               Instant closedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        if (closedAt != null) {
            body.put("closedAt", closedAt.toString());
        }
        return write(m, body);
    }

    // ---- 受控反复潜水流程（虚构积分模型） ----

    public static String controlledApply(ObjectMapper m, String requestId, String a, String b,
                                         int depth, int bottom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diverACode", a);
        body.put("diverBCode", b);
        body.put("plannedDepthM", depth);
        body.put("plannedBottomMin", bottom);
        return write(m, body);
    }

    public static String controlledPlan(ObjectMapper m, String requestId, String diveRequestId,
                                        int expectedVersion, int depth, int bottom,
                                        Instant plannedDescentAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("expectedVersion", expectedVersion);
        body.put("depthM", depth);
        body.put("bottomMin", bottom);
        body.put("plannedDescentAt", plannedDescentAt.toString());
        return write(m, body);
    }

    public static String controlledStart(ObjectMapper m, String requestId, String diveRequestId,
                                         int expectedVersion, Instant actualDescentAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("expectedVersion", expectedVersion);
        if (actualDescentAt != null) {
            body.put("actualDescentAt", actualDescentAt.toString());
        }
        return write(m, body);
    }

    public static String controlledSurface(ObjectMapper m, String requestId, String diveRequestId,
                                           int expectedVersion, int bottomMin, Instant surfacedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("expectedVersion", expectedVersion);
        body.put("bottomMin", bottomMin);
        if (surfacedAt != null) {
            body.put("surfacedAt", surfacedAt.toString());
        }
        return write(m, body);
    }

    public static String correction(ObjectMapper m, String requestId, String diveRequestId,
                                    String diver, String phase, int expectedVersion,
                                    int correctedDurationMin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("diveRequestId", diveRequestId);
        body.put("diverCode", diver);
        body.put("phase", phase);
        body.put("expectedVersion", expectedVersion);
        body.put("correctedDurationMin", correctedDurationMin);
        return write(m, body);
    }

    private static String write(ObjectMapper m, Map<String, Object> body) {
        try {
            return m.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
