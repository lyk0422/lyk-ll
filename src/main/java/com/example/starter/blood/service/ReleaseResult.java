package com.example.starter.blood.service;

import com.example.starter.blood.domain.UnitStatus;

/**
 * 放行处理结果。
 *
 * @param requestId  放行请求幂等键
 * @param outcome    处理结果：RELEASED 放行成功 / REJECTED 拒绝
 * @param unitStatus 响应时单位真实状态
 * @param message    结果说明，null 表示无
 * @param replayed   是否为同键重放（返回首次结果）
 */
public record ReleaseResult(String requestId, String outcome, UnitStatus unitStatus, String message,
                            boolean replayed) {
}
