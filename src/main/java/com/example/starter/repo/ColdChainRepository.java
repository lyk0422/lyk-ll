package com.example.starter.repo;

import com.example.starter.domain.ColdChainLedger;
import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import com.example.starter.domain.ColdChainStatus;
import com.example.starter.domain.ColdChainViolation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 冷链运输台账、版本化片段/读数与核验缺口存取。
 *
 * <p>并发安全靠行锁序列化，加锁顺序全局固定为：
 * customs_declaration → subbatch_binding → batch_review → coldchain_ledger，避免死锁。
 * 片段/读数/缺口按证据版本整体写入，旧版本行不回改。
 */
@Repository
public class ColdChainRepository {

    private final JdbcTemplate jdbc;

    public ColdChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ColdChainLedger> LEDGER_MAPPER = (rs, rowNum) -> {
        ColdChainLedger ledger = new ColdChainLedger();
        ledger.setId(rs.getLong("id"));
        ledger.setReviewId(rs.getLong("review_id"));
        ledger.setBatchNo(rs.getString("batch_no"));
        ledger.setStatus(ColdChainStatus.valueOf(rs.getString("status")));
        ledger.setVersion(rs.getInt("version"));
        int certVersion = rs.getInt("cert_version");
        ledger.setCertVersion(rs.wasNull() ? null : certVersion);
        ledger.setTransportStart(rs.getTimestamp("transport_start").toInstant());
        ledger.setTransportEnd(rs.getTimestamp("transport_end").toInstant());
        ledger.setSubmittedAt(rs.getTimestamp("submitted_at").toInstant());
        Timestamp qualifiedAt = rs.getTimestamp("qualified_at");
        ledger.setQualifiedAt(qualifiedAt == null ? null : qualifiedAt.toInstant());
        ledger.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        ledger.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return ledger;
    };

    private static final RowMapper<ColdChainSegment> SEGMENT_MAPPER = (rs, rowNum) -> {
        ColdChainSegment segment = new ColdChainSegment();
        segment.setId(rs.getLong("id"));
        segment.setLedgerId(rs.getLong("ledger_id"));
        segment.setVersion(rs.getInt("version"));
        segment.setSeq(rs.getInt("seq"));
        segment.setStartTs(rs.getTimestamp("start_ts").toInstant());
        segment.setEndTs(rs.getTimestamp("end_ts").toInstant());
        segment.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return segment;
    };

    private static final RowMapper<ColdChainReading> READING_MAPPER = (rs, rowNum) -> {
        ColdChainReading reading = new ColdChainReading();
        reading.setId(rs.getLong("id"));
        reading.setSegmentId(rs.getLong("segment_id"));
        reading.setSeq(rs.getInt("seq"));
        reading.setAtTs(rs.getTimestamp("at_ts").toInstant());
        reading.setTempCelsius(rs.getBigDecimal("temp_celsius"));
        reading.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return reading;
    };

    private static final RowMapper<ColdChainViolation> VIOLATION_MAPPER = (rs, rowNum) -> {
        ColdChainViolation violation = new ColdChainViolation();
        violation.setId(rs.getLong("id"));
        violation.setLedgerId(rs.getLong("ledger_id"));
        violation.setVersion(rs.getInt("version"));
        violation.setCode(rs.getString("code"));
        int segmentSeq = rs.getInt("segment_seq");
        violation.setSegmentSeq(rs.wasNull() ? null : segmentSeq);
        int readingSeq = rs.getInt("reading_seq");
        violation.setReadingSeq(rs.wasNull() ? null : readingSeq);
        Timestamp atTs = rs.getTimestamp("at_ts");
        violation.setAtTs(atTs == null ? null : atTs.toInstant());
        violation.setValue(rs.getString("value_text"));
        violation.setMessage(rs.getString("message"));
        violation.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return violation;
    };

