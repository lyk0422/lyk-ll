package com.example.starter.feed.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 养殖批次仓储。批次合并口径休药期字段由服务层在事务内重算后写入。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** herd_batch 表行。 */
    public record BatchRow(
            long id,
            String batchNo,
            String species,
            String status,
            Instant lastMedicatedFeedAtUtc,
            LocalDate withdrawalUntilDate,
            Integer governingWithdrawalDays,
            Instant slaughteredAtUtc,
            Instant createdAtUtc
    ) {
    }

    private static final RowMapper<BatchRow> MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getString("species"),
            rs.getString("status"),
            DictRepository.toInstant(rs.getTimestamp("last_medicated_feed_at_utc")),
            rs.getObject("withdrawal_until_date", LocalDate.class),
            (Integer) rs.getObject("governing_withdrawal_days"),
            DictRepository.toInstant(rs.getTimestamp("slaughtered_at_utc")),
            DictRepository.toInstant(rs.getTimestamp("created_at_utc"))
    );

    public void insert(String batchNo, String species) {
        jdbc.update("INSERT INTO herd_batch (batch_no, species, status) VALUES (?,?,'FEEDING')",
                batchNo, species);
    }

    public boolean exists(String batchNo) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM herd_batch WHERE batch_no = ?",
                Long.class, batchNo);
        return count != null && count > 0;
    }

    public Optional<BatchRow> find(String batchNo) {
        return jdbc.query("SELECT * FROM herd_batch WHERE batch_no = ?", MAPPER, batchNo)
                .stream().findFirst();
    }

    public BatchRow get(String batchNo) {
        return find(batchNo).orElseThrow(() ->
                com.example.starter.feed.web.ApiException.notFound("养殖批次不存在: " + batchNo));
    }

    /**
     * 事务内行锁：并发投产/出栏/迟到饲喂以同一行锁串行化，保证合并口径唯一。
     */
    public BatchRow lockAndGet(String batchNo) {
        List<BatchRow> rows = jdbc.query(
                "SELECT * FROM herd_batch WHERE batch_no = ? FOR UPDATE", MAPPER, batchNo);
        return rows.stream().findFirst().orElseThrow(() ->
                com.example.starter.feed.web.ApiException.notFound("养殖批次不存在: " + batchNo));
    }

    /**
     * 重算后写回合并口径休药期字段。
     */
    public void updateWithdrawal(long batchId, Instant lastFeedAtUtc, LocalDate untilDate, Integer maxDays) {
        jdbc.update("UPDATE herd_batch SET last_medicated_feed_at_utc = ?, withdrawal_until_date = ?, "
                        + "governing_withdrawal_days = ? WHERE id = ?",
                lastFeedAtUtc == null ? null : Timestamp.from(lastFeedAtUtc),
                untilDate, maxDays, batchId);
    }

    /**
     * 条件置为出栏终态；仅当当前仍为 FEEDING 时生效，返回受影响行数。
     */
    public int markSlaughteredIfFeeding(long batchId, Instant slaughteredAtUtc) {
        return jdbc.update(
                "UPDATE herd_batch SET status = 'SLAUGHTERED', slaughtered_at_utc = ? "
                        + "WHERE id = ? AND status = 'FEEDING'",
                Timestamp.from(slaughteredAtUtc), batchId);
    }

    /**
     * 治疗直报撤销已批准（尚未到计划出栏时刻）的出栏申请时，将批次恢复为饲养中并清空出栏时刻。
     */
    public void clearSlaughter(long batchId) {
        jdbc.update("UPDATE herd_batch SET status = 'FEEDING', slaughtered_at_utc = NULL WHERE id = ?",
                batchId);
    }
}
