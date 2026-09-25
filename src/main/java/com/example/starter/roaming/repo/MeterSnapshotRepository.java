package com.example.starter.roaming.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计量快照数据访问（会话号＋抄表序号唯一，乱序可后到）。
 */
@Repository
public class MeterSnapshotRepository {

    private final JdbcTemplate jdbc;

    public MeterSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 计量快照行。 */
    public record SnapshotRow(Long id, String sessionNo, Integer readingSeq,
                              BigDecimal energyKwh, Instant recordedAt,
                              String requestId, Instant createdAt) {
    }

    private static final RowMapper<SnapshotRow> MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getInt("reading_seq"),
            rs.getBigDecimal("energy_kwh"),
            rs.getTimestamp("recorded_at_utc").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant());

    /**
     * 插入快照；唯一键冲突表示同“会话号＋抄表序号”重复，不重计电量。
     *
     * @return true 首次插入；false 重复
     */
    public boolean insertIfAbsent(SnapshotRow row) {
        try {
            jdbc.update(
                    "INSERT INTO meter_snapshot (session_no, reading_seq, energy_kwh, recorded_at_utc, "
                            + "request_id, created_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                    row.sessionNo(), row.readingSeq(), row.energyKwh(),
                    Timestamp.from(row.recordedAt()), row.requestId(), Timestamp.from(row.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<SnapshotRow> find(String sessionNo, Integer readingSeq) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, session_no, reading_seq, energy_kwh, recorded_at_utc, request_id, created_at_utc "
                            + "FROM meter_snapshot WHERE session_no = ? AND reading_seq = ?",
                    MAPPER, sessionNo, readingSeq));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<SnapshotRow> findBySession(String sessionNo) {
        return jdbc.query(
                "SELECT id, session_no, reading_seq, energy_kwh, recorded_at_utc, request_id, created_at_utc "
                        + "FROM meter_snapshot WHERE session_no = ? ORDER BY reading_seq",
                MAPPER, sessionNo);
    }

    /** 最新（抄表序号最大）快照读数；无快照返回空。 */
    public Optional<SnapshotRow> findLatest(String sessionNo) {
        return jdbc.query(
                        "SELECT id, session_no, reading_seq, energy_kwh, recorded_at_utc, request_id, created_at_utc "
                                + "FROM meter_snapshot WHERE session_no = ? ORDER BY reading_seq DESC LIMIT 1",
                        MAPPER, sessionNo).stream()
                .findFirst();
    }
}
