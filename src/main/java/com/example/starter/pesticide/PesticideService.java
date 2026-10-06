package com.example.starter.pesticide;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 药剂库存服务：库存查询、行级锁定读取与采购建议查询。
 */
@Service
public class PesticideService {

    private static final RowMapper<PesticideRow> MAPPER = (rs, rowNum) -> new PesticideRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getBigDecimal("quantity"),
            rs.getString("unit"),
            rs.getBigDecimal("safety_line"),
            rs.getObject("expiry_date", LocalDate.class));

    private final JdbcClient jdbc;

    public PesticideService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询药剂，不存在时抛出 404。
     */
    public PesticideRow mustFind(long pesticideId) {
        return jdbc.sql("SELECT * FROM pesticide WHERE id = ?")
                .param(pesticideId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("药剂不存在: " + pesticideId));
    }

    /**
     * 行级锁定读取药剂（用于同事务内扣减库存）。
     */
    public PesticideRow findForUpdate(long pesticideId) {
        return jdbc.sql("SELECT * FROM pesticide WHERE id = ? FOR UPDATE")
                .param(pesticideId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("药剂不存在: " + pesticideId));
    }

    /**
     * 药剂库存列表。
     */
    public List<PesticideResponse> list() {
        return jdbc.sql("SELECT * FROM pesticide ORDER BY id")
                .query(MAPPER)
                .list()
                .stream()
                .map(row -> new PesticideResponse(row.id(), row.name(), row.quantity(), row.unit(),
                        row.safetyLine(), row.expiryDate()))
                .toList();
    }

    /**
     * 采购建议列表（与最近投放记录勾稽）。
     */
    public List<PurchaseSuggestionResponse> listSuggestions() {
        return jdbc.sql("SELECT s.id, s.pesticide_id, p.name, s.suggested_quantity, s.reason,"
                        + " s.placement_id, s.created_at"
                        + " FROM purchase_suggestion s JOIN pesticide p ON s.pesticide_id = p.id"
                        + " ORDER BY s.id")
                .query((rs, rowNum) -> new PurchaseSuggestionResponse(
                        rs.getLong("id"),
                        rs.getLong("pesticide_id"),
                        rs.getString("name"),
                        rs.getBigDecimal("suggested_quantity"),
                        rs.getString("reason"),
                        rs.getLong("placement_id"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    /**
     * 药剂库存响应。
     */
    public record PesticideResponse(long id, String name, BigDecimal quantity, String unit,
            BigDecimal safetyLine, LocalDate expiryDate) {
    }

    /**
     * 采购建议响应。
     *
     * @param placementId 勾稽的最近投放记录
     */
    public record PurchaseSuggestionResponse(long id, long pesticideId, String pesticideName,
            BigDecimal suggestedQuantity, String reason, long placementId,
            LocalDateTime createdAt) {
    }
}
