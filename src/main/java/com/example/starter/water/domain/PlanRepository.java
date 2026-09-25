package com.example.starter.water.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 月度取水计划申报与幂等记录仓储。
 */
public interface PlanRepository {

    /**
     * 月度计划确认版本行；每月仅保留“最后已确认版本”，修改即追加新版本。
     *
     * @param version         版本号，从 1 递增
     * @param declaredVolume  原始申报水量（m³）
     * @param acceptedVolume  受理水量（m³）；限取减半时为申报量一半
     * @param restricted      受理时是否处于限取
     * @param restrictionId   触发减半的限取事件 ID，未限取为 null
     * @param requestId       产生本版本的申报 requestId
     */
    record PlanVersionRow(
            Long id,
            Long userId,
            String month,
            int version,
            BigDecimal declaredVolume,
            BigDecimal acceptedVolume,
            boolean restricted,
            Long restrictionId,
            String requestId,
            Instant confirmedAt) {
    }

    /**
     * 幂等记录行；唯一键为 requestId，指纹含 user_id、month、declared_volume。
     *
     * @param planVersionId 首次受理产生的版本 ID；首次请求若被拒绝则为 null
     * @param rejected      首次请求是否被额满等规则拒绝
     * @param rejectReason  拒绝原因
     */
    record IdempotencyRow(
            String requestId,
            Long userId,
            String month,
            BigDecimal declaredVolume,
            Long planVersionId,
            boolean rejected,
            String rejectReason,
            Instant firstSeenAt) {
    }

    Optional<IdempotencyRow> findIdempotency(String requestId);

    void insertIdempotency(IdempotencyRow row);

    /**
     * 追加新月计划确认版本（version 由库内该 user+month 最大版本 +1 得到）。
     */
    PlanVersionRow appendVersion(
            Long userId,
            String month,
            BigDecimal declaredVolume,
            BigDecimal acceptedVolume,
            boolean restricted,
            Long restrictionId,
            String requestId,
            Instant confirmedAt);

    /**
     * 某取水户指定自然月的最后已确认版本。
     */
    Optional<PlanVersionRow> findLatestVersion(Long userId, String month);

    /**
     * 某取水户指定年度（UTC）全部月份的最后已确认版本，按月份升序。
     */
    List<PlanVersionRow> findLatestVersionsOfYear(Long userId, int year);

    /**
     * 按版本 ID 加载。
     */
    Optional<PlanVersionRow> findVersionById(Long id);
}
