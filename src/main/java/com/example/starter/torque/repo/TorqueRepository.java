package com.example.starter.torque.repo;

import com.example.starter.torque.domain.FastenerSpecRow;
import com.example.starter.torque.domain.InspectionRow;
import com.example.starter.torque.domain.LateMeasurementRow;
import com.example.starter.torque.domain.ReworkRow;
import com.example.starter.torque.domain.TighteningRow;
import com.example.starter.torque.domain.ToolRow;
import com.example.starter.torque.domain.WitnessEventRow;
import com.example.starter.torque.domain.WorkCardRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 力矩服务持久层：全部语句参数化；紧固/返工/见证链只追加。
 */
@Repository
public class TorqueRepository {

    private final JdbcTemplate jdbc;

    public TorqueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 工序卡
    // ------------------------------------------------------------------

    public void insertWorkCard(WorkCardRow row) {
        jdbc.update("""
                        INSERT INTO work_card
                          (card_no, station, product_no, status, dispatched_at,
                           released_at, quarantined_at, current_rework)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                row.cardNo(), row.station(), row.productNo(), row.status(),
                Timestamp.from(row.dispatchedAt()),
                row.releasedAt() == null ? null : Timestamp.from(row.releasedAt()),
                row.quarantinedAt() == null ? null : Timestamp.from(row.quarantinedAt()),
                row.currentRework());
    }

    public Optional<WorkCardRow> findWorkCard(String cardNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, card_no, station, product_no, status, dispatched_at, " +
                            "released_at, quarantined_at, current_rework " +
                            "FROM work_card WHERE card_no = ?",
                    WORK_CARD_MAPPER, cardNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public WorkCardRow getWorkCard(String cardNo) {
        return findWorkCard(cardNo).orElseThrow(() ->
                new EmptyResultDataAccessException("工序卡不存在: " + cardNo, 1));
    }

    public void updateStatus(long cardId, String status) {
        jdbc.update("UPDATE work_card SET status = ? WHERE id = ?", status, cardId);
    }

    public void updateReworkCount(long cardId, int currentRework) {
        jdbc.update("UPDATE work_card SET current_rework = ? WHERE id = ?", currentRework, cardId);
    }

    public void markReleased(long cardId, String status, Instant releasedAt) {
        jdbc.update("UPDATE work_card SET status = ?, released_at = ? WHERE id = ?",
                status, Timestamp.from(releasedAt), cardId);
    }

    public void markQuarantined(long cardId, Instant quarantinedAt) {
        jdbc.update("UPDATE work_card SET status = 'QUARANTINED', quarantined_at = ? WHERE id = ?",
                Timestamp.from(quarantinedAt), cardId);
    }

    // ------------------------------------------------------------------
    // 工位占用（同一工位同时刻仅一张执行中工序卡）
    // ------------------------------------------------------------------

    /** 占用工位；若已有执行中工序卡返回 false（由唯一约束兜底并发）。 */
    public boolean occupyStation(String station, long cardId, Instant occupiedAt) {
        try {
            jdbc.update("INSERT INTO station_active_card (station, card_id, occupied_at) VALUES (?, ?, ?)",
                    station, cardId, Timestamp.from(occupiedAt));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseStation(long cardId) {
        jdbc.update("DELETE FROM station_active_card WHERE card_id = ?", cardId);
    }

    public Optional<Long> activeCardIdAtStation(String station) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT card_id FROM station_active_card WHERE station = ?",
                    Long.class, station));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 工艺卡紧固件
    // ------------------------------------------------------------------

    public void insertFastener(long cardId, String pointCode, BigDecimal nominalTorque, boolean hPoint) {
        jdbc.update("INSERT INTO fastener_spec (card_id, point_code, nominal_torque, h_point) VALUES (?, ?, ?, ?)",
                cardId, pointCode, nominalTorque, hPoint);
    }

    public List<FastenerSpecRow> listFasteners(long cardId) {
        return jdbc.query(
                "SELECT id, card_id, point_code, nominal_torque, h_point FROM fastener_spec WHERE card_id = ?",
                FASTENER_MAPPER, cardId);
    }

    public Optional<FastenerSpecRow> findFastener(long cardId, String pointCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, card_id, point_code, nominal_torque, h_point " +
                            "FROM fastener_spec WHERE card_id = ? AND point_code = ?",
                    FASTENER_MAPPER, cardId, pointCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 力矩工具
    // ------------------------------------------------------------------

    public Optional<ToolRow> findTool(String toolNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, tool_no, last_check_utc_date FROM torque_tool WHERE tool_no = ?",
                    TOOL_MAPPER, toolNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsertTool(String toolNo, java.time.LocalDate lastCheckDate) {
        int updated = jdbc.update(
                "UPDATE torque_tool SET last_check_utc_date = ? WHERE tool_no = ?",
                lastCheckDate, toolNo);
        if (updated == 0) {
            jdbc.update("INSERT INTO torque_tool (tool_no, last_check_utc_date) VALUES (?, ?)",
                    toolNo, lastCheckDate);
        }
    }

    // ------------------------------------------------------------------
    // 力矩紧固记录链（只追加）
    // ------------------------------------------------------------------

    public void insertTightening(TighteningRow row) {
        jdbc.update("""
                        INSERT INTO tightening_record
                          (card_id, point_code, attempt_no, measured_torque, nominal_torque,
                           within_tolerance, tool_no, operator, recorded_at, late)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                row.cardId(), row.pointCode(), row.attemptNo(), row.measuredTorque(),
                row.nominalTorque(), row.withinTolerance(), row.toolNo(), row.operator(),
                Timestamp.from(row.recordedAt()), row.late());
    }

