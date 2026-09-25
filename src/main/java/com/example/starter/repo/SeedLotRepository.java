package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.LotStatus;
import com.example.starter.domain.SeedGrade;
import com.example.starter.domain.SeedLot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 种子批次仓储：申报落库、状态与可用标签的条件更新（供事务/并发控制使用）。
 */
@Repository
public class SeedLotRepository {

    private static final LotRowMapper ROW_MAPPER = new LotRowMapper();

    private final JdbcTemplate jdbc;

    public SeedLotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增申报批次。 */
    public void insert(SeedLot lot) {
        jdbc.update("""
                INSERT INTO seed_lot
                  (lot_no, variety_code, plot_code, declared_grade, label_quantity,
                   available_labels, status, declared_at, revoked_at, revoke_reason, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
                """,
                lot.lotNo(), lot.varietyCode(), lot.plotCode(), lot.declaredGrade().name(),
                lot.labelQuantity(), lot.availableLabels(), lot.status().name(),
                Timestamp.from(lot.declaredAt()), Timestamp.from(lot.updatedAt()));
    }

    /** 按批次编号查询。 */
    public Optional<SeedLot> findByLotNo(String lotNo) {
        return jdbc.query("SELECT * FROM seed_lot WHERE lot_no = ?", ROW_MAPPER, lotNo)
                .stream().findFirst();
    }

    /** 按批次编号查询并加行级写锁（FOR UPDATE），串行化同批次检验/发证/注销。 */
    public Optional<SeedLot> findByLotNoForUpdate(String lotNo) {
        return jdbc.query("SELECT * FROM seed_lot WHERE lot_no = ? FOR UPDATE", ROW_MAPPER, lotNo)
                .stream().findFirst();
    }

    /**
     * 发证条件更新：仅当批次存在、未注销且未发证时，将状态置为 CERTIFIED 并核减标签。
     *
     * @return 受影响行数；0 表示批次不存在、已注销或已发证（并发时仅一方成功）
     */
    public int certifyIfPending(String lotNo, int deductedLabels, Instant now) {
        return jdbc.update("""
                UPDATE seed_lot
                   SET status = 'CERTIFIED',
                       available_labels = available_labels - ?,
                       updated_at = ?
                 WHERE lot_no = ?
                   AND status = 'PENDING'
                """, deductedLabels, Timestamp.from(now), lotNo);
    }

    /**
     * 注销条件更新：非终态才写入注销信息；重复注销（已是 CANCELLED）返回 0，由服务层走幂等分支。
     *
     * @return 受影响行数
     */
    public int cancelIfActive(String lotNo, String reason, Instant now) {
        return jdbc.update("""
                UPDATE seed_lot
                   SET status = 'CANCELLED',
                       revoked_at = ?,
                       revoke_reason = ?,
                       updated_at = ?
                 WHERE lot_no = ?
                   AND status <> 'CANCELLED'
                """, Timestamp.from(now), reason, Timestamp.from(now), lotNo);
    }

    private static final class LotRowMapper implements RowMapper<SeedLot> {
        @Override
        public SeedLot mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new SeedLot(
                    rs.getString("lot_no"),
                    rs.getString("variety_code"),
                    rs.getString("plot_code"),
                    SeedGrade.valueOf(rs.getString("declared_grade")),
                    rs.getInt("label_quantity"),
                    rs.getInt("available_labels"),
                    LotStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("declared_at").toInstant(),
                    revokedAt == null ? null : revokedAt.toInstant(),
                    rs.getString("revoke_reason"),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
