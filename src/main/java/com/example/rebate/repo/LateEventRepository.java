package com.example.rebate.repo;

import com.example.rebate.domain.LateEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 迟到单证事件数据访问。结案后迟到单证只登记事件，不参与关联。
 */
@Repository
public class LateEventRepository {

    private static final RowMapper<LateEvent> MAPPER = new LateEventRowMapper();

    private final JdbcTemplate jdbc;

    public LateEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记迟到事件。
     */
    public void insert(long batchId, String declarationNo, String invoiceNo,
                       String note, java.time.Instant now) {
        jdbc.update("""
                INSERT INTO t_late_event (batch_id, declaration_no, invoice_no, note, recorded_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                batchId, declarationNo, invoiceNo, note, java.sql.Timestamp.from(now));
    }

    /**
     * 查询批次迟到事件（复算/核验用）。
     */
    public List<LateEvent> findByBatchId(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, declaration_no, invoice_no, note, recorded_at "
                        + "FROM t_late_event WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }

    private static final class LateEventRowMapper implements RowMapper<LateEvent> {
        @Override
        public LateEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LateEvent(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getString("invoice_no"),
                    rs.getString("note"),
                    rs.getTimestamp("recorded_at").toInstant());
        }
    }
}
