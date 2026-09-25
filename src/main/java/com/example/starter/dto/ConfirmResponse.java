package com.example.starter.dto;

import java.util.List;

/**
 * 发车确认响应: confirmed=false 时 reasons 给出拒绝原因(HTTP 422)。
 */
public record ConfirmResponse(long operationId, boolean confirmed, List<String> reasons) {
}
