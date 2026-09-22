package com.example.starter.perception.api;

import java.util.Map;

/**
 * 单条检测的处理结果。
 *
 * @param detectionId 检测ID
 * @param accepted    是否产生有效变化；false 时不改动任何轨迹版本/最近观测/状态
 * @param trackId     关联或新建的轨迹ID；被拒绝时为 null
 * @param state       处理后轨迹状态；被拒绝时为 null
 * @param version     处理后轨迹版本；被拒绝时为 null
 * @param reason      拒绝原因码（LOW_CONFIDENCE/OUT_OF_ORDER/DUPLICATE/NO_ASSOCIATION 等），接受时为 null
 * @param evidence    可解释关联证据：候选轨迹、距离、门限、选/弃原因、ID切换或重识别说明
 */
public record DetectionResult(
        String detectionId,
        boolean accepted,
        String trackId,
        String state,
        Long version,
        String reason,
        Map<String, Object> evidence
) {

    public static DetectionResult accepted(String detectionId, String trackId, String state, long version,
                                           Map<String, Object> evidence) {
        return new DetectionResult(detectionId, true, trackId, state, version, null, evidence);
    }

    public static DetectionResult rejected(String detectionId, String reason, Map<String, Object> evidence) {
        return new DetectionResult(detectionId, false, null, null, null, reason, evidence);
    }

    public static DetectionResult duplicate(String detectionId, DetectionResult first) {
        return new DetectionResult(detectionId, first.accepted, first.trackId, first.state, first.version,
                "DUPLICATE:" + (first.reason == null ? "ACCEPTED" : first.reason), first.evidence);
    }
}
