package com.example.starter.web.dto;

import com.example.starter.domain.UnitStatus;

/**
 * 放行响应。
 *
 * @param requestId     幂等键
 * @param barcode       成分血条码
 * @param outcome       RELEASED 成功 / REJECTED 拒绝
 * @param currentStatus 该单位当前真实状态
 * @param detail        拒绝原因，成功时为 null
 */
public record ReleaseResponse(
        String requestId,
        String barcode,
        String outcome,
        UnitStatus currentStatus,
        String detail) {
}
