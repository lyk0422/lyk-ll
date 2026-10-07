package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackBatchReceipt;
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
 * 汇总到账母凭证数据访问。银行流水号全局唯一由数据库约束保证（与互斥登记同事务双保险）；
 * 首份分配结果快照随母凭证同事务写入，供同流水同内容重放与查询原样返回。
 */
@Repository
public class ClawbackBatchReceiptRepository {

    private static final RowMapper<ClawbackBatchReceipt> MAPPER = new ReceiptRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackBatchReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入母凭证（版本 1，快照待业务视图构建后回写），回填自增主键。 */
    public long insert(ClawbackBatchReceipt r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_batch_receipt
                        (batch_id, bank_serial, total_cents, received_at, content_fingerprint,
                         response_snapshot, version_no, created_at)
                    VALUES (?, ?, ?, ?, ?, NULL, 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.batchId());
            ps.setString(2, r.bankSerial());
            ps.setLong(3, r.totalCents());
            ps.setTimestamp(4, Timestamp.from(r.receivedAt()));
            ps.setString(5, r.contentFingerprint());
            ps.setTimestamp(6, Timestamp.from(r.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 同事务回写首份分配结果快照（JSON）。 */
    public void saveSnapshot(long id, String snapshotJson) {
        jdbc.update("""
                UPDATE t_clawback_batch_receipt SET response_snapshot = ? WHERE id = ?
                """, snapshotJson, id);
    }

    public Optional<ClawbackBatchReceipt> findBySerial(String bankSerial) {
        List<ClawbackBatchReceipt> list =
                jdbc.query(BASE_SELECT + " WHERE bank_serial = ?", MAPPER, bankSerial);
        return list.stream().findFirst();
    }

    /** 读取母凭证的首份分配结果快照；无快照（异常中间态）返回空。 */
    public Optional<String> findSnapshot(String bankSerial) {
        List<String> list = jdbc.query(
                "SELECT response_snapshot FROM t_clawback_batch_receipt WHERE bank_serial = ?",
                (rs, rowNum) -> rs.getString("response_snapshot"), bankSerial);
        return list.stream().filter(s -> s != null).findFirst();
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, bank_serial, total_cents, received_at, content_fingerprint, "
                    + "version_no, created_at FROM t_clawback_batch_receipt";

    private static final class ReceiptRowMapper implements RowMapper<ClawbackBatchReceipt> {
        @Override
        public ClawbackBatchReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackBatchReceipt(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("bank_serial"),
                    rs.getLong("total_cents"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getString("content_fingerprint"),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
