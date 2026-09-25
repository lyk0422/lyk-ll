package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 结案后迟到分次事件视图（仅登记，不入分次台账）。
 *
 * @param id              事件ID
 * @param planVersionId   指向版本ID
 * @param versionNo       指向版本号
 * @param fractionNo      迟到分次号
 * @param requestId       迟到事件 requestId
 * @param createdAt       事件登记时间
 */
public record LateFractionItem(
        Long id,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        String requestId,
        OffsetDateTime createdAt) {
}
