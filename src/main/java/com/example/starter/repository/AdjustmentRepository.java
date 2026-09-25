package com.example.starter.repository;

import com.example.starter.entity.Adjustment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 挂账调账指令表数据访问。 */
@Repository
public class AdjustmentRepository {

    private static final RowMapper<Adjustment> MAPPER = new AdjustmentRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public AdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Adjustment findByAdjustNo(String adjustNo) {
        List<Adjustment> list = jdbcTemplate.query(
                "SELECT id, adjust_no, txn_id, from_batch_date, to_batch_date, amount, status, created_at "
                        + "FROM adjustment WHERE adjust_no = ?",
                MAPPER, adjustNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(Adjustment adjustment) {
        jdbcTemplate.update(
                "INSERT INTO adjustment (adjust_no, txn_id, from_batch_date, to_batch_date, amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                adjustment.getAdjustNo(), adjustment.getTxnId(), adjustment.getFromBatchDate(),
                adjustment.getToBatchDate(), adjustment.getAmount(), adjustment.getStatus(),
                Timestamp.from(adjustment.getCreatedAt()));
    }

    private static class AdjustmentRowMapper implements RowMapper<Adjustment> {
        @Override
        public Adjustment mapRow(ResultSet rs, int rowNum) throws SQLException {
            Adjustment a = new Adjustment();
            a.setId(rs.getLong("id"));
            a.setAdjustNo(rs.getString("adjust_no"));
            a.setTxnId(rs.getLong("txn_id"));
            a.setFromBatchDate(rs.getDate("from_batch_date").toLocalDate());
            a.setToBatchDate(rs.getDate("to_batch_date").toLocalDate());
            a.setAmount(rs.getLong("amount"));
            a.setStatus(rs.getString("status"));
            a.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return a;
        }
    }
}