    public List<TighteningRow> listTightening(long cardId, String pointCode) {
        return jdbc.query("""
                        SELECT id, card_id, point_code, attempt_no, measured_torque, nominal_torque,
                               within_tolerance, tool_no, operator, recorded_at, late
                        FROM tightening_record
                        WHERE card_id = ? AND point_code = ? AND late = FALSE
                        ORDER BY attempt_no
                        """,
                TIGHTENING_MAPPER, cardId, pointCode);
    }

    public int countPassedPoints(long cardId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT point_code) FROM tightening_record t
                        WHERE card_id = ? AND late = FALSE AND within_tolerance = TRUE
                        AND attempt_no = (
                            SELECT MAX(attempt_no) FROM tightening_record
                            WHERE card_id = t.card_id AND point_code = t.point_code AND late = FALSE
                        )
                        """, Integer.class, cardId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 返工台账（只追加）
    // ------------------------------------------------------------------

    public void insertRework(ReworkRow row) {
        jdbc.update("""
                        INSERT INTO rework_record (card_id, point_code, rework_no, reason, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                row.cardId(), row.pointCode(), row.reworkNo(), row.reason(),
                Timestamp.from(row.createdAt()));
    }

    public List<ReworkRow> listReworks(long cardId) {
        return jdbc.query(
                "SELECT id, card_id, point_code, rework_no, reason, created_at " +
                        "FROM rework_record WHERE card_id = ? ORDER BY id",
                REWORK_MAPPER, cardId);
    }

    public int countReworks(long cardId, String pointCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_record WHERE card_id = ? AND point_code = ?",
                Integer.class, cardId, pointCode);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 自检/互检
    // ------------------------------------------------------------------

    public void insertInspection(InspectionRow row) {
        jdbc.update("INSERT INTO inspection_sign (card_id, sign_type, inspector, signed_at) VALUES (?, ?, ?, ?)",
                row.cardId(), row.signType(), row.inspector(), Timestamp.from(row.signedAt()));
    }

    public Optional<InspectionRow> findInspection(long cardId, String signType) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, card_id, sign_type, inspector, signed_at FROM inspection_sign WHERE card_id = ? AND sign_type = ?",
                    INSPECTION_MAPPER, cardId, signType));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 见证链
    // ------------------------------------------------------------------

    public void insertWitnessEvent(WitnessEventRow row) {
        jdbc.update("INSERT INTO witness_event (card_id, point_code, event_type, witness, event_at, detail) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                row.cardId(), row.pointCode(), row.eventType(), row.witness(),
                Timestamp.from(row.eventAt()), row.detail());
    }

    public List<WitnessEventRow> listWitnessEvents(long cardId, String pointCode) {
        return jdbc.query(
                "SELECT id, card_id, point_code, event_type, witness, event_at, detail " +
                        "FROM witness_event WHERE card_id = ? AND point_code = ? ORDER BY id",
                WITNESS_MAPPER, cardId, pointCode);
    }

    public Optional<WitnessEventRow> latestWitnessEvent(long cardId, String pointCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, card_id, point_code, event_type, witness, event_at, detail " +
                            "FROM witness_event WHERE card_id = ? AND point_code = ? ORDER BY id DESC LIMIT 1",
                    WITNESS_MAPPER, cardId, pointCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 迟到测量
    // ------------------------------------------------------------------

    public void insertLateMeasurement(LateMeasurementRow row) {
        jdbc.update("""
                        INSERT INTO late_measurement
                          (card_id, point_code, measured_torque, tool_no, operator, recorded_at, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                row.cardId(), row.pointCode(), row.measuredTorque(), row.toolNo(), row.operator(),
                Timestamp.from(row.recordedAt()), Timestamp.from(row.receivedAt()));
    }

    public List<LateMeasurementRow> listLateMeasurements(long cardId) {
        return jdbc.query("""
                        SELECT id, card_id, point_code, measured_torque, tool_no, operator,
                               recorded_at, received_at
                        FROM late_measurement WHERE card_id = ? ORDER BY id
                        """,
                LATE_MAPPER, cardId);
    }

    // ------------------------------------------------------------------
    // 复算支持：原始链全量读取
    // ------------------------------------------------------------------

    public List<TighteningRow> listAllTightening(long cardId) {
        return jdbc.query("""
                        SELECT id, card_id, point_code, attempt_no, measured_torque, nominal_torque,
                               within_tolerance, tool_no, operator, recorded_at, late
                        FROM tightening_record WHERE card_id = ? ORDER BY point_code, attempt_no
                        """,
                TIGHTENING_MAPPER, cardId);
    }

    public List<WitnessEventRow> listAllWitnessEvents(long cardId) {
        return jdbc.query(
                "SELECT id, card_id, point_code, event_type, witness, event_at, detail " +
                        "FROM witness_event WHERE card_id = ? ORDER BY id",
                WITNESS_MAPPER, cardId);
    }

    // ------------------------------------------------------------------
    // RowMappers
    // ------------------------------------------------------------------

    private static final RowMapper<WorkCardRow> WORK_CARD_MAPPER = (rs, n) -> new WorkCardRow(
            rs.getLong("id"),
            rs.getString("card_no"),
            rs.getString("station"),
            rs.getString("product_no"),
            rs.getString("status"),
            rs.getTimestamp("dispatched_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
            rs.getTimestamp("quarantined_at") == null ? null : rs.getTimestamp("quarantined_at").toInstant(),
            rs.getInt("current_rework"));

    private static final RowMapper<FastenerSpecRow> FASTENER_MAPPER = (rs, n) -> new FastenerSpecRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
            rs.getBigDecimal("nominal_torque"), rs.getBoolean("h_point"));

    private static final RowMapper<ToolRow> TOOL_MAPPER = (rs, n) -> new ToolRow(
            rs.getLong("id"), rs.getString("tool_no"), rs.getDate("last_check_utc_date").toLocalDate());

    private static final RowMapper<TighteningRow> TIGHTENING_MAPPER = (rs, n) -> new TighteningRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
            rs.getInt("attempt_no"), rs.getBigDecimal("measured_torque"),
            rs.getBigDecimal("nominal_torque"), rs.getBoolean("within_tolerance"),
            rs.getString("tool_no"), rs.getString("operator"),
            rs.getTimestamp("recorded_at").toInstant(), rs.getBoolean("late"));

    private static final RowMapper<ReworkRow> REWORK_MAPPER = (rs, n) -> new ReworkRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
            rs.getInt("rework_no"), rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<InspectionRow> INSPECTION_MAPPER = (rs, n) -> new InspectionRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("sign_type"),
            rs.getString("inspector"), rs.getTimestamp("signed_at").toInstant());

    private static final RowMapper<WitnessEventRow> WITNESS_MAPPER = (rs, n) -> new WitnessEventRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
            rs.getString("event_type"),
            rs.getString("witness"),
            rs.getTimestamp("event_at").toInstant(), rs.getString("detail"));

    private static final RowMapper<LateMeasurementRow> LATE_MAPPER = (rs, n) -> new LateMeasurementRow(
            rs.getLong("id"), rs.getLong("card_id"), rs.getString("point_code"),
            rs.getBigDecimal("measured_torque"), rs.getString("tool_no"), rs.getString("operator"),
            rs.getTimestamp("recorded_at").toInstant(), rs.getTimestamp("received_at").toInstant());
}
