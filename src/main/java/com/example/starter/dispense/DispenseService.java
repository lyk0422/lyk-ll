package com.example.starter.dispense;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.decay.DecayService;
import com.example.starter.error.ApiExceptionHandler.BusinessRejectionException;
import com.example.starter.error.ApiExceptionHandler.IdempotencyConflictException;
import com.example.starter.error.ApiExceptionHandler.NotFoundException;
import com.example.starter.error.ApiExceptionHandler.StateConflictException;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.motherliquor.MotherLiquorService;
import com.example.starter.motherliquor.MotherLiquorService.MotherLiquorView;
import com.example.starter.nuclide.NuclideService;
import com.example.starter.nuclide.NuclideService.NuclideView;

/**
 * 分装计算、双人核对与发药勾稽。
 * 分装活度按衰变公式推算至给药时刻（UTC），与处方活度偏差超过 ±10% 拒绝分装，恰 10% 合法；
 * 分装须两名不同人员先后核对，单人不生效；
 * 发药按“母液初始活度＝分装总量＋剩余活度＋衰变残量”勾稽，不等拒绝发药，
 * 发药与母液活度扣减在同一事务内完成；批次发药完成为终态，迟到测量数据只记迟到事件。
 */
@Service
public class DispenseService {

    /** 分装允许的最大相对偏差：恰 10% 合法，超过拒绝；浮点容差用于边界判定。 */
    static final double MAX_DEVIATION = 0.10;
    private static final double EPSILON = 1e-9;
    /** 勾稽残量容差，单位 MBq；超过视为勾稽不等。 */
    static final double RECONCILE_TOLERANCE_MBQ = 1e-6;

    private final JdbcClient jdbc;
    private final MotherLiquorService motherLiquorService;
    private final NuclideService nuclideService;
    private final DecayService decayService;
    private final Clock clock;

    public DispenseService(JdbcClient jdbc, MotherLiquorService motherLiquorService,
                           NuclideService nuclideService, DecayService decayService, Clock clock) {
        this.jdbc = jdbc;
        this.motherLiquorService = motherLiquorService;
        this.nuclideService = nuclideService;
        this.decayService = decayService;
        this.clock = clock;
    }

    /**
     * 分装批次视图。
     *
     * @param status 状态：PENDING_CHECK 待核对 / FIRST_CHECKED 已首核 / CHECKED 已双核 /
     *               DELIVERED 已发药（终态）/ REJECTED 已拒绝（终态）
     */
    public record DispenseView(long id, String requestId, long motherLiquorId,
                               double prescriptionActivityMbq, double calculatedActivityMbq,
                               Instant administerAt, double deviation, String status, boolean replayed) {
    }

    /**
     * 核对记录视图。
     */
    public record CheckView(long id, String requestId, long dispenseId, String checkerId, int seq,
                            String dispenseStatus, boolean replayed) {
    }

    /**
     * 发药结果视图：勾稽分量均推算至基准时刻（剩余活度测量时刻，UTC），可复算。
     */
    public record DeliveryView(long id, String requestId, long dispenseId,
                               double remainingActivityMbq, Instant remainingMeasuredAt,
                               double initialAtRefMbq, double dispensedAtRefMbq,
                               double decayResidualMbq, boolean replayed) {
    }

    /**
     * 测量数据视图；late=true 表示批次完成后到达的迟到事件。
     */
    public record MeasurementView(long id, String requestId, long dispenseId,
                                  double measuredActivityMbq, Instant measuredAt,
                                  boolean late, boolean replayed) {
    }

