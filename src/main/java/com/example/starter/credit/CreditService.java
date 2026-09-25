package com.example.starter.credit;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开票方授信额度服务。
 * 可用额度＝总额度−未归档蓝票金额＋已确认红票金额，按公式实时复算；
 * 开票占用、红票确认恢复、归档释放均写入台账，台账可复算、重复查询结果稳定。
 */
@Service
public class CreditService {

    private final JdbcTemplate jdbc;

    public CreditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建开票方授信账户（合成数据）；已存在返回 409。
     */
    @Transactional
    public CreditView createAccount(CreateCreditAccountRequest request) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_account WHERE seller_id = ?",
                Integer.class, request.sellerId());
        if (exists != null && exists > 0) {
            throw ApiException.conflict("CREDIT_ACCOUNT_EXISTS", "授信账户已存在: " + request.sellerId());
        }
        jdbc.update("INSERT INTO credit_account (seller_id, total_limit) VALUES (?, ?)",
                request.sellerId(), request.totalLimit());
        return getCredit(request.sellerId());
    }

    /**
     * 查询授信账户与实时可用额度。
     */
    @Transactional(readOnly = true)
    public CreditView getCredit(String sellerId) {
        BigDecimal totalLimit = jdbc.query(
                        "SELECT total_limit FROM credit_account WHERE seller_id = ?",
                        (rs, rowNum) -> rs.getBigDecimal("total_limit"), sellerId)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("CREDIT_ACCOUNT_NOT_FOUND", "授信账户不存在: " + sellerId));
        return new CreditView(sellerId, totalLimit, computeAvailable(sellerId));
    }

    /**
     * 查询额度台账，按流水号升序，结果稳定可复算。
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> listLedger(String sellerId) {
        return jdbc.query(
                "SELECT id, seller_id, change_amount, reason, ref_no, available_after, created_at"
                        + " FROM credit_ledger WHERE seller_id = ? ORDER BY id",
                (rs, rowNum) -> new LedgerEntry(
                        rs.getLong("id"),
                        rs.getString("seller_id"),
                        rs.getBigDecimal("change_amount"),
                        rs.getString("reason"),
                        rs.getString("ref_no"),
                        rs.getBigDecimal("available_after"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                sellerId);
    }

    /**
     * 按公式复算可用额度：总额度−未归档蓝票金额＋已确认红票金额。
     * 归档（ARCHIVED）蓝票不再占用；全额红冲蓝票占用与红票恢复相互抵销。
     */
    public BigDecimal computeAvailable(String sellerId) {
        BigDecimal result = jdbc.queryForObject(
                "SELECT a.total_limit"
                        + " - COALESCE((SELECT SUM(i.total_amount) FROM invoice i"
                        + "   WHERE i.seller_id = a.seller_id AND i.kind = 'BLUE' AND i.status <> 'ARCHIVED'), 0)"
                        + " + COALESCE((SELECT SUM(r.total_amount) FROM invoice r"
                        + "   WHERE r.seller_id = a.seller_id AND r.kind = 'RED'), 0)"
                        + " FROM credit_account a WHERE a.seller_id = ?",
                BigDecimal.class, sellerId);
        if (result == null) {
            throw ApiException.notFound("CREDIT_ACCOUNT_NOT_FOUND", "授信账户不存在: " + sellerId);
        }
        return result;
    }

    /**
     * 对授信账户加行级悲观锁（须在事务内调用），用于开票/红冲串行校验。
     */
    public void lockAccount(String sellerId) {
        jdbc.query(
                        "SELECT seller_id FROM credit_account WHERE seller_id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getString("seller_id"), sellerId)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("CREDIT_ACCOUNT_NOT_FOUND", "授信账户不存在: " + sellerId));
    }

    /**
     * 写入额度台账（须在事务内调用）。availableAfter 由调用方按变动前后额度计算。
     */
    public void appendLedger(String sellerId, BigDecimal changeAmount, String reason,
                             String refNo, BigDecimal availableAfter) {
        jdbc.update(
                "INSERT INTO credit_ledger (seller_id, change_amount, reason, ref_no, available_after)"
                        + " VALUES (?, ?, ?, ?, ?)",
                sellerId, changeAmount, reason, refNo, availableAfter);
    }

    /**
     * 创建授信账户请求。
     *
     * @param sellerId   开票方ID
     * @param totalLimit 授信总额度，单位元
     */
    public record CreateCreditAccountRequest(String sellerId, BigDecimal totalLimit) {
    }

    /**
     * 授信视图。
     *
     * @param sellerId       开票方ID
     * @param totalLimit     授信总额度，单位元
     * @param availableLimit 可用额度，单位元
     */
    public record CreditView(String sellerId, BigDecimal totalLimit, BigDecimal availableLimit) {
    }

    /**
     * 额度台账条目。
     */
    public record LedgerEntry(Long id, String sellerId, BigDecimal changeAmount, String reason,
                              String refNo, BigDecimal availableAfter, LocalDateTime createdAt) {
    }
}
