package com.example.starter.dailycheck;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibrator.CalibratorService;
import com.example.starter.calibrator.CalibratorService.ReferenceSourceView;
import com.example.starter.decay.DecayService;
import com.example.starter.error.ApiExceptionHandler.IdempotencyConflictException;
import com.example.starter.error.ApiExceptionHandler.StateConflictException;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.nuclide.NuclideService;
import com.example.starter.nuclide.NuclideService.NuclideView;

/**
 * 活度计日检：以参考源（合成）校验，偏差超过 ±5% 当日禁用，恰 5% 合法可用。
 * 以 requestId 幂等；同一活度计同一 UTC 自然日只允许一个校准会话，并发日检唯一受理。
 */
@Service
public class DailyCheckService {

    /** 日检允许的最大相对偏差：恰 5% 合法，超过禁用；浮点容差用于边界判定。 */
    static final double MAX_DEVIATION = 0.05;
    private static final double EPSILON = 1e-9;

    private final JdbcClient jdbc;
    private final CalibratorService calibratorService;
    private final NuclideService nuclideService;
    private final DecayService decayService;
    private final Clock clock;

    public DailyCheckService(JdbcClient jdbc, CalibratorService calibratorService,
                             NuclideService nuclideService, DecayService decayService, Clock clock) {
        this.jdbc = jdbc;
        this.calibratorService = calibratorService;
        this.nuclideService = nuclideService;
        this.decayService = decayService;
        this.clock = clock;
    }

    /**
     * 日检结果视图。
     *
     * @param id                   主键
     * @param requestId            幂等请求键
     * @param calibratorId         活度计
     * @param checkDate            日检日期（UTC 自然日）
     * @param expectedActivityMbq  参考源衰变推算至日检时刻的期望活度，单位 MBq
     * @param measuredActivityMbq  实测活度，单位 MBq
     * @param deviation            相对偏差 (实测-期望)/期望
     * @param passed               是否通过；false 表示当日禁用
     * @param replayed             是否幂等重放（同键同参返回首次结果）
     */
    public record DailyCheckView(long id, String requestId, long calibratorId, LocalDate checkDate,
                                 double expectedActivityMbq, double measuredActivityMbq,
                                 double deviation, boolean passed, boolean replayed) {
    }

    /**
     * 执行日检（幂等）。
     *
     * @param requestId           幂等请求键
     * @param calibratorId        活度计
     * @param referenceSourceId   参考源
     * @param measuredActivityMbq 实测活度，单位 MBq
     * @param checkedAt           日检时刻（UTC）
     */
    @Transactional
    public DailyCheckView check(String requestId, long calibratorId, long referenceSourceId,
                                double measuredActivityMbq, Instant checkedAt) {
        String fingerprint = Fingerprints.of(requestId, calibratorId, referenceSourceId,
                measuredActivityMbq, checkedAt);
        var existing = findByRequestId(requestId);
        if (existing.isPresent()) {
            DailyCheckView view = existing.get();
            String storedFingerprint = jdbc.sql("SELECT fingerprint FROM daily_check WHERE request_id = ?")
                    .param(requestId).query(String.class).single();
            if (!storedFingerprint.equals(fingerprint)) {
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            return new DailyCheckView(view.id(), view.requestId(), view.calibratorId(), view.checkDate(),
                    view.expectedActivityMbq(), view.measuredActivityMbq(), view.deviation(),
                    view.passed(), true);
        }

        ReferenceSourceView source = calibratorService.requireReferenceSource(referenceSourceId);
        calibratorService.requireCalibrator(calibratorId);
        NuclideView nuclide = nuclideService.require(source.nuclideId());
        double expected = decayService.decayTo(source.activityMbq(), source.measuredAt(), checkedAt,
                nuclide.halfLifeSeconds());
        double deviation = (measuredActivityMbq - expected) / expected;
        boolean passed = Math.abs(deviation) <= MAX_DEVIATION + EPSILON;
        LocalDate checkDate = LocalDate.ofInstant(checkedAt, ZoneOffset.UTC);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO daily_check (request_id, calibrator_id, check_date,"
                            + " expected_activity_mbq, measured_activity_mbq, deviation, passed,"
                            + " fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(requestId, calibratorId, java.sql.Date.valueOf(checkDate), expected,
                            measuredActivityMbq, deviation, passed, fingerprint,
                            Timestamp.from(clock.instant()))
                    .update(keyHolder);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底：request_id 重放竞态或同一活度计同日并发日检，唯一受理
            var replayed = findByRequestId(requestId);
            if (replayed.isPresent()) {
                String storedFingerprint = jdbc.sql("SELECT fingerprint FROM daily_check WHERE request_id = ?")
                        .param(requestId).query(String.class).single();
                if (storedFingerprint.equals(fingerprint)) {
                    DailyCheckView view = replayed.get();
                    return new DailyCheckView(view.id(), view.requestId(), view.calibratorId(),
                            view.checkDate(), view.expectedActivityMbq(), view.measuredActivityMbq(),
                            view.deviation(), view.passed(), true);
                }
                throw new IdempotencyConflictException("同一 requestId 携带不同业务参数: " + requestId);
            }
            throw new StateConflictException(
                    "同一活度计当日已存在日检会话，并发日检唯一受理: calibratorId=" + calibratorId);
        }
        return new DailyCheckView(keyHolder.getKey().longValue(), requestId, calibratorId, checkDate,
                expected, measuredActivityMbq, deviation, passed, false);
    }

    /**
     * 查询活度计某日是否可用（日检通过）。
     */
    public boolean isUsable(long calibratorId, LocalDate date) {
        return jdbc.sql("SELECT passed FROM daily_check WHERE calibrator_id = ? AND check_date = ?")
                .params(calibratorId, java.sql.Date.valueOf(date))
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private java.util.Optional<DailyCheckView> findByRequestId(String requestId) {
        return jdbc.sql("SELECT id, request_id, calibrator_id, check_date, expected_activity_mbq,"
                        + " measured_activity_mbq, deviation, passed FROM daily_check WHERE request_id = ?")
                .param(requestId)
                .query((rs, rn) -> new DailyCheckView(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("calibrator_id"), rs.getDate("check_date").toLocalDate(),
                        rs.getDouble("expected_activity_mbq"), rs.getDouble("measured_activity_mbq"),
                        rs.getDouble("deviation"), rs.getBoolean("passed"), false))
                .optional();
    }
}
