package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;

import java.time.Instant;

/**
 * 儿童备案详情响应（聚合 + 当前最新修订）。时间均为 UTC。
 */
public record ChildFilingResponse(
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String status,
        int currentRevisionNo,
        ChildRevisionResponse currentRevision,
        Instant createdAtUtc,
        Instant updatedAtUtc) {

    /** 由聚合与最新修订构造响应。 */
    public static ChildFilingResponse from(ChildFilingRecord filing, ChildRevisionRecord current) {
        return new ChildFilingResponse(
                filing.filingNo(), filing.productCode(), filing.productName(),
                filing.versionNo(), filing.status().name(), filing.currentRevisionNo(),
                current == null ? null : ChildRevisionResponse.from(current),
                filing.createdAtUtc(), filing.updatedAtUtc());
    }
}
