package com.example.starter.blast.repo;

import com.example.starter.blast.domain.VibrationReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 振动读数、迟到事件、冻结/复核事件、效果评估仓储。
 */
@Repository
public class MonitoringRepository {

    private static final RowMapper<VibrationReading> READING_MAPPER = (rs, rowNum) -> new VibrationReading(
            rs.getLong("id"),
            rs.getLong("application_id"),
            rs.getString("zone_code"),
            rs.getString("point_code"),
            rs.getTimestamp("sample_time").toInstant(),
            rs.getBigDecimal("velocity_mm_s"),
            rs.getBoolean("over_limit"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public MonitoringRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入读数；uq_reading_slot 冲突（爆区＋测点＋采样时刻重复）返回 empty。
     */
    public Optional<VibrationReading> insertReading(VibrationReading reading) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO vibration_reading "
                                + "(application_id, zone_code, point_code, sample_time, velocity_mm_s, "
                                + "over_limit, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        new String[]{"id"});
                ps.setLong(1, reading.applicationId());
                ps.setString(2, reading.zoneCode());
                ps.setString(3, reading.pointCode());
                ps.setTimestamp(4, Timestamp.from(reading.sampleTime()));
                ps.setBigDecimal(5, reading.velocityMmS());
                ps.setBoolean(6, reading.overLimit());
                ps.setTimestamp(7, Timestamp.from(reading.createdAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return Optional.empty();
        }
        return Optional.of(new VibrationReading(keyHolder.getKey().longValue(),
                reading.applicationId(), reading.zoneCode(), reading.pointCode(),
                reading.sampleTime(), reading.velocityMmS(), reading.overLimit(), reading.createdAt()));
    }

    /**
     * 记录迟到读数事件；同一爆区＋测点＋采样时刻已记录时返回 false。
     */
    public boolean insertLateEvent(VibrationReading reading, Instant now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO late_reading_event "
                            + "(application_id, zone_code, point_code, sample_time, velocity_mm_s, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    reading.applicationId(), reading.zoneCode(), reading.pointCode(),
                    Timestamp.from(reading.sampleTime()), reading.velocityMmS(), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 统计作业已回传的不同测点数，用于与备案测点数比对“回传齐全”。
     */
    public int countDistinctPointsRead(long applicationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT point_code) FROM vibration_reading WHERE application_id = ?",
                Integer.class, applicationId);
        return count == null ? 0 : count;
    }

    public void insertFreezeEvent(FreezeEventRow row) {
        jdbcTemplate.update(
                "INSERT INTO freeze_event "
                        + "(mine_code, trigger_application_id, zone_code, point_code, sample_time, "
                        + "velocity_mm_s, threshold_mm_s, frozen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.mineCode(), row.triggerApplicationId(), row.zoneCode(), row.pointCode(),
                row.sampleTime() == null ? null : Timestamp.from(row.sampleTime()),
                row.velocityMmS(), row.thresholdMmS(), Timestamp.from(row.frozenAt()));
    }

    public Optional<UnfreezeReviewRow> findUnfreezeReview(String requestId) {
        return jdbcTemplate.query(
                "SELECT mine_code, request_id, reviewer, confirmed, reviewed_at "
                        + "FROM unfreeze_review WHERE request_id = ?",
                (rs, n) -> new UnfreezeReviewRow(
                        rs.getString("mine_code"),
                        rs.getString("request_id"),
                        rs.getString("reviewer"),
                        rs.getBoolean("confirmed"),
                        rs.getTimestamp("reviewed_at").toInstant()),
                requestId).stream().findFirst();
    }

    /**
     * 插入复核记录；requestId 唯一冲突返回 false（并发复核幂等）。
     */
    public boolean insertUnfreezeReview(UnfreezeReviewRow row) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO unfreeze_review "
                            + "(mine_code, request_id, reviewer, confirmed, reviewed_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    row.mineCode(), row.requestId(), row.reviewer(), row.confirmed(),
                    Timestamp.from(row.reviewedAt()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public Optional<EvaluationRow> findEvaluationByApplication(long applicationId) {
        return jdbcTemplate.query(
                "SELECT id, application_id, request_id, evaluator, conclusion, archived_at "
                        + "FROM effect_evaluation WHERE application_id = ?",
                EVALUATION_MAPPER, applicationId).stream().findFirst();
    }

    public Optional<EvaluationRow> findEvaluationByRequest(String requestId) {
        return jdbcTemplate.query(
                "SELECT id, application_id, request_id, evaluator, conclusion, archived_at "
                        + "FROM effect_evaluation WHERE request_id = ?",
                EVALUATION_MAPPER, requestId).stream().findFirst();
    }

    /**
     * 插入评估封档；作业或 requestId 唯一冲突返回 false（终态/键幂等）。
     */
    public boolean insertEvaluation(EvaluationRow row) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO effect_evaluation "
                                + "(application_id, request_id, evaluator, conclusion, archived_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        new String[]{"id"});
                ps.setLong(1, row.applicationId());
                ps.setString(2, row.requestId());
                ps.setString(3, row.evaluator());
                ps.setString(4, row.conclusion());
                ps.setTimestamp(5, Timestamp.from(row.archivedAt()));
                return ps;
            }, keyHolder);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    private static final RowMapper<EvaluationRow> EVALUATION_MAPPER = (rs, rowNum) -> new EvaluationRow(
            rs.getLong("id"),
            rs.getLong("application_id"),
            rs.getString("request_id"),
            rs.getString("evaluator"),
            rs.getString("conclusion"),
            rs.getTimestamp("archived_at").toInstant());

    /**
     * 冻结触发事件行。
     */
    public record FreezeEventRow(String mineCode, Long triggerApplicationId, String zoneCode, String pointCode,
                                 Instant sampleTime, java.math.BigDecimal velocityMmS,
                                 java.math.BigDecimal thresholdMmS, Instant frozenAt) {
    }

    /**
     * 解冻复核记录行。
     */
    public record UnfreezeReviewRow(String mineCode, String requestId, String reviewer,
                                    boolean confirmed, Instant reviewedAt) {
    }

    /**
     * 效果评估记录行。
     */
    public record EvaluationRow(Long id, long applicationId, String requestId, String evaluator,
                                String conclusion, Instant archivedAt) {
    }
}
