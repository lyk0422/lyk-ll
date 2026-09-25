package com.example.starter.damage.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.EstimateVersionPo;

/** 估价版本与版本明细快照数据访问。 */
@Repository
public class EstimateRepository {

    private final JdbcTemplate jdbc;

    public EstimateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增估价版本，返回版本 ID。 */
    public long insertVersion(long caseId, int versionNo, String requestId,
                              java.math.BigDecimal subtotalOwner, java.math.BigDecimal subtotalTerminal,
                              java.math.BigDecimal totalAmount, java.math.BigDecimal maxItemAmount,
                              java.math.BigDecimal ratio, String decision, long nowUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO estimate_version
                        (case_id, version_no, request_id, subtotal_owner, subtotal_terminal,
                         total_amount, max_item_amount, ratio, decision, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, caseId);
            ps.setInt(2, versionNo);
            ps.setString(3, requestId);
            ps.setBigDecimal(4, subtotalOwner);
            ps.setBigDecimal(5, subtotalTerminal);
            ps.setBigDecimal(6, totalAmount);
            ps.setBigDecimal(7, maxItemAmount);
            ps.setBigDecimal(8, ratio);
            ps.setString(9, decision);
            ps.setLong(10, nowUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertEstimateItem(long estimateId, long itemId, java.math.BigDecimal amount) {
        jdbc.update("INSERT INTO estimate_item (estimate_id, item_id, amount) VALUES (?, ?, ?)",
                estimateId, itemId, amount);
    }

    public int countByCaseId(long caseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM estimate_version WHERE case_id = ?", Integer.class, caseId);
        return count == null ? 0 : count;
    }

    private static final String SELECT = """
            SELECT id, case_id, version_no, request_id, subtotal_owner, subtotal_terminal,
                   total_amount, max_item_amount, ratio, decision, created_at_utc
            FROM estimate_version
            """;

    public List<EstimateVersionPo> findAllByCaseId(long caseId) {
        return jdbc.query(SELECT + " WHERE case_id = ? ORDER BY version_no",
                (rs, n) -> map(rs), caseId);
    }

    public EstimateVersionPo findById(long estimateId) {
        List<EstimateVersionPo> list = jdbc.query(SELECT + " WHERE id = ?",
                (rs, n) -> map(rs), estimateId);
        return list.stream().findFirst().orElse(null);
    }

    private EstimateVersionPo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EstimateVersionPo(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getInt("version_no"),
                rs.getString("request_id"),
                rs.getBigDecimal("subtotal_owner"),
                rs.getBigDecimal("subtotal_terminal"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("max_item_amount"),
                rs.getBigDecimal("ratio"),
                rs.getString("decision"),
                (Long) rs.getObject("created_at_utc"));
    }
}
