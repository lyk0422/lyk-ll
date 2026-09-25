package com.example.starter.compliance.service;

import com.example.starter.compliance.domain.FormulaContent;
import com.example.starter.compliance.engine.ReviewModels.ReviewReport;
import com.example.starter.compliance.repo.LimitRow;
import java.util.Map;

/**
 * 配方批准时冻结的版本快照：量产备案与备案复审均按此快照中的配方内容执行；
 * limits 为批准时该食品分类下各添加剂限量（g/kg），report 为批准所依据的合规审查报告。
 */
public record ApprovedSnapshot(
        FormulaContent content,
        Map<String, LimitRow> limits,
        ReviewReport report,
        long approvedAt) {
}
