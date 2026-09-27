package com.example.starter.cosmetic.child.domain;

import java.time.Instant;

/**
 * 儿童分龄形式审查明细，可逐条复算。
 *
 * @param id          主键
 * @param filingNo    备案编号
 * @param versionNo   修订版本号
 * @param ruleCode    规则编码（年龄区间/分档命中/功效目录/分档安全报告/覆盖范围/标识/浓度等）
 * @param passed      是否通过
 * @param detail      审查结论说明（含分档、限值、覆盖范围口径与实测值）
 * @param createdAtUtc 记录时间(UTC)
 */
public record ChildReviewDetail(Long id,
                                String filingNo,
                                int versionNo,
                                String ruleCode,
                                boolean passed,
                                String detail,
                                Instant createdAtUtc) {
}
