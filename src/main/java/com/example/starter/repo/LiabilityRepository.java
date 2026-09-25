package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 融资负债明细数据访问。强平时按未偿还余额从大到小逐笔平仓。
 */
@Repository
public class LiabilityRepository {

    /** 负债行。outstanding 单位：分。 */
    public record LiabilityRow(long id, long accountId, long outstanding, String status) {
    }

    private static final RowMapper<LiabilityRow> MAPPER = (rs, i) -> new LiabilityRow(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getLong("outstanding"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public LiabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long accountId, long snapshotId, long outstanding) {
        jdbc.update("INSERT INTO liability (account_id, snapshot_id, outstanding) VALUES (?, ?, ?)",
                accountId, snapshotId, outstanding);
    }

    /** 用新快照的负债明细替换账户未结清负债（旧明细视为已被新快照覆盖）。 */
    public void replaceForSnapshot(long accountId, long snapshotId, List<Long> outstandingCents) {
        jdbc.update("UPDATE liability SET status = 'CLOSED', outstanding = 0"
                + " WHERE account_id = ? AND status = 'OPEN'", accountId);
        for (Long cents : outstandingCents) {
            if (cents != null && cents > 0) {
                insert(accountId, snapshotId, cents);
            }
        }
    }

    /** 未结清负债，按余额从大到小排序（强平阶梯顺序）。 */
    public List<LiabilityRow> findOpenOrderByOutstandingDesc(long accountId) {
        return jdbc.query("SELECT * FROM liability WHERE account_id = ? AND status = 'OPEN'"
                + " ORDER BY outstanding DESC, id ASC", MAPPER, accountId);
    }

    /** 部分或全部偿还：扣减余额，余额为 0 时结清。 */
    public void repay(long liabilityId, long amountCents) {
        jdbc.update("UPDATE liability SET outstanding = outstanding - ?,"
                + " status = CASE WHEN outstanding - ? <= 0 THEN 'CLOSED' ELSE status END"
                + " WHERE id = ?", amountCents, amountCents, liabilityId);
    }
}
