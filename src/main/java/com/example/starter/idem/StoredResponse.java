package com.example.starter.idem;

/**
 * 已登记请求的响应快照：HTTP 状态码与 JSON 报文体。
 *
 * @param status HTTP 状态码
 * @param body   响应报文（JSON）
 */
public record StoredResponse(int status, String body) {

    public static StoredResponse ok(String body) {
        return new StoredResponse(200, body);
    }
}
