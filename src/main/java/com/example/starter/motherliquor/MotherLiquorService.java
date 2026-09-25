package com.example.starter.motherliquor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import com.example.starter.error.ApiExceptionHandler.NotFoundException;
import com.example.starter.nuclide.NuclideService;

/**
 * 母液登记与活度库存查询；剩余活度由发药事务扣减。
 */
@Service
public class MotherLiquorService {

    private final JdbcClient jdbc;
    private final NuclideService nuclideService;
    private final Clock clock;

    public MotherLiquorService(JdbcClient jdbc, NuclideService nuclideService, Clock clock) {
        this.jdbc = jdbc;
        this.nuclideService = nuclideService;
        this.clock = clock;
    }

    /**
     * 母液视图。
     *
     * @param id                    主键
     * @param nuclideId             核素
     * @param initialActivityMbq    初始活度，单位 MBq
     * @param measuredAt            初始活度测量时刻（UTC）
     * @param remainingActivityMbq  当前剩余活度，单位 MBq
     * @param remainingMeasuredAt   剩余活度测量时刻（UTC）
     */
    public record MotherLiquorView(long id, long nuclideId, double initialActivityMbq, Instant measuredAt,
                                   double remainingActivityMbq, Instant remainingMeasuredAt) {
    }

    /**
     * 勾稽台账视图：母液初始活度=分装总量+剩余活度+衰变残量（均推算至基准时刻），可复算。
     */
    public record LedgerView(long id, long motherLiquorId, long dispenseId, long deliveryId,
                             double initialActivityMbq, double dispensedActivityMbq,
                             double remainingActivityMbq, double decayResidualMbq,
                             Instant referenceTime) {
    }

    /**
     * 登记母液；初始剩余活度等于初始活度。
     */
    public MotherLiquorView register(long nuclideId, double initialActivityMbq, Instant measuredAt) {
        nuclideService.require(nuclideId);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO mother_liquor (nuclide_id, initial_activity_mbq, measured_at,"
                        + " remaining_activity_mbq, remaining_measured_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")
                .params(nuclideId, initialActivityMbq, Timestamp.from(measuredAt),
                        initialActivityMbq, Timestamp.from(measuredAt), Timestamp.from(clock.instant()))
                .update(keyHolder);
        return new MotherLiquorView(keyHolder.getKey().longValue(), nuclideId, initialActivityMbq,
                measuredAt, initialActivityMbq, measuredAt);
    }

    /**
     * 按主键查询，不存在抛 404。
     */
    public MotherLiquorView require(long id) {
        return jdbc.sql("SELECT id, nuclide_id, initial_activity_mbq, measured_at,"
                        + " remaining_activity_mbq, remaining_measured_at FROM mother_liquor WHERE id = ?")
                .param(id)
                .query((rs, rn) -> new MotherLiquorView(rs.getLong("id"), rs.getLong("nuclide_id"),
                        rs.getDouble("initial_activity_mbq"), rs.getTimestamp("measured_at").toInstant(),
                        rs.getDouble("remaining_activity_mbq"),
                        rs.getTimestamp("remaining_measured_at").toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException("母液不存在: " + id));
    }

    /**
     * 查询母液的勾稽台账。
     */
    public List<LedgerView> ledger(long motherLiquorId) {
        require(motherLiquorId);
        return jdbc.sql("SELECT id, mother_liquor_id, dispense_id, delivery_id, initial_activity_mbq,"
                        + " dispensed_activity_mbq, remaining_activity_mbq, decay_residual_mbq, reference_time"
                        + " FROM ledger WHERE mother_liquor_id = ? ORDER BY id")
                .param(motherLiquorId)
                .query((rs, rn) -> new LedgerView(rs.getLong("id"), rs.getLong("mother_liquor_id"),
                        rs.getLong("dispense_id"), rs.getLong("delivery_id"),
                        rs.getDouble("initial_activity_mbq"), rs.getDouble("dispensed_activity_mbq"),
                        rs.getDouble("remaining_activity_mbq"), rs.getDouble("decay_residual_mbq"),
                        rs.getTimestamp("reference_time").toInstant()))
                .list();
    }
}
