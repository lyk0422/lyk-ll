package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.invoice.domain.InfoStatus;
import com.example.starter.invoice.domain.Opinion;
import com.example.starter.invoice.domain.Party;
import com.example.starter.invoice.domain.RedType;

/**
 * 红字信息表、行项目与确认轨迹数据访问。
 */
@Repository
public class RedInfoRepository {

    private final JdbcClient jdbc;

    public RedInfoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入红字信息表，返回生成的主键。
     */
    public long insert(String requestId, String fingerprint, String infoNo, long originalInvoiceId,
                       RedType redType, String sellerTaxNo, String buyerTaxNo, BigDecimal totalAmount,
                       LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO red_info_table (request_id, fingerprint, info_no, original_invoice_id, red_type,"
                        + " status, seller_tax_no, buyer_tax_no, total_amount, seller_confirmed, buyer_confirmed,"
                        + " red_invoice_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, 0, 0, NULL, ?, ?)")
                .params(requestId, fingerprint, infoNo, originalInvoiceId, redType.name(), sellerTaxNo,
                        buyerTaxNo, totalAmount, now, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入部分红冲行项目。
     */
    public void insertLine(long infoTableId, long originalLineId, BigDecimal amount) {
        jdbc.sql("INSERT INTO red_info_line (info_table_id, original_line_id, amount) VALUES (?, ?, ?)")
                .params(infoTableId, originalLineId, amount)
                .update();
    }

    /**
     * 按幂等请求号查询信息表。
     */
    public Optional<RedInfoRow> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM red_info_table WHERE request_id = ?")
                .param(requestId)
                .query(RedInfoRow.class)
                .optional();
    }

    /**
     * 按编号查询信息表（不加锁）。
     */
    public Optional<RedInfoRow> findByInfoNo(String infoNo) {
        return jdbc.sql("SELECT * FROM red_info_table WHERE info_no = ?")
                .param(infoNo)
                .query(RedInfoRow.class)
                .optional();
    }

    /**
     * 按编号查询并锁定信息表行，双方确认在同一事务内串行推进。
     */
    public Optional<RedInfoRow> lockByInfoNo(String infoNo) {
        return jdbc.sql("SELECT * FROM red_info_table WHERE info_no = ? FOR UPDATE")
                .param(infoNo)
                .query(RedInfoRow.class)
                .optional();
    }

    /**
     * 查询信息表行项目。
     */
    public List<RedInfoLineRow> findLines(long infoTableId) {
        return jdbc.sql("SELECT * FROM red_info_line WHERE info_table_id = ? ORDER BY id")
                .param(infoTableId)
                .query(RedInfoLineRow.class)
                .list();
    }

    /**
     * 查询某蓝票最近一次红字信息表，用于终态幂等返回。
     */
    public Optional<RedInfoRow> findLatestByOriginalInvoiceId(long originalInvoiceId) {
        return jdbc.sql("SELECT * FROM red_info_table WHERE original_invoice_id = ? ORDER BY id DESC LIMIT 1")
                .param(originalInvoiceId)
                .query(RedInfoRow.class)
                .optional();
    }

    /**
     * 更新单方确认标记。
     */
    public void updatePartyConfirmed(long id, Party party, LocalDateTime now) {
        String column = party == Party.SELLER ? "seller_confirmed" : "buyer_confirmed";
        jdbc.sql("UPDATE red_info_table SET " + column + " = 1, updated_at = ? WHERE id = ?")
                .params(now, id)
                .update();
    }

    /**
     * 更新信息表状态（挂起或确认完成并关联红票）。
     */
    public void updateStatus(long id, InfoStatus status, Long redInvoiceId, LocalDateTime now) {
        jdbc.sql("UPDATE red_info_table SET status = ?, red_invoice_id = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), redInvoiceId, now, id)
                .update();
    }

    /**
     * 记录确认轨迹。
     */
    public void insertConfirmation(long infoTableId, Party party, Opinion opinion, String comment,
                                   LocalDateTime now) {
        jdbc.sql("INSERT INTO red_confirmation (info_table_id, party, opinion, comment, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(infoTableId, party.name(), opinion.name(), comment, now)
                .update();
    }

    /**
     * 查询信息表确认轨迹，按时间升序。
     */
    public List<RedConfirmationRow> findConfirmations(long infoTableId) {
        return jdbc.sql("SELECT * FROM red_confirmation WHERE info_table_id = ? ORDER BY id")
                .param(infoTableId)
                .query(RedConfirmationRow.class)
                .list();
    }
}
