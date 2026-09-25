package com.example.starter.rt.dto;

/**
 * 疗程结案结果。结案为终态，重复结案同键同参返回幂等成功。
 *
 * @param planVersionId 当前结案版本ID
 * @param versionNo     版本号
 * @param status        终态 CLOSED
 * @param requestId     幂等键
 * @param replayed      是否为同键同参重放
 */
public record ClosureResponse(
        Long planVersionId,
        Integer versionNo,
        String status,
        String requestId,
        boolean replayed) {
}
