package com.example.starter.robot;

import java.util.List;

/**
 * 统一错误响应体。
 *
 * @param requestId 触发错误的请求标识，无法解析时为 null
 * @param code      业务错误码
 * @param message   错误说明
 * @param chain     循环引用时从入口到重复文件的完整链路，其余场景为空
 */
public record ErrorResponse(String requestId, String code, String message, List<String> chain) {
}
