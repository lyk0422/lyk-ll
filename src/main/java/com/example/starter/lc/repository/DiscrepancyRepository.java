package com.example.starter.lc.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.lc.domain.Discrepancy;

/**
 * 不符点数据访问。
 */
@Repository
public class DiscrepancyRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiscrepancyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 批量写入一次性列明的不符点。调用方应在批次锁定事务内写入，之后不得再补充。 */
    public void insertAll(Long presentationId, List<Discrepancy> discrepancies) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbcTemplate.batchUpdate("""
                INSERT INTO discrepancy
                  (presentation_id, doc_type, discrepancy_code, description, seq_no, created_at_utc)
                VALUES (?,?,?,?,?,?)
                """,
                discrepancies,
                discrepancies.size(),
                (ps, d) -> {
                    ps.setLong(1, presentationId);
                    ps.setString(2, d.getDocType());
                    ps.setString(3, d.getDiscrepancyCode());
                    ps.setString(4, d.getDescription());
                    ps.setInt(5, d.getSeqNo());
                    ps.setTimestamp(6, Timestamp.valueOf(now));
                });
    }

    /** 查询批次的全部不符点，按通知内序号排序。 */
    public List<Discrepancy> findByPresentationId(Long presentationId) {
        return jdbcTemplate.query("""
                SELECT id, presentation_id, doc_type, discrepancy_code, description, seq_no, created_at_utc
                  FROM discrepancy WHERE presentation_id = ? ORDER BY seq_no, id
                """, ROW_MAPPER, presentationId);
    }

    private static final RowMapper<Discrepancy> ROW_MAPPER = (rs, rowNum) -> {
        Discrepancy d = new Discrepancy();
        d.setId(rs.getLong("id"));
        d.setPresentationId(rs.getLong("presentation_id"));
        d.setDocType(rs.getString("doc_type"));
        d.setDiscrepancyCode(rs.getString("discrepancy_code"));
        d.setDescription(rs.getString("description"));
        d.setSeqNo(rs.getInt("seq_no"));
        d.setCreatedAtUtc(rs.getTimestamp("created_at_utc").toLocalDateTime());
        return d;
    };
}
