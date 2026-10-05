package com.example.starter.feed.dto;

import java.util.List;

/**
 * 批次出栏申请台账响应。
 *
 * @param batchNo      批次号
 * @param applications 申请台账（含 REVOKED 撤销记录），按主键升序
 */
public record SlaughterApplicationTrailResponse(
        String batchNo,
        List<SlaughterApplicationView> applications
) {
}
