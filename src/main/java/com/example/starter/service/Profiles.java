package com.example.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 减压剖面构造：下潜→水底停留→上升三段，速率上限 18m/min。
 * 剖面与速率、台账均可由原始记录复算。
 */
final class Profiles {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Profiles() {
    }

    static String buildJson(int depthM, int bottomMin) {
        int transitMin = (int) Math.ceil(depthM / (double) DiveService.RATE_LIMIT);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("depthM", depthM);
        root.put("bottomMin", bottomMin);
        root.put("rateLimitMMin", DiveService.RATE_LIMIT);
        root.put("segments", List.of(
                segment("DESCENT", 0, depthM, transitMin),
                segment("BOTTOM", depthM, depthM, bottomMin),
                segment("ASCENT", depthM, 0, transitMin)));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("减压剖面序列化失败", e);
        }
    }

    private static Map<String, Object> segment(String phase, int from, int to, int minutes) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("phase", phase);
        s.put("fromDepthM", from);
        s.put("toDepthM", to);
        s.put("durationMin", minutes);
        s.put("rateLimitMMin", DiveService.RATE_LIMIT);
        return s;
    }
}
