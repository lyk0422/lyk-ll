package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackMasterReceipt;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 汇总分配母凭证数据访问。流水号全局唯一由数据库约束保证；母凭证只记一次，
 * 首份分配结果快照同事务回写，供同流水同内容重放。
 */
@Repository
public class ClawbackMasterReceiptRepository {

    private static final RowMapper<ClawbackMasterReceipt> MAPPER = new MasterRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackMasterReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入母凭证（version=1，结果快照随后同事务回写），回填自增主键。 */
    public long insert(ClawbackMasterReceipt m) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_master_receipt
                        (batch_id, master_serial, total_cents, received_at,
                         content_fingerprint, response_body, version_no, created_at)
                    VALUES (?, ?, ?, ?, ?, NULL, 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, m.batchId());
            ps.setString(2, m.masterSerial());
            ps.setLong(3, m.totalCents());
            ps.setTimestamp(4, Timestamp.from(m.receivedAt()));
            ps.setString(5, m.contentFingerprint());
            ps.setTimestamp(6, Timestamp.from(m.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 同事务回写首份分配结果快照（随母凭证与分配行一起提交）。 */
    public void updateResponseBody(long masterId, String responseBody) {
        jdbc.update("UPDATE t_clawback_master_receipt SET response_body = ? WHERE id = ?",
                responseBody, masterId);
    }

    public Optional<ClawbackMasterReceipt> findBySerial(String masterSerial) {
        List<ClawbackMasterReceipt> list = jdbc.query(
                BASE_SELECT + " WHERE master_serial = ?", MAPPER, masterSerial);
        return list.stream().findFirst();
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, master_serial, total_cents, received_at, content_fingerprint, "
                    + "response_body, version_no, created_at FROM t_clawback_master_receipt";

    private static final class MasterRowMapper implements RowMapper<ClawbackMasterReceipt> {
        @Override
        public ClawbackMasterReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackMasterReceipt(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("master_serial"),
                    rs.getLong("total_cents"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getString("content_fingerprint"),
                    rs.getString("response_body"),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
