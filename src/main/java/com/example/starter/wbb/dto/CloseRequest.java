package com.example.starter.wbb.dto;

/**
 * 放行关闭请求。requestId 可空：关闭为终态，对已关闭舱单重复关闭始终幂等成功。
 */
public record CloseRequest(
        String requestId
) {
}