    /** 登记台账（版本 1）；同一审查流程重复登记（uk_coldchain_review）返回 null。 */
    public Long insertLedger(ColdChainLedger ledger) {
        String sql = """
                INSERT INTO coldchain_ledger
                    (review_id, batch_no, status, version, cert_version,
                     transport_start, transport_end, submitted_at, qualified_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, ledger.getReviewId());
                ps.setString(2, ledger.getBatchNo());
                ps.setString(3, ledger.getStatus().name());
                ps.setInt(4, ledger.getVersion());
                if (ledger.getCertVersion() == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, ledger.getCertVersion());
                }
                ps.setTimestamp(6, Timestamp.from(ledger.getTransportStart()));
                ps.setTimestamp(7, Timestamp.from(ledger.getTransportEnd()));
                ps.setTimestamp(8, Timestamp.from(ledger.getSubmittedAt()));
                ps.setTimestamp(9, toTimestamp(ledger.getQualifiedAt()));
                ps.setTimestamp(10, Timestamp.from(ledger.getCreatedAt()));
                ps.setTimestamp(11, Timestamp.from(ledger.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程查询台账（不加锁），不存在返回 null。 */
    public ColdChainLedger findByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM coldchain_ledger WHERE review_id = ?",
                LEDGER_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 按审查流程加行锁查询台账，不存在返回 null。须在事务内调用。 */
    public ColdChainLedger lockByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM coldchain_ledger WHERE review_id = ? FOR UPDATE",
                LEDGER_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 全字段更新台账头（状态/版本/冻结版本/运输起止/提交与合格时刻）。 */
    public void updateLedger(ColdChainLedger ledger) {
        jdbc.update("""
                        UPDATE coldchain_ledger
                        SET status = ?, version = ?, cert_version = ?,
                            transport_start = ?, transport_end = ?,
                            submitted_at = ?, qualified_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                ledger.getStatus().name(),
                ledger.getVersion(),
                ledger.getCertVersion() == null ? null : ledger.getCertVersion(),
                Timestamp.from(ledger.getTransportStart()),
                Timestamp.from(ledger.getTransportEnd()),
                Timestamp.from(ledger.getSubmittedAt()),
                toTimestamp(ledger.getQualifiedAt()),
                Timestamp.from(ledger.getUpdatedAt()),
                ledger.getId());
    }

    /** 写入某证据版本的运输片段，返回片段主键（供读数外键使用）。 */
    public Long insertSegment(ColdChainSegment segment) {
        String sql = """
                INSERT INTO coldchain_segment
                    (ledger_id, version, seq, start_ts, end_ts, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segment.getLedgerId());
            ps.setInt(2, segment.getVersion());
            ps.setInt(3, segment.getSeq());
            ps.setTimestamp(4, Timestamp.from(segment.getStartTs()));
            ps.setTimestamp(5, Timestamp.from(segment.getEndTs()));
            ps.setTimestamp(6, Timestamp.from(segment.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 批量写入某片段的温度读数。 */
    public void insertReadings(Long segmentId, List<ColdChainReading> readings) {
        jdbc.batchUpdate("""
                        INSERT INTO coldchain_reading (segment_id, seq, at_ts, temp_celsius, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                readings, readings.size(), (ps, reading) -> {
                    ps.setLong(1, segmentId);
                    ps.setInt(2, reading.getSeq());
                    ps.setTimestamp(3, Timestamp.from(reading.getAtTs()));
                    ps.setBigDecimal(4, reading.getTempCelsius());
                    ps.setTimestamp(5, Timestamp.from(reading.getCreatedAt()));
                });
    }

    /** 批量写入某证据版本的核验缺口/越界点。 */
    public void insertViolations(Long ledgerId, int version, List<ColdChainViolation> violations) {
        jdbc.batchUpdate("""
                        INSERT INTO coldchain_violation
                            (ledger_id, version, code, segment_seq, reading_seq, at_ts, value_text, message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                violations, violations.size(), (ps, violation) -> {
                    ps.setLong(1, ledgerId);
                    ps.setInt(2, version);
                    ps.setString(3, violation.getCode());
                    if (violation.getSegmentSeq() == null) {
                        ps.setNull(4, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(4, violation.getSegmentSeq());
                    }
                    if (violation.getReadingSeq() == null) {
                        ps.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(5, violation.getReadingSeq());
                    }
                    ps.setTimestamp(6, toTimestamp(violation.getAtTs()));
                    ps.setString(7, violation.getValue());
                    ps.setString(8, violation.getMessage());
                    ps.setTimestamp(9, Timestamp.from(violation.getCreatedAt()));
                });
    }

    /** 读取台账某证据版本的全部片段（按序号排序）。 */
    public List<ColdChainSegment> listSegments(Long ledgerId, int version) {
        return jdbc.query(
                "SELECT * FROM coldchain_segment WHERE ledger_id = ? AND version = ? ORDER BY seq",
                SEGMENT_MAPPER, ledgerId, version);
    }

    /** 读取某片段的全部读数（按序号排序）。 */
    public List<ColdChainReading> listReadings(Long segmentId) {
        return jdbc.query("SELECT * FROM coldchain_reading WHERE segment_id = ? ORDER BY seq",
                READING_MAPPER, segmentId);
    }

    /** 读取台账某证据版本的全部核验缺口/越界点（按主键排序）。 */
    public List<ColdChainViolation> listViolations(Long ledgerId, int version) {
        return jdbc.query(
                "SELECT * FROM coldchain_violation WHERE ledger_id = ? AND version = ? ORDER BY id",
                VIOLATION_MAPPER, ledgerId, version);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
