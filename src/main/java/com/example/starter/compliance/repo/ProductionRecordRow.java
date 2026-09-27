package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 量产上报记录行；lineCode 为 NULL 表示原未指定生产线入口写入的记录。
 */
public record ProductionRecordRow(
        Long id,
        Long filingId,
        String batchNo,
        BigDecimal quantityKg,
        long reportedAt,
        String lineCode,
        Integer occupiedVersion,
        Long clearanceCertificateId) {
}
