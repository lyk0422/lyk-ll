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
 * 汇总分配行数据访问。分配行仅有独立内部标识（ALC- 序列），不进入真实银行流水命名空间。
 */
@Repository
public class ClawbackAllocationRepository {

    private static final RowMapper<ClawbackAllocation> MAPPER = new AllocationRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取下一个分配行内部标识（非银行流水号）。 */
    public String nextAllocationNo() {
        long seq = jdbc.queryForObject("SELECT NEXT VALUE FOR seq_allocation_no", Long.class);
        return String.format("ALC-%08d", seq);
    }

    /** 写入分配行，回填自增主键。 */
    public long insert(ClawbackAllocation a) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_allocation
                        (master_id, detail_id, order_id, allocation_no, amount_cents, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, a.masterId());
            ps.setLong(2, a.detailId());
            ps.setLong(3, a.orderId());
            ps.setString(4, a.allocationNo());
            ps.setLong(5, a.amountCents());
            ps.setTimestamp(6, Timestamp.from(a.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<ClawbackAllocation> findByMasterId(long masterId) {
        return jdbc.query(BASE_SELECT + " WHERE master_id = ? ORDER BY id", MAPPER, masterId);
    }

    private static final String BASE_SELECT =
            "SELECT id, master_id, detail_id, order_id, allocation_no, amount_cents, created_at "
                    + "FROM t_clawback_allocation";

    private static final class AllocationRowMapper implements RowMapper<ClawbackAllocation> {
        @Override
        public ClawbackAllocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackAllocation(
                    rs.getLong("id"),
                    rs.getLong("master_id"),
                    rs.getLong("detail_id"),
                    rs.getLong("order_id"),
                    rs.getString("allocation_no"),
                    rs.getLong("amount_cents"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
