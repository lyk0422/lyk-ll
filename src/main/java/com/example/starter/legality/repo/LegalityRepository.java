package com.example.starter.legality.repo;

import com.example.starter.legality.po.AirportPo;
import com.example.starter.legality.po.CheckBatchPo;
import com.example.starter.legality.po.DelayEventPo;
import com.example.starter.legality.po.DutyRecordPo;
import com.example.starter.legality.po.FlightPo;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 值勤合规数据访问：批次、值勤记录、延误事件及合成主数据。
 * 所有 SQL 均使用参数绑定；时刻统一 UTC。
 */
@Repository
public class LegalityRepository {

    private final JdbcTemplate jdbcTemplate;

    public LegalityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AirportPo> AIRPORT_MAPPER = (rs, n) ->
            new AirportPo(rs.getString("code"), rs.getString("time_zone_id"), rs.getString("display_name"));

    private static final RowMapper<FlightPo> FLIGHT_MAPPER = (rs, n) ->
            new FlightPo(rs.getString("flight_no"),
                    rs.getString("departure_airport_code"),
                    rs.getString("arrival_airport_code"));

    private static final RowMapper<CheckBatchPo> BATCH_MAPPER = (rs, n) -> {
        CheckBatchPo po = new CheckBatchPo();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setOverallResult(rs.getString("overall_result"));
        po.setSealed(rs.getBoolean("sealed"));
        Timestamp created = rs.getTimestamp("created_at");
        po.setCreatedAt(created == null ? null : created.toInstant());
        Timestamp finalized = rs.getTimestamp("finalized_at");
        po.setFinalizedAt(finalized == null ? null : finalized.toInstant());
        return po;
    };

