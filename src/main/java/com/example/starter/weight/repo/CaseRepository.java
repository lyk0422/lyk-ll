package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.CaseRow;

/**
 * 案件仓储：立案（按车牌+检测站+过磅时刻唯一）、结案终态与幂等。
 */
@Repository
public class CaseRepository {

    private static final RowMapper<CaseRow> MAPPER = (rs, n) -> new CaseRow(
            rs.getLong("id"),
            rs.getString("case_no"),
            rs.getString("plate_no"),
            rs.getString("station_code"),
            rs.getTimestamp("weigh_time").toInstant(),
            rs.getString("status"),
            toInstant(rs.getTimestamp("closed_at")),
            rs.getString("close_request_id"),
            rs.getString("close_fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按“车牌+检测站+过磅时刻”定位案件；不存在返回 null。
     */
    public CaseRow findByBizKey(String plateNo, String stationCode, Instant weighTime) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM enforcement_case WHERE plate_no = ? AND station_code = ? AND weigh_time = ?",
                    MAPPER, plateNo, stationCode, Timestamp.from(weighTime));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按案件编号查询；不存在返回 null。
     */
    public CaseRow findByCaseNo(String caseNo) {
        try {
            return jdbc.queryForObject("SELECT * FROM enforcement_case WHERE case_no = ?", MAPPER, caseNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询；不存在返回 null。
     */
    public CaseRow findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM enforcement_case WHERE id = ?", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 插入新案件。
     */
    public void insert(CaseRow row) {
        jdbc.update("""
                INSERT INTO enforcement_case
                  (case_no, plate_no, station_code, weigh_time, status, closed_at,
                   close_request_id, close_fingerprint, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                """,
                row.caseNo(), row.plateNo(), row.stationCode(), Timestamp.from(row.weighTime()),
                row.status(), Timestamp.from(row.createdAt()));
    }

    /**
     * 结案（终态）。仅对未结案件生效。
     *
     * @return 受影响行数；0 表示案件已结案
     */
    public int close(long caseId, String requestId, String fingerprint, Instant closedAt) {
        return jdbc.update("""
                UPDATE enforcement_case
                SET status = 'CLOSED', closed_at = ?, close_request_id = ?, close_fingerprint = ?
                WHERE id = ? AND status = 'OPEN'
                """, Timestamp.from(closedAt), requestId, fingerprint, caseId);
    }

    /**
     * 按结案幂等键查询案件；不存在返回 null。
     */
    public CaseRow findByCloseRequestId(String closeRequestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM enforcement_case WHERE close_request_id = ?", MAPPER, closeRequestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 列出全部案件（测试与核查用）。
     */
    public List<CaseRow> findAll() {
        return jdbc.query("SELECT * FROM enforcement_case ORDER BY id", MAPPER);
    }

    /**
     * 查询同车牌同检测站在时刻窗口 [from, to] 内的案件（非现场接入迟到判定用）。
     */
    public List<CaseRow> findByPlateStationWithinWindow(String plateNo, String stationCode,
                                                        Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM enforcement_case
                WHERE plate_no = ? AND station_code = ? AND weigh_time BETWEEN ? AND ?
                ORDER BY id
                """, MAPPER, plateNo, stationCode, Timestamp.from(from), Timestamp.from(to));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
