package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 外部船旗国检查报告的一个修订版本。
 * 同一报告编号 {@code reportNo} 可有多条修订行，{@code revisionNo} 从 1 递增；
 * 只有一条为 {@link ExternalReportStatus#CURRENT}，历史行不可变。
 *
 * @param id               主键
 * @param reportNo         报告编号，全局唯一
 * @param revisionNo       修订号，从 1 开始
 * @param shipImo          被检查船舶 IMO
 * @param flagCode         船旗代码
 * @param completedAt      检查完成时刻（UTC 精确瞬时）
 * @param signatureMarker  合成签认标记
 * @param status           CURRENT / SUPERSEDED
 * @param supersededBy     被哪条新修订行替代，未替代为 null
 * @param requestId        首次写入该修订的幂等键
 * @param fingerprint      业务指纹
 * @param createdAt        创建时刻（UTC）
 */
public record ExternalReport(
        Long id,
        String reportNo,
        int revisionNo,
        String shipImo,
        String flagCode,
        Instant completedAt,
        String signatureMarker,
        ExternalReportStatus status,
        Long supersededBy,
        String requestId,
        String fingerprint,
        Instant createdAt
) {
}