    private static final RowMapper<DutyRecordPo> DUTY_MAPPER = (rs, n) -> {
        DutyRecordPo po = new DutyRecordPo();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getLong("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setMemberCode(rs.getString("member_code"));
        po.setFlightNo(rs.getString("flight_no"));
        po.setReportTimeUtc(rs.getTimestamp("report_time_utc").toInstant());
        po.setPlannedEndUtc(rs.getTimestamp("planned_end_utc").toInstant());
        po.setTierLimitMinutes(rs.getInt("tier_limit_minutes"));
        po.setTotalExtensionMinutes(rs.getInt("total_extension_minutes"));
        po.setDutyEndUtc(rs.getTimestamp("duty_end_utc").toInstant());
        po.setResult(rs.getString("result"));
        po.setReasons(rs.getString("reasons"));
        po.setAccepted(rs.getBoolean("accepted"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    };

    private static final RowMapper<DelayEventPo> DELAY_MAPPER = (rs, n) -> {
        DelayEventPo po = new DelayEventPo();
        po.setId(rs.getLong("id"));
        po.setDutyRecordId(rs.getLong("duty_record_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setExtensionMinutes(rs.getInt("extension_minutes"));
        po.setLate(rs.getBoolean("late"));
        po.setEventDayUtc(rs.getDate("event_day_utc").toLocalDate());
        po.setNote(rs.getString("note"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    };

    public AirportPo findAirport(String code) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT code, time_zone_id, display_name FROM airport WHERE code = ?",
                    AIRPORT_MAPPER, code);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public FlightPo findFlight(String flightNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT flight_no, departure_airport_code, arrival_airport_code FROM flight WHERE flight_no = ?",
                    FLIGHT_MAPPER, flightNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public boolean existsCrewMember(String memberCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crew_member WHERE member_code = ?", Integer.class, memberCode);
        return count != null && count > 0;
    }

    public void insertBatch(CheckBatchPo po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO check_batch(batch_id, overall_result, sealed, created_at, finalized_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getBatchId());
            ps.setString(2, po.getOverallResult());
            ps.setBoolean(3, po.isSealed());
            ps.setTimestamp(4, Timestamp.from(po.getCreatedAt()));
            if (po.getFinalizedAt() == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, Timestamp.from(po.getFinalizedAt()));
            }
            return ps;
        }, keyHolder);
        po.setId(keyHolder.getKey().longValue());
    }

    public CheckBatchPo findBatchByBatchId(String batchId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, batch_id, overall_result, sealed, created_at, finalized_at "
                            + "FROM check_batch WHERE batch_id = ?",
                    BATCH_MAPPER, batchId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public CheckBatchPo findBatchById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, batch_id, overall_result, sealed, created_at, finalized_at "
                            + "FROM check_batch WHERE id = ?",
                    BATCH_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 幂等封板：仅未封板行会被更新，返回受影响行数。 */
    public int sealBatch(String batchId, Instant finalizedAt) {
        return jdbcTemplate.update(
                "UPDATE check_batch SET sealed = TRUE, finalized_at = ? "
                        + "WHERE batch_id = ? AND sealed = FALSE",
                Timestamp.from(finalizedAt), batchId);
    }

    /** 重新核算（未封板批次延误导致结论变化）后刷新整组结论。 */
    public void updateBatchOverall(Long batchId, String overallResult) {
        jdbcTemplate.update(
                "UPDATE check_batch SET overall_result = ? WHERE id = ? AND sealed = FALSE",
                overallResult, batchId);
    }

    public void insertDuty(DutyRecordPo po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO duty_record(batch_id, request_id, member_code, flight_no, report_time_utc, "
                            + "planned_end_utc, tier_limit_minutes, total_extension_minutes, duty_end_utc, "
                            + "result, reasons, accepted, fingerprint, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, po.getBatchId());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getMemberCode());
            ps.setString(4, po.getFlightNo());
            ps.setTimestamp(5, Timestamp.from(po.getReportTimeUtc()));
            ps.setTimestamp(6, Timestamp.from(po.getPlannedEndUtc()));
            ps.setInt(7, po.getTierLimitMinutes());
            ps.setInt(8, po.getTotalExtensionMinutes());
            ps.setTimestamp(9, Timestamp.from(po.getDutyEndUtc()));
            ps.setString(10, po.getResult());
            ps.setString(11, po.getReasons());
            ps.setBoolean(12, po.isAccepted());
            ps.setString(13, po.getFingerprint());
            ps.setTimestamp(14, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        po.setId(keyHolder.getKey().longValue());
    }

    public DutyRecordPo findDutyByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, batch_id, request_id, member_code, flight_no, report_time_utc, planned_end_utc, "
                            + "tier_limit_minutes, total_extension_minutes, duty_end_utc, result, reasons, "
                            + "accepted, fingerprint, created_at "
                            + "FROM duty_record WHERE request_id = ?",
                    DUTY_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public DutyRecordPo findDutyById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, batch_id, request_id, member_code, flight_no, report_time_utc, planned_end_utc, "
                            + "tier_limit_minutes, total_extension_minutes, duty_end_utc, result, reasons, "
                            + "accepted, fingerprint, created_at "
                            + "FROM duty_record WHERE id = ?",
                    DUTY_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 并发同槽位唯一受理：按（成员、航班、报到时刻）查既有记录。 */
    public DutyRecordPo findDutyBySlot(String memberCode, String flightNo, Instant reportTimeUtc) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, batch_id, request_id, member_code, flight_no, report_time_utc, planned_end_utc, "
                            + "tier_limit_minutes, total_extension_minutes, duty_end_utc, result, reasons, "
                            + "accepted, fingerprint, created_at "
                            + "FROM duty_record WHERE member_code = ? AND flight_no = ? AND report_time_utc = ?",
                    DUTY_MAPPER, memberCode, flightNo, Timestamp.from(reportTimeUtc));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<DutyRecordPo> findDutiesByBatchId(Long batchId) {
        return jdbcTemplate.query(
                "SELECT id, batch_id, request_id, member_code, flight_no, report_time_utc, planned_end_utc, "
                        + "tier_limit_minutes, total_extension_minutes, duty_end_utc, result, reasons, "
                        + "accepted, fingerprint, created_at "
                        + "FROM duty_record WHERE batch_id = ? ORDER BY id",
                DUTY_MAPPER, batchId);
    }

    /** 成员全部已受理（有效）值勤，按报到时刻升序；休息期与 7 日窗口在服务层据此计算。 */
    public List<DutyRecordPo> findAcceptedDutiesByMember(String memberCode) {
        return jdbcTemplate.query(
                "SELECT id, batch_id, request_id, member_code, flight_no, report_time_utc, planned_end_utc, "
                        + "tier_limit_minutes, total_extension_minutes, duty_end_utc, result, reasons, "
                        + "accepted, fingerprint, created_at "
                        + "FROM duty_record WHERE member_code = ? AND accepted = TRUE ORDER BY report_time_utc, id",
                DUTY_MAPPER, memberCode);
    }

    /**
     * 某机组成员在指定 UTC 日历日（按 FDP 报到日归属）已受理值勤的累计延误延长（分钟）。
     */
    public int sumAcceptedExtensionForDay(String memberCode, LocalDate dayUtc) {
        Integer sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_extension_minutes), 0) FROM duty_record "
                        + "WHERE member_code = ? AND accepted = TRUE AND CAST(report_time_utc AS DATE) = ?",
                Integer.class, memberCode, java.sql.Date.valueOf(dayUtc));
        return sum == null ? 0 : sum;
    }

    public void insertDelay(DelayEventPo po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO delay_event(duty_record_id, request_id, extension_minutes, late, "
                            + "event_day_utc, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, po.getDutyRecordId());
            ps.setString(2, po.getRequestId());
            ps.setInt(3, po.getExtensionMinutes());
            ps.setBoolean(4, po.isLate());
            ps.setDate(5, java.sql.Date.valueOf(po.getEventDayUtc()));
            if (po.getNote() == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, po.getNote());
            }
            ps.setTimestamp(7, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        po.setId(keyHolder.getKey().longValue());
    }

    public DelayEventPo findDelayByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, duty_record_id, request_id, extension_minutes, late, event_day_utc, note, created_at "
                            + "FROM delay_event WHERE request_id = ?",
                    DELAY_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 延误延长受理后刷新值勤记录的累计延长、实际结束、判定与受理状态。 */
    public void updateDutyAfterExtension(DutyRecordPo po) {
        jdbcTemplate.update(
                "UPDATE duty_record SET total_extension_minutes = ?, duty_end_utc = ?, result = ?, "
                        + "reasons = ?, accepted = ? WHERE id = ?",
                po.getTotalExtensionMinutes(),
                Timestamp.from(po.getDutyEndUtc()),
                po.getResult(),
                po.getReasons(),
                po.isAccepted(),
                po.getId());
    }
}
