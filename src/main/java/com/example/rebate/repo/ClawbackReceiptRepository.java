package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackReceipt;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 追缴回执数据访问。流水号全局唯一由数据库约束保证；同流水异金额在业务层先查后拒绝。
 */
@Repository
public class ClawbackReceiptRepository {

    private static final RowMapper<ClawbackReceipt> MAPPER = new ReceiptRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入回执，回填主键。 */
    public long insert(ClawbackReceipt r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_receipt
                        (detail_id, order_id, receipt_serial, received_cents, received_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.detailId());
            ps.setLong(2, r.orderId());
            ps.setString(3, r.receiptSerial());
            ps.setLong(4, r.receivedCents());
            ps.setTimestamp(5, Timestamp.from(r.receivedAt()));
            ps.setTimestamp(6, Timestamp.from(r.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<ClawbackReceipt> findBySerial(String receiptSerial) {
        return jdbc.query(BASE_SELECT + " WHERE receipt_serial = ?", MAPPER, receiptSerial);
    }

    public List<ClawbackReceipt> findByDetailId(long detailId) {
        return jdbc.query(BASE_SELECT + " WHERE detail_id = ? ORDER BY id", MAPPER, detailId);
    }

    private static final String BASE_SELECT =
            "SELECT id, detail_id, order_id, receipt_serial, received_cents, received_at, created_at "
                    + "FROM t_clawback_receipt";

    private static final class ReceiptRowMapper implements RowMapper<ClawbackReceipt> {
        @Override
        public ClawbackReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackReceipt(
                    rs.getLong("id"),
                    rs.getLong("detail_id"),
                    rs.getLong("order_id"),
                    rs.getString("receipt_serial"),
                    rs.getLong("received_cents"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