    /**
     * 分装计算（幂等）。偏差超过 ±10% 时持久化 REJECTED 终态并返回拒绝结果。
     */
    @Transactional
    public DispenseView calculate(String requestId, long motherLiquorId,
                                  double prescriptionActivityMbq, Instant administerAt) {
        String fingerprint = Fingerprints.of(requestId, motherLiquorId, prescriptionActivityMbq,
                administerAt);
        Optional<StoredDispense> existing = findDispenseByRequestId(requestId);
        if (existing.isPresent()) {
            StoredDispense stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(true);
        }

        MotherLiquorView mother = motherLiquorService.require(motherLiquorId);
        NuclideView nuclide = nuclideService.require(mother.nuclideId());
        double calculated = decayService.decayTo(mother.initialActivityMbq(), mother.measuredAt(),
                administerAt, nuclide.halfLifeSeconds());
        double deviation = (calculated - prescriptionActivityMbq) / prescriptionActivityMbq;
        boolean rejected = Math.abs(deviation) > MAX_DEVIATION + EPSILON;
        String status = rejected ? "REJECTED" : "PENDING_CHECK";

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO dispense (request_id, mother_liquor_id, prescription_activity_mbq,"
                            + " calculated_activity_mbq, administer_at, deviation, status, fingerprint,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(requestId, motherLiquorId, prescriptionActivityMbq, calculated,
                            Timestamp.from(administerAt), deviation, status, fingerprint,
                            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()))
                    .update(keyHolder);
        } catch (DuplicateKeyException e) {
            StoredDispense stored = findDispenseByRequestId(requestId)
                    .orElseThrow(() -> e);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(true);
        }
        return new DispenseView(keyHolder.getKey().longValue(), requestId, motherLiquorId,
                prescriptionActivityMbq, calculated, administerAt, deviation, status, false);
    }

    /**
     * 双人核对（幂等）：须两名不同人员先后核对，单人不生效。
     */
    @Transactional
    public CheckView check(String requestId, long dispenseId, String checkerId) {
        String fingerprint = Fingerprints.of(requestId, dispenseId, checkerId);
        Optional<StoredCheck> existing = findCheckByRequestId(requestId);
        if (existing.isPresent()) {
            StoredCheck stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(statusOf(dispenseId), true);
        }

        StoredDispense dispense = requireDispense(dispenseId);
        int seq;
        switch (dispense.status()) {
            case "PENDING_CHECK" -> seq = 1;
            case "FIRST_CHECKED" -> {
                String firstChecker = jdbc.sql(
                                "SELECT checker_id FROM dispense_check WHERE dispense_id = ? AND seq = 1")
                        .param(dispenseId).query(String.class).single();
                if (firstChecker.equals(checkerId)) {
                    throw new StateConflictException("双人核对须两名不同人员，单人重复核对不生效: " + checkerId);
                }
                seq = 2;
            }
            case "CHECKED" -> throw new StateConflictException("分装批次已完成双人核对: " + dispenseId);
            default -> throw new StateConflictException(
                    "分装批次状态不允许核对: " + dispenseId + " status=" + dispense.status());
        }

        String newStatus = seq == 1 ? "FIRST_CHECKED" : "CHECKED";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO dispense_check (request_id, dispense_id, checker_id, seq, fingerprint,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(requestId, dispenseId, checkerId, seq, fingerprint,
                            Timestamp.from(clock.instant()))
                    .update(keyHolder);
        } catch (DuplicateKeyException e) {
            StoredCheck stored = findCheckByRequestId(requestId).orElseThrow(() -> e);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(statusOf(dispenseId), true);
        }
        jdbc.sql("UPDATE dispense SET status = ?, updated_at = ? WHERE id = ?")
                .params(newStatus, Timestamp.from(clock.instant()), dispenseId)
                .update();
        return new CheckView(keyHolder.getKey().longValue(), requestId, dispenseId, checkerId, seq,
                newStatus, false);
    }

    /**
     * 发药（幂等）：勾稽“母液初始活度＝分装总量＋剩余活度＋衰变残量”，不等拒绝发药；
     * 发药记录、勾稽台账与母液活度扣减在同一事务内完成。
     */
    @Transactional
    public DeliveryView deliver(String requestId, long dispenseId, double remainingActivityMbq,
                                Instant remainingMeasuredAt) {
        String fingerprint = Fingerprints.of(requestId, dispenseId, remainingActivityMbq,
                remainingMeasuredAt);
        Optional<StoredDelivery> existing = findDeliveryByRequestId(requestId);
        if (existing.isPresent()) {
            StoredDelivery stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(true);
        }

        StoredDispense dispense = requireDispense(dispenseId);
        if ("DELIVERED".equals(dispense.status())) {
            throw new StateConflictException("批次发药完成为终态，不允许重复完成: " + dispenseId);
        }
        if (!"CHECKED".equals(dispense.status())) {
            throw new StateConflictException(
                    "分装批次未完成双人核对，不允许发药: " + dispenseId + " status=" + dispense.status());
        }

        MotherLiquorView mother = motherLiquorService.require(dispense.motherLiquorId());
        NuclideView nuclide = nuclideService.require(mother.nuclideId());
        // 勾稽基准时刻取剩余活度测量时刻；分装总量为处方活度（实际分装剂量），
        // 母液初始活度与分装总量均推算至基准时刻，衰变残量=初始-分装-剩余
        double initialAtRef = decayService.decayTo(mother.initialActivityMbq(), mother.measuredAt(),
                remainingMeasuredAt, nuclide.halfLifeSeconds());
        double dispensedAtRef = decayService.decayTo(dispense.prescriptionActivityMbq(),
                dispense.administerAt(), remainingMeasuredAt, nuclide.halfLifeSeconds());
        double decayResidual = initialAtRef - dispensedAtRef - remainingActivityMbq;
        if (Math.abs(decayResidual) > RECONCILE_TOLERANCE_MBQ) {
            throw new BusinessRejectionException("活度勾稽不等，拒绝发药",
                    Map.of("dispenseId", dispenseId,
                            "initialAtRefMbq", initialAtRef,
                            "dispensedAtRefMbq", dispensedAtRef,
                            "remainingActivityMbq", remainingActivityMbq,
                            "decayResidualMbq", decayResidual));
        }

        GeneratedKeyHolder deliveryKey = new GeneratedKeyHolder();
        long deliveryId;
        try {
            jdbc.sql("INSERT INTO delivery (request_id, dispense_id, remaining_activity_mbq,"
                            + " remaining_measured_at, initial_at_ref_mbq, dispensed_at_ref_mbq,"
                            + " decay_residual_mbq, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(requestId, dispenseId, remainingActivityMbq,
                            Timestamp.from(remainingMeasuredAt), initialAtRef, dispensedAtRef,
                            decayResidual, fingerprint, Timestamp.from(clock.instant()))
                    .update(deliveryKey);
            deliveryId = deliveryKey.getKey().longValue();
        } catch (DuplicateKeyException e) {
            // dispense_id 唯一约束兜底：并发发药唯一受理；同键重放返回首次结果
            Optional<StoredDelivery> replay = findDeliveryByRequestId(requestId);
            if (replay.isPresent() && replay.get().fingerprint().equals(fingerprint)) {
                return replay.get().toView(true);
            }
            if (replay.isPresent()) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            throw new StateConflictException("批次发药完成为终态，不允许重复完成: " + dispenseId);
        }

        jdbc.sql("INSERT INTO ledger (mother_liquor_id, dispense_id, delivery_id, initial_activity_mbq,"
                        + " dispensed_activity_mbq, remaining_activity_mbq, decay_residual_mbq,"
                        + " reference_time, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(dispense.motherLiquorId(), dispenseId, deliveryId, initialAtRef, dispensedAtRef,
                        remainingActivityMbq, decayResidual, Timestamp.from(remainingMeasuredAt),
                        Timestamp.from(clock.instant()))
                .update();
        // 发药与母液活度扣减同事务：剩余活度更新为发药时实测值
        jdbc.sql("UPDATE mother_liquor SET remaining_activity_mbq = ?, remaining_measured_at = ?"
                        + " WHERE id = ?")
                .params(remainingActivityMbq, Timestamp.from(remainingMeasuredAt),
                        dispense.motherLiquorId())
                .update();
        jdbc.sql("UPDATE dispense SET status = 'DELIVERED', updated_at = ? WHERE id = ?")
                .params(Timestamp.from(clock.instant()), dispenseId)
                .update();
        return new DeliveryView(deliveryId, requestId, dispenseId, remainingActivityMbq,
                remainingMeasuredAt, initialAtRef, dispensedAtRef, decayResidual, false);
    }

    /**
     * 登记批次测量数据（幂等）；批次发药完成后到达的数据只记迟到事件。
     */
    @Transactional
    public MeasurementView recordMeasurement(String requestId, long dispenseId,
                                             double measuredActivityMbq, Instant measuredAt) {
        String fingerprint = Fingerprints.of(requestId, dispenseId, measuredActivityMbq, measuredAt);
        Optional<StoredMeasurement> existing = findMeasurementByRequestId(requestId);
        if (existing.isPresent()) {
            StoredMeasurement stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(true);
        }

        StoredDispense dispense = requireDispense(dispenseId);
        boolean late = "DELIVERED".equals(dispense.status());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO measurement (request_id, dispense_id, measured_activity_mbq,"
                            + " measured_at, late, fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(requestId, dispenseId, measuredActivityMbq, Timestamp.from(measuredAt), late,
                            fingerprint, Timestamp.from(clock.instant()))
                    .update(keyHolder);
        } catch (DuplicateKeyException e) {
            StoredMeasurement stored = findMeasurementByRequestId(requestId).orElseThrow(() -> e);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return stored.toView(true);
        }
        return new MeasurementView(keyHolder.getKey().longValue(), requestId, dispenseId,
                measuredActivityMbq, measuredAt, late, false);
    }

    /**
     * 查询分装批次详情（含双人核对链，可复算）。
     */
    public DispenseDetail detail(long dispenseId) {
        StoredDispense dispense = requireDispense(dispenseId);
        List<CheckView> checks = jdbc.sql(
                        "SELECT id, request_id, dispense_id, checker_id, seq FROM dispense_check"
                                + " WHERE dispense_id = ? ORDER BY seq")
                .param(dispenseId)
                .query((rs, rn) -> new CheckView(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("dispense_id"), rs.getString("checker_id"), rs.getInt("seq"),
                        dispense.status(), false))
                .list();
        return new DispenseDetail(dispense.toView(false), checks);
    }

    /**
     * 分装批次详情：批次视图 + 核对链。
     */
    public record DispenseDetail(DispenseView dispense, List<CheckView> checks) {
    }

    private record StoredDispense(long id, String requestId, long motherLiquorId,
                                  double prescriptionActivityMbq, double calculatedActivityMbq,
                                  Instant administerAt, double deviation, String status,
                                  String fingerprint) {
        DispenseView toView(boolean replayed) {
            return new DispenseView(id, requestId, motherLiquorId, prescriptionActivityMbq,
                    calculatedActivityMbq, administerAt, deviation, status, replayed);
        }
    }

    private record StoredCheck(long id, String requestId, long dispenseId, String checkerId, int seq,
                               String fingerprint) {
        CheckView toView(String dispenseStatus, boolean replayed) {
            return new CheckView(id, requestId, dispenseId, checkerId, seq, dispenseStatus, replayed);
        }
    }

    private record StoredDelivery(long id, String requestId, long dispenseId,
                                  double remainingActivityMbq, Instant remainingMeasuredAt,
                                  double initialAtRefMbq, double dispensedAtRefMbq,
                                  double decayResidualMbq, String fingerprint) {
        DeliveryView toView(boolean replayed) {
            return new DeliveryView(id, requestId, dispenseId, remainingActivityMbq,
                    remainingMeasuredAt, initialAtRefMbq, dispensedAtRefMbq, decayResidualMbq, replayed);
        }
    }

    private record StoredMeasurement(long id, String requestId, long dispenseId,
                                     double measuredActivityMbq, Instant measuredAt, boolean late,
                                     String fingerprint) {
        MeasurementView toView(boolean replayed) {
            return new MeasurementView(id, requestId, dispenseId, measuredActivityMbq, measuredAt,
                    late, replayed);
        }
    }

    private StoredDispense requireDispense(long dispenseId) {
        return jdbc.sql("SELECT id, request_id, mother_liquor_id, prescription_activity_mbq,"
                        + " calculated_activity_mbq, administer_at, deviation, status, fingerprint"
                        + " FROM dispense WHERE id = ?")
                .param(dispenseId)
                .query((rs, rn) -> new StoredDispense(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("mother_liquor_id"), rs.getDouble("prescription_activity_mbq"),
                        rs.getDouble("calculated_activity_mbq"),
                        rs.getTimestamp("administer_at").toInstant(), rs.getDouble("deviation"),
                        rs.getString("status"), rs.getString("fingerprint")))
                .optional()
                .orElseThrow(() -> new NotFoundException("分装批次不存在: " + dispenseId));
    }

    private Optional<StoredDispense> findDispenseByRequestId(String requestId) {
        return jdbc.sql("SELECT id, request_id, mother_liquor_id, prescription_activity_mbq,"
                        + " calculated_activity_mbq, administer_at, deviation, status, fingerprint"
                        + " FROM dispense WHERE request_id = ?")
                .param(requestId)
                .query((rs, rn) -> new StoredDispense(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("mother_liquor_id"), rs.getDouble("prescription_activity_mbq"),
                        rs.getDouble("calculated_activity_mbq"),
                        rs.getTimestamp("administer_at").toInstant(), rs.getDouble("deviation"),
                        rs.getString("status"), rs.getString("fingerprint")))
                .optional();
    }

    private Optional<StoredCheck> findCheckByRequestId(String requestId) {
        return jdbc.sql("SELECT id, request_id, dispense_id, checker_id, seq, fingerprint"
                        + " FROM dispense_check WHERE request_id = ?")
                .param(requestId)
                .query((rs, rn) -> new StoredCheck(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("dispense_id"), rs.getString("checker_id"), rs.getInt("seq"),
                        rs.getString("fingerprint")))
                .optional();
    }

    private Optional<StoredDelivery> findDeliveryByRequestId(String requestId) {
        return jdbc.sql("SELECT id, request_id, dispense_id, remaining_activity_mbq,"
                        + " remaining_measured_at, initial_at_ref_mbq, dispensed_at_ref_mbq,"
                        + " decay_residual_mbq, fingerprint FROM delivery WHERE request_id = ?")
                .param(requestId)
                .query((rs, rn) -> new StoredDelivery(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("dispense_id"), rs.getDouble("remaining_activity_mbq"),
                        rs.getTimestamp("remaining_measured_at").toInstant(),
                        rs.getDouble("initial_at_ref_mbq"), rs.getDouble("dispensed_at_ref_mbq"),
                        rs.getDouble("decay_residual_mbq"), rs.getString("fingerprint")))
                .optional();
    }

    private Optional<StoredMeasurement> findMeasurementByRequestId(String requestId) {
        return jdbc.sql("SELECT id, request_id, dispense_id, measured_activity_mbq, measured_at, late,"
                        + " fingerprint FROM measurement WHERE request_id = ?")
                .param(requestId)
                .query((rs, rn) -> new StoredMeasurement(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("dispense_id"), rs.getDouble("measured_activity_mbq"),
                        rs.getTimestamp("measured_at").toInstant(), rs.getBoolean("late"),
                        rs.getString("fingerprint")))
                .optional();
    }

    private String statusOf(long dispenseId) {
        return jdbc.sql("SELECT status FROM dispense WHERE id = ?")
                .param(dispenseId).query(String.class).single();
    }
}
