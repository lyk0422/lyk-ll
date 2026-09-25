package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 追加保证金入账数据访问。request_id 全局唯一，保证同键不重复入账；
 * 入账结果列（ratio_after_bp 等）使同键同参重放可返回首次结果。
 */
@Repository
public class DepositRepository {

    /** 入账记录行。amount 单位：分；ratioAfterBp 单位：基点，无负债为 null。 */
    public record DepositRow(long id, String requestId, long accountId, long amount,
                             String currency, String fingerprint, Integer ratioAfterBp,
                             Long resolvedMarginCallId, String cancelledOrderIds,
                             LocalDateTime createdAt) {
    }

    private static final RowMapper<DepositRow> MAPPER = (rs, i) -> new DepositRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("account_id"),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getString("fingerprint"),
            rs.getObject("ratio_after_bp") == null ? null : rs.getInt("ratio_after_bp"),
            rs.getObject("resolved_margin_call_id") == null ? null : rs.getLong("resolved_margin_call_id"),
            rs.getString("cancelled_order_ids"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public DepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试插入入账记录。
     *
     * @return 插入成功的记录ID；requestId 已存在时返回 empty（由调用方比对指纹决定幂等返回或 409）
     */
    public Optional<Long> tryInsert(String requestId, long accountId, long amount,
                                    String currency, String fingerprint, LocalDateTime createdAt) {
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO deposit (request_id, account_id, amount, currency, fingerprint, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        new String[]{"id"});
                ps.setString(1, requestId);
                ps.setLong(2, accountId);
                ps.setLong(3, amount);
                ps.setString(4, currency);
                ps.setString(5, fingerprint);
                ps.setTimestamp(6, Timestamp.valueOf(createdAt));
                return ps;
            }, kh);
            return Optional.of(Objects.requireNonNull(kh.getKey()).longValue());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Optional<DepositRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM deposit WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 回写首次入账的风控结果，供幂等重放返回首次结果。 */
    public void updateOutcome(long id, Integer ratioAfterBp, Long resolvedMarginCallId,
                              String cancelledOrderIds) {
        jdbc.update("UPDATE deposit SET ratio_after_bp = ?, resolved_margin_call_id = ?,"
                        + " cancelled_order_ids = ? WHERE id = ?",
                ratioAfterBp, resolvedMarginCallId, cancelledOrderIds, id);
    }
}
