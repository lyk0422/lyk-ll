package com.example.starter.water.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 河流生态流量预警（限取）事件仓储。
 */
public interface RestrictionRepository {

    /**
     * 限取事件行；同河段新事件覆盖旧事件的有效状态。
     */
    record RestrictionRow(
            Long id,
            String riverSection,
            Instant effectiveAt,
            String reason,
            boolean active,
            Instant createdAt) {
    }

    /**
     * 追加限取事件并立即置为生效（同河段历史事件置为非激活）。
     */
    RestrictionRow trigger(String riverSection, Instant effectiveAt, String reason, Instant now);

    /**
     * 河段当前激活限取事件。
     */
    Optional<RestrictionRow> findActive(String riverSection);

    /**
     * 按事件 ID 查询（幂等回放还原受理时限取生效时刻）。
     */
    Optional<RestrictionRow> findById(Long id);

    /**
     * 全部当前激活事件。
     */
    List<RestrictionRow> findAllActive();
}
