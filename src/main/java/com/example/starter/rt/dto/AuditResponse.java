package com.example.starter.rt.dto;

import java.util.List;

/**
 * 物理审核结果。
 *
 * @param planVersionId     计划版本ID
 * @param versionNo         版本号
 * @param status            审核后状态 REVIEWED
 * @param requestId         幂等键
 * @param replayed          是否为同键同参重放
 * @param totalDoseCheckOk  总剂量勾稽：分次剂量×次数=处方总剂量
 * @param passed            全部约束项是否通过
 * @param items             逐项核对结果
 */
public record AuditResponse(
        Long planVersionId,
        Integer versionNo,
        String status,
        String requestId,
        boolean replayed,
        boolean totalDoseCheckOk,
        boolean passed,
        List<ConstraintCheckItem> items) {
}
