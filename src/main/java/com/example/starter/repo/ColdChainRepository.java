package com.example.starter.repo;

import com.example.starter.domain.ColdChainLedger;
import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 冷链运输台账、运输片段与温度读数存取。
 *
 * <p>台账行锁只在已持有对应 batch_review 行锁的事务内获取
 * （全局加锁顺序 customs_declaration → subbatch_binding → batch_review → cold_chain_ledger），
 * 因此台账锁不会成环；片段与读数按台账版本冻结保留，可复算。
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
        ledger.setTransportStartUtc(rs.getTimestamp("transport_start_utc").toInstant());
        ledger.setTransportEndUtc(rs.getTimestamp("transport_end_utc").toInstant());
        ledger.setVersion(rs.getInt("version"));
        int qualified = rs.getInt("qualified_version");
        ledger.setQualifiedVersion(rs.wasNull() ? null : qualified);
        ledger.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        ledger.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return ledger;
    };

    private static final RowMapper<ColdChainSegment> SEGMENT_MAPPER = (rs, rowNum) -> {
        ColdChainSegment segment = new ColdChainSegment();
        segment.setId(rs.getLong("id"));
        segment.setLedgerId(rs.getLong("ledger_id"));
        segment.setLedgerVersion(rs.getInt("ledger_version"));
        segment.setSegmentSeq(rs.getInt("segment_seq"));
        segment.setStartUtc(rs.getTimestamp("start_utc").toInstant());
        segment.setEndUtc(rs.getTimestamp("end_utc").toInstant());
        segment.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return segment;
    };

    private static final RowMapper<ColdChainReading> READING_MAPPER = (rs, rowNum) -> {
        ColdChainReading reading = new ColdChainReading();
        reading.setId(rs.getLong("id"));
        reading.setLedgerId(rs.getLong("ledger_id"));
        reading.setLedgerVersion(rs.getInt("ledger_version"));
        reading.setSegmentSeq(rs.getInt("segment_seq"));
        reading.setReadingSeq(rs.getInt("reading_seq"));
        reading.setReadAt(rs.getTimestamp("read_at").toInstant());
        reading.setTemperature(rs.getBigDecimal("temperature"));
        reading.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return reading;
    };

    /** 登记冷链台账（版本 1）；同一审查流程重复登记（uk_coldchain_review）返回 null。 */
    public Long insertLedger(ColdChainLedger ledger) {
        String sql = """
                INSERT INTO cold_chain_ledger
                    (review_id, batch_no, transport_start_utc, transport_end_utc,
                     version, qualified_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, NULL, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, ledger.getReviewId());
                ps.setString(2, ledger.getBatchNo());
                ps.setTimestamp(3, Timestamp.from(ledger.getTransportStartUtc()));
                ps.setTimestamp(4, Timestamp.from(ledger.getTransportEndUtc()));
                ps.setTimestamp(5, Timestamp.from(ledger.getCreatedAt()));
                ps.setTimestamp(6, Timestamp.from(ledger.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程查询台账（不加锁），不存在返回 null。 */
    public ColdChainLedger findLedgerByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM cold_chain_ledger WHERE review_id = ?",
                LEDGER_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /**
     * 按审查流程加行锁查询台账，不存在返回 null。
     * 须在已持有对应 batch_review 行锁的事务内调用（review → ledger 顺序）。
     */
    public ColdChainLedger lockLedgerByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM cold_chain_ledger WHERE review_id = ? FOR UPDATE",
                LEDGER_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 更新台账（运输起止/版本/温证冻结版本/变更时刻）。 */
    public void updateLedger(ColdChainLedger ledger) {
        jdbc.update("""
                        UPDATE cold_chain_ledger
                        SET transport_start_utc = ?, transport_end_utc = ?,
                            version = ?, qualified_version = ?, updated_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(ledger.getTransportStartUtc()),
                Timestamp.from(ledger.getTransportEndUtc()),
                ledger.getVersion(),
                ledger.getQualifiedVersion(),
                Timestamp.from(ledger.getUpdatedAt()),
                ledger.getId());
    }

    /** 批量写入某版本的运输片段（旧版本片段冻结保留，不物理删除）。 */
    public void insertSegments(List<ColdChainSegment> segments) {
        jdbc.batchUpdate("""
                        INSERT INTO cold_chain_segment
                            (ledger_id, ledger_version, segment_seq, start_utc, end_utc, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                segments, segments.size(), (ps, segment) -> {
                    ps.setLong(1, segment.getLedgerId());
                    ps.setInt(2, segment.getLedgerVersion());
                    ps.setInt(3, segment.getSegmentSeq());
                    ps.setTimestamp(4, Timestamp.from(segment.getStartUtc()));
                    ps.setTimestamp(5, Timestamp.from(segment.getEndUtc()));
                    ps.setTimestamp(6, Timestamp.from(segment.getCreatedAt()));
                });
    }

    /** 批量写入某版本的温度读数。 */
    public void insertReadings(List<ColdChainReading> readings) {
        jdbc.batchUpdate("""
                        INSERT INTO cold_chain_reading
                            (ledger_id, ledger_version, segment_seq, reading_seq, read_at,
                             temperature, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                readings, readings.size(), (ps, reading) -> {
                    ps.setLong(1, reading.getLedgerId());
                    ps.setInt(2, reading.getLedgerVersion());
                    ps.setInt(3, reading.getSegmentSeq());
                    ps.setInt(4, reading.getReadingSeq());
                    ps.setTimestamp(5, Timestamp.from(reading.getReadAt()));
                    ps.setBigDecimal(6, reading.getTemperature());
                    ps.setTimestamp(7, Timestamp.from(reading.getCreatedAt()));
                });
    }

    /** 删除某版本已存读数（仅用于替换未合格的当前版本证据；已冻结旧版本不得调用）。 */
    public void deleteReadings(Long ledgerId, int ledgerVersion) {
        jdbc.update("DELETE FROM cold_chain_reading WHERE ledger_id = ? AND ledger_version = ?",
                ledgerId, ledgerVersion);
    }

    /** 读取某版本片段（按序号升序）。 */
    public List<ColdChainSegment> listSegments(Long ledgerId, int ledgerVersion) {
        return jdbc.query(
                "SELECT * FROM cold_chain_segment WHERE ledger_id = ? AND ledger_version = ?"
                        + " ORDER BY segment_seq",
                SEGMENT_MAPPER, ledgerId, ledgerVersion);
    }

    /** 读取某版本读数（按片段序号、读数序号升序）。 */
    public List<ColdChainReading> listReadings(Long ledgerId, int ledgerVersion) {
        return jdbc.query(
                "SELECT * FROM cold_chain_reading WHERE ledger_id = ? AND ledger_version = ?"
                        + " ORDER BY segment_seq, reading_seq",
                READING_MAPPER, ledgerId, ledgerVersion);
    }
}
