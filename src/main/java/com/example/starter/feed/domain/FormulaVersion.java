package com.example.starter.feed.domain;

import java.time.Instant;
import java.util.List;

/**
 * 配方版本（含组成项快照）。
 *
 * @param formulaCode   配方编码
 * @param versionNo     版本号
 * @param status        SUBMITTED / REJECTED / APPROVED
 * @param targetSpecies 目标动物种类；非含药可 null
 * @param medicated     是否含药物添加剂
 * @param fingerprint   提交指纹
 * @param rejectReasons 合规拒绝原因；通过为空列表
 * @param createdAt     提交时刻（UTC）
 * @param approvedAt    批准时刻（UTC）；未批准为 null
 * @param items         组成项快照
 */
public record FormulaVersion(
        String formulaCode,
        int versionNo,
        FormulaVersionStatus status,
        String targetSpecies,
        boolean medicated,
        String fingerprint,
        List<String> rejectReasons,
        Instant createdAt,
        Instant approvedAt,
        List<FormulaItem> items) {
}
