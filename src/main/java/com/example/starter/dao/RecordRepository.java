package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 水下记录访问。单人记录 effective=FALSE 不生效；两人同阶段成对后同时置为生效。
 */
@Repository
public class RecordRepository {

    private static final RowMapper<RecordRow> MAPPER = (rs, n) -> new RecordRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getString("diver_code"),
            rs.getString("phase"),
            rs.getInt("from_depth_m"),
            rs.getInt("to_depth_m"),
            rs.getInt("duration_min"),
            rs.getBigDecimal("rate_m_min"),
            rs.getBoolean("effective"),
            rs.getBoolean("frozen"),
            (Long) rs.getObject("paired_record_id"),
            rs.getTimestamp("event_time_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public RecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(RecordRow row) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dive_record "
                            + "(dive_request_id, plan_version, diver_code, phase, from_depth_m, "
                            + "to_depth_m, duration_min, rate_m_min, effective, frozen, "
                            + "paired_record_id, event_time_utc, created_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.diveRequestId());
            ps.setInt(2, row.planVersion());
            ps.setString(3, row.diverCode());
            ps.setString(4, row.phase());
            ps.setInt(5, row.fromDepthM());
            ps.setInt(6, row.toDepthM());
            ps.setInt(7, row.durationMin());
            ps.setBigDecimal(8, row.rateMMin());
            ps.setBoolean(9, row.effective());
            ps.setBoolean(10, row.frozen());
            if (row.pairedRecordId() == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, row.pairedRecordId());
            }
            ps.setTimestamp(12, Timestamp.from(row.eventTimeUtc()));
            ps.setTimestamp(13, Timestamp.from(row.createdAtUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public Optional<RecordRow> find(String requestId, int version, String diverCode, String phase) {
        return jdbc.query("SELECT * FROM dive_record WHERE dive_request_id = ? AND plan_version = ? "
                        + "AND diver_code = ? AND phase = ?",
                MAPPER, requestId, version, diverCode, phase).stream().findFirst();
    }

    /** 查找同潜次、同版本、同阶段另一名潜水员的未冻结、未生效记录，用于成对。 */
    public Optional<RecordRow> findPairPeer(String requestId, int version, String phase,
                                            String diverCode) {
        return jdbc.query("SELECT * FROM dive_record WHERE dive_request_id = ? AND plan_version = ? "
                        + "AND phase = ? AND diver_code <> ? AND frozen = FALSE "
                        + "ORDER BY id LIMIT 1",
                MAPPER, requestId, version, phase, diverCode).stream().findFirst();
    }

    /** 成对生效：互相写入配对手 id 并置 effective=TRUE。 */
    public void markPaired(long recordId, long peerId) {
        jdbc.update("UPDATE dive_record SET effective = TRUE, paired_record_id = ? WHERE id = ?",
                peerId, recordId);
    }

    public RecordRow getById(long id) {
        return jdbc.query("SELECT * FROM dive_record WHERE id = ?", MAPPER, id)
                .stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException("水下记录不存在: id=" + id));
    }

    /** 潜次全部版本的全部记录（复算用），按版本与 id 排序。 */
    public List<RecordRow> findAllByDive(String requestId) {
        return jdbc.query("SELECT * FROM dive_record WHERE dive_request_id = ? "
                        + "ORDER BY plan_version, id",
                MAPPER, requestId);
    }

    /** 方案变更：旧版本全部水下记录冻结，与方案冻结同一事务。 */
    public int freezeByDive(String requestId) {
        return jdbc.update("UPDATE dive_record SET frozen = TRUE, effective = FALSE, "
                        + "paired_record_id = NULL WHERE dive_request_id = ?",
                requestId);
    }

    /** 取某潜次某版本成对生效的 BOTTOM 记录（每名组员一条）。 */
    public List<RecordRow> findEffectiveBottom(String requestId, int version) {
        return jdbc.query("SELECT * FROM dive_record WHERE dive_request_id = ? AND plan_version = ? "
                        + "AND phase = 'BOTTOM' AND effective = TRUE AND frozen = FALSE",
                MAPPER, requestId, version);
    }

    /** 取某潜次某名组员某阶段成对生效、未冻结的记录（历史更正定位用）。 */
    public Optional<RecordRow> findEffective(String requestId, String diverCode, String phase) {
        return jdbc.query("SELECT * FROM dive_record WHERE dive_request_id = ? AND diver_code = ? "
                        + "AND phase = ? AND effective = TRUE AND frozen = FALSE ORDER BY id LIMIT 1",
                MAPPER, requestId, diverCode, phase).stream().findFirst();
    }

    /**
     * 24 小时（UTC）滚动窗口内某潜水员已入账的水下时间（分钟），窗口含两端时刻。
     * 仅成对生效的 BOTTOM 记录入账；方案冻结时旧台账同事务删除，因此窗口内即为有效累计，可复算。
     */
    public int sumBottomMinutes(String diverCode, Timestamp windowStart, Timestamp windowEnd) {
        Integer total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(bottom_min), 0) FROM bottom_time_ledger "
                        + "WHERE diver_code = ? AND started_at_utc >= ? AND started_at_utc <= ?",
                Integer.class, diverCode, windowStart, windowEnd);
        return total == null ? 0 : total;
    }

    /** 方案变更冻结时，同事务删除该潜次旧方案产生的台账，避免旧方案时间继续占用 6 小时额度。 */
    public int deleteLedgerByDive(String requestId) {
        return jdbc.update("DELETE FROM bottom_time_ledger WHERE dive_request_id = ?", requestId);
    }

    /** 台账是否已为指定成对记录入账（幂等）。 */
    public boolean ledgerExists(String requestId, int version, String diverCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM bottom_time_ledger WHERE dive_request_id = ? "
                        + "AND plan_version = ? AND diver_code = ?",
                Integer.class, requestId, version, diverCode);
        return count != null && count > 0;
    }

    public void insertLedger(LedgerRow row) {
        jdbc.update("INSERT INTO bottom_time_ledger "
                        + "(diver_code, dive_request_id, plan_version, bottom_min, started_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.diverCode(), row.diveRequestId(), row.planVersion(), row.bottomMin(),
                Timestamp.from(row.startedAtUtc()));
    }

    /** 复算用：窗口内台账行（含右端点时刻，恰 24 小时边界与同刻多潜均可正确累计）。 */
    public List<LedgerRow> findLedgerWindowInclusive(String diverCode, Timestamp start,
                                                     Timestamp end) {
        return jdbc.query("SELECT * FROM bottom_time_ledger WHERE diver_code = ? "
                        + "AND started_at_utc >= ? AND started_at_utc <= ? ORDER BY started_at_utc",
                (rs, n) -> new LedgerRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getTimestamp(6).toInstant()),
                diverCode, start, end);
    }

    /** 速率复算：深度差/历时，米/分钟；BOTTOM 段速率为 0。 */
    public static BigDecimal rateOf(String phase, int fromDepthM, int toDepthM, int durationMin) {
        if ("BOTTOM".equals(phase)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.abs(toDepthM - fromDepthM))
                .divide(BigDecimal.valueOf(durationMin), 2, java.math.RoundingMode.HALF_UP);
    }
}
