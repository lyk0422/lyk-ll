package com.example.starter.marpol.model;

import com.example.starter.marpol.domain.Decision;
import com.example.starter.marpol.domain.ItemStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 核查条目：每个排放记录版本族一条，结论钉住所依据的具体版本。
 *
 * @param pinnedRecordId 结论所依据版本行 id；签发时以当时已确认（最新有效）版本为准
 * @param status 整改状态，仅 VIOLATION 缺陷项非空；CONFIRMED 条目为空
 * @param dueDate 整改截止日（UTC）=核查日+14 自然日，恰第 14 日关闭有效
 */
public record InspectionItem(
        Long id,
        Long inspectionId,
        Long rootRecordId,
        Long pinnedRecordId,
        Integer pinnedVersionNo,
        Decision decision,
        String reason,
        ItemStatus status,
        LocalDate dueDate,
        Instant rectifiedAt,
        Instant createdAt,
        Instant updatedAt) {
}
