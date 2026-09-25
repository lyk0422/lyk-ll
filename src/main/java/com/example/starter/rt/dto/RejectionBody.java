package com.example.starter.rt.dto;

import java.util.List;

/**
 * 业务拒绝响应体（422/409 等首次拒绝同样参与 requestId 幂等留痕与重放）。
 *
 * @param code    业务错误码
 * @param message 拒绝原因
 * @param alert   是否同时触发告警（如跳次拒绝）
 * @param details 附加明细（如逐项约束核对结果），无明细时为空列表
 */
public record RejectionBody(String code, String message, boolean alert, List<Object> details) {

    public static RejectionBody of(String code, String message) {
        return new RejectionBody(code, message, false, List.of());
    }

    public static RejectionBody alert(String code, String message) {
        return new RejectionBody(code, message, true, List.of());
    }

    public static RejectionBody withDetails(String code, String message, List<Object> details) {
        return new RejectionBody(code, message, false, details);
    }
}
