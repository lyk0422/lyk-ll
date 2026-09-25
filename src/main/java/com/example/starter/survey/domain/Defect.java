package com.example.starter.survey.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 缺陷项与整改复查链。
 *
 * @param id            主键
 * @param surveyId      所属检验
 * @param severity      严重程度：MAJOR 重大 / MINOR 一般
 * @param description   缺陷描述
 * @param foundDate     发现日期（UTC）
 * @param status        整改状态
 * @param deadline      复查期限（UTC，一般缺陷为发现日+30 日，含当日；重大缺陷发证前必须完成）
 * @param submittedAt   整改材料提交日期（UTC），未提交为 null
 * @param reviewedAt    复查受理时刻（UTC），未受理为 null
 * @param note          复查/驳回说明
 */
public record Defect(
        Long id,
        Long surveyId,
        DefectSeverity severity,
        String description,
        LocalDate foundDate,
        DefectStatus status,
        LocalDate deadline,
        LocalDate submittedAt,
        Instant reviewedAt,
        String note
) {
}
