package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.OffsiteWeighRecordRow;

/**
 * 非现场动态称重接入记录仓储：幂等键唯一、车牌+时刻窗口去重锚点查询。
 */
@Repository
public class OffsiteWeighRecordRepository {

    private static final RowMapper<OffsiteWeighRecordRow> MAPPER = (rs, n) -> new OffsiteWeighRecordRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("plate_no"),
            rs.getString("lane_code"),
            rs.getString("device_code"),
            rs.getTimestamp("weigh_time").toInstant(),
            rs.getBigDecimal("total_kg"),
            rs.getString("axle_type"),
            rs.getBigDecimal("limit_kg"),
            rs.getBigDecimal("overload_rate"),
            rs.getString("tier"),
            rs.getString("image_ref"),
            rs.getString("evidence_status"),
            rs.getString("missing_items"),
            rs.getString("dedup_ref_type"),
            (Long) rs.getObject("dedup_ref_id"),
            (Long) rs.getObject("case_id"),
            (Long) rs.getObject("decision_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public OffsiteWeighRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询；不存在返回 null。
     */
    public OffsiteWeighRecordRow findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject("SELECT * FROM offsite_weigh_record WHERE request_id = ?",
                    MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按主键查询；不存在返回 null。
     */
    public OffsiteWeighRecordRow findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM offsite_weigh_record WHERE id = ?", MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查询同车牌在时刻窗口 [from, to] 内已保留（非 DUPLICATE）的非现场记录，
     * 作为窗口去重锚点；不存在返回 null。
     */
    public OffsiteWeighRecordRow findRetainedByPlateWithinWindow(String plateNo, Instant from, Instant to) {
        List<OffsiteWeighRecordRow> rows = jdbc.query("""
                SELECT * FROM offsite_weigh_record
                WHERE plate_no = ? AND evidence_status <> 'DUPLICATE' AND weigh_time BETWEEN ? AND ?
                ORDER BY id LIMIT 1
                """, MAPPER, plateNo, Timestamp.from(from), Timestamp.from(to));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 插入非现场接入记录，返回生成的主键。
     */
    public long insert(OffsiteWeighRecordRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO offsite_weigh_record
                      (request_id, fingerprint, plate_no, lane_code, device_code, weigh_time, total_kg,
                       axle_type, limit_kg, overload_rate, tier, image_ref, evidence_status, missing_items,
                       dedup_ref_type, dedup_ref_id, case_id, decision_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, row.requestId());
            ps.setString(2, row.fingerprint());
            ps.setString(3, row.plateNo());
            ps.setString(4, row.laneCode());
            ps.setString(5, row.deviceCode());
            ps.setTimestamp(6, Timestamp.from(row.weighTime()));
            ps.setBigDecimal(7, row.totalKg());
            ps.setString(8, row.axleType());
            ps.setBigDecimal(9, row.limitKg());
            ps.setBigDecimal(10, row.overloadRate());
            ps.setString(11, row.tier());
            ps.setString(12, row.imageRef());
            ps.setString(13, row.evidenceStatus());
            ps.setString(14, row.missingItems());
            ps.setString(15, row.dedupRefType());
            if (row.dedupRefId() == null) {
                ps.setObject(16, null);
            } else {
                ps.setLong(16, row.dedupRefId());
            }
            if (row.caseId() == null) {
                ps.setObject(17, null);
            } else {
                ps.setLong(17, row.caseId());
            }
            if (row.decisionId() == null) {
                ps.setObject(18, null);
            } else {
                ps.setLong(18, row.decisionId());
            }
            ps.setTimestamp(19, Timestamp.from(row.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 回填非现场记录关联的案件与自动生成的处罚决定。
     */
    public void updateCaseAndDecision(long id, Long caseId, Long decisionId) {
        jdbc.update("UPDATE offsite_weigh_record SET case_id = ?, decision_id = ? WHERE id = ?",
                caseId, decisionId, id);
    }

    /**
     * 并发下去重键被其他事务抢先写入时，将本记录改判为窗口去重（只保留首次）。
     */
    public void markDuplicate(long id, String dedupRefType, Long dedupRefId) {
        jdbc.update("""
                UPDATE offsite_weigh_record
                SET evidence_status = 'DUPLICATE', dedup_ref_type = ?, dedup_ref_id = ?
                WHERE id = ?
                """, dedupRefType, dedupRefId, id);
    }
}
