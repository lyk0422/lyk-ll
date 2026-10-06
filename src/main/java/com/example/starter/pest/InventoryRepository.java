package com.example.starter.pest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 药剂库存、投放记录与采购建议数据访问。
 */
@Repository
public class InventoryRepository {

    /**
     * 药剂库存记录。
     *
     * @param expireDate 过期日期（当日及以后视为过期）
     */
    public record Pesticide(long id, String name, String batchNo, BigDecimal quantity, String unit,
                            BigDecimal safetyLine, LocalDate expireDate) {
    }

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 ID 查询药剂。
     */
    public Optional<Pesticide> findPesticide(long id) {
        return jdbc.sql("SELECT * FROM pesticide WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new Pesticide(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("batch_no"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getBigDecimal("safety_line"),
                        rs.getDate("expire_date").toLocalDate()))
                .optional();
    }

    /**
     * 扣减库存（带库存充足保护条件），返回受影响行数；0 表示库存不足。
     */
    public int deductStock(long pesticideId, BigDecimal dosage) {
        return jdbc.sql("UPDATE pesticide SET quantity = quantity - :dosage"
                        + " WHERE id = :id AND quantity >= :dosage")
                .param("dosage", dosage)
                .param("id", pesticideId)
                .update();
    }

    /**
     * 新增投放记录，返回自增 ID。
     */
    public long insertDispense(String requestId, long chainId, long pesticideId, BigDecimal dosage,
                               String points, String registrar1, String registrar2, Instant dispensedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dispense_record (request_id, chain_id, pesticide_id, dosage, points,"
                        + " registrar1, registrar2, dispensed_at)"
                        + " VALUES (:requestId, :chainId, :pesticideId, :dosage, :points,"
                        + " :registrar1, :registrar2, :dispensedAt)")
                .param("requestId", requestId)
                .param("chainId", chainId)
                .param("pesticideId", pesticideId)
                .param("dosage", dosage)
                .param("points", points)
                .param("registrar1", registrar1)
                .param("registrar2", registrar2)
                .param("dispensedAt", Timestamp.from(dispensedAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 新增采购建议并与最近投放记录勾稽，返回自增 ID。
     */
    public long insertPurchaseSuggestion(long pesticideId, BigDecimal suggestedQuantity, String reason,
                                         Long relatedDispenseId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO purchase_suggestion (pesticide_id, suggested_quantity, reason,"
                        + " related_dispense_id, created_at)"
                        + " VALUES (:pesticideId, :suggestedQuantity, :reason, :relatedDispenseId, :createdAt)")
                .param("pesticideId", pesticideId)
                .param("suggestedQuantity", suggestedQuantity)
                .param("reason", reason)
                .param("relatedDispenseId", relatedDispenseId)
                .param("createdAt", Timestamp.from(createdAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
