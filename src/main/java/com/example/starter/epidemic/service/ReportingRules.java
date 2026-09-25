package com.example.starter.epidemic.service;

import com.example.starter.epidemic.repo.DiseasePo;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 报告时限规则：自诊断时刻（UTC）起算，甲类 2 小时、乙类 24 小时；
 * 提交时刻恰在时限终点（inclusive）判为及时。
 */
public final class ReportingRules {

    public static final long CATEGORY_A_LIMIT_HOURS = 2;
    public static final long CATEGORY_B_LIMIT_HOURS = 24;

    private ReportingRules() {
    }

    /** 时限小时数。 */
    public static long limitHours(DiseasePo disease) {
        return switch (disease.category) {
            case "A" -> CATEGORY_A_LIMIT_HOURS;
            case "B" -> CATEGORY_B_LIMIT_HOURS;
            default -> throw new BusinessException("DISEASE_INVALID",
                    "病种管理类别未知：" + disease.code, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        };
    }

    public static LocalDateTime deadline(DiseasePo disease, LocalDateTime diagnosisTimeUtc) {
        return diagnosisTimeUtc.plusHours(limitHours(disease));
    }

    /**
     * 判定是否及时：submittedAt &lt;= deadline 为及时（恰在终点判及时）。
     */
    public static boolean isTimely(LocalDateTime deadlineUtc, LocalDateTime submittedAtUtc) {
        return !submittedAtUtc.isAfter(deadlineUtc);
    }

    /** 带偏移量时间统一转 UTC 本地时间存储。 */
    public static LocalDateTime toUtc(OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
