package com.example.starter.blast.api;

/**
 * 被幂等记录缓存的业务拒绝结果体（首次失败也可按同键重放）。
 *
 * @param rejected 是否拒绝（恒为 true，供调用方显式识别）
 * @param status   首次响应 HTTP 状态码
 * @param message  拒绝原因
 */
public record RejectionResponse(boolean rejected, int status, String message) {

    public static RejectionResponse of(int status, String message) {
        return new RejectionResponse(true, status, message);
    }
}
