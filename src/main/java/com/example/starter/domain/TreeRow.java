package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 行道树档案行，riskLevel 1为最高风险。
 */
public record TreeRow(long id, long segmentId, String treeCode,
                      double tiltDegrees, int diseaseLevel, int ageYears, int windExposure,
                      double riskScore, int riskLevel, LocalDateTime evaluatedAt) {
}
