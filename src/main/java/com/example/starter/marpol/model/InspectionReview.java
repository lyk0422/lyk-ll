package com.example.starter.marpol.model;

import java.time.Instant;

/**
 * 签发后补充证据的复查记录，引用原核查号，不改动原结论。
 */
public record InspectionReview(
        Long id,
        Long inspectionId,
        String inspectionNo,
        Long itemId,
        String evidence,
        Instant createdAt) {
}
