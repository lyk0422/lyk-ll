package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackAllocation;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 汇总分配行数据访问。分配行只有内部行号标识，不产生任何银行流水号；
 * 与母凭证、目标追缴明细累加在同一事务内写入。
 */
@Repository
public class ClawbackAllocationRepository {

    private static final RowMapper<ClawbackAllocation> MAPPER = new AllocationRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入分配行，回填自增主键。 */
    public long insert(ClawbackAllocation a) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_allocation
                        (receipt_id, detail_id, order_id, line_no, amount_cents, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, a.receiptId());
            ps.setLong(2, a.detailId());
            ps.setLong(3, a.orderId());
            ps.setInt(4, a.lineNo());
            ps.setLong(5, a.amountCents());
            ps.setTimestamp(6, Timestamp.from(a.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<ClawbackAllocation> findByReceiptId(long receiptId) {
        return jdbc.query(BASE_SELECT + " WHERE receipt_id = ? ORDER BY line_no", MAPPER, receiptId);
    }

    public List<ClawbackAllocation> findByOrderId(long orderId) {
        return jdbc.query(BASE_SELECT + " WHERE order_id = ? ORDER BY id", MAPPER, orderId);
    }

    private static final String BASE_SELECT =
            "SELECT id, receipt_id, detail_id, order_id, line_no, amount_cents, created_at "
                    + "FROM t_clawback_allocation";

    private static final class AllocationRowMapper implements RowMapper<ClawbackAllocation> {
        @Override
        public ClawbackAllocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackAllocation(
                    rs.getLong("id"),
                    rs.getLong("receipt_id"),
                    rs.getLong("detail_id"),
                    rs.getLong("order_id"),
                    rs.getInt("line_no"),
                    rs.getLong("amount_cents"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
