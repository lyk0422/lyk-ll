package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 形式/异议审查明细，可逐条复算。
 *
 * @param id          主键
 * @param filingNo    备案编号
 * @param versionNo   版本号
 * @param stage       审查阶段：FORM / OBJECTION
 * @param ruleCode    规则编码
 * @param passed      是否通过
 * @param detail      审查结论说明（含期限口径与实测值）
 * @param createdAtUtc 记录时间(UTC)
 */
public record ReviewDetail(Long id,
                           String filingNo,
                           int versionNo,
                           String stage,
                           String ruleCode,
                           boolean passed,
                           String detail,
                           Instant createdAtUtc) {
}
