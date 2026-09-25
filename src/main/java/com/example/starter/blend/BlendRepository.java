package com.example.starter.blend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 调合服务数据访问层：组分罐、成品规格、调合单、批次、切罐流水、化验样本与调合罐执行锁。
 * 所有写操作由服务层在同一事务内编排，保证切罐与扣量原子提交。
 */
@Repository
public class BlendRepository {

    private final JdbcClient jdbc;

    public BlendRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 组分罐行：stockKg 为当前罐余量（kg）。 */
    public record TankRow(long id, String code, String name, BigDecimal octane, BigDecimal sulfur,
                          BigDecimal rvp, BigDecimal stockKg) {
    }

    /** 成品规格行：上限与安全边际，语义同 product_spec 表注释。 */
    public record SpecRow(long id, String productCode, BigDecimal octaneMax, BigDecimal sulfurMax,
                          BigDecimal rvpMax, BigDecimal octaneMargin, BigDecimal sulfurMargin,
                          BigDecimal rvpMargin) {
        public BlendModel.SpecLimits toLimits() {
            return new BlendModel.SpecLimits(octaneMax, sulfurMax, rvpMax,
                    octaneMargin, sulfurMargin, rvpMargin);
        }
    }

    /** 调合单行：status 取值 EXECUTING / RELEASED / OFF_SPEC。 */
    public record OrderRow(long id, String requestId, String fingerprint, String blendTankCode,
                           String productCode, BigDecimal targetQtyKg, String status,
                           Instant createdAt, Instant releasedAt) {
    }

    /** 调合批次行：parentBatchId 为 NULL 表示首批。 */
    public record BatchRow(long id, long orderId, int batchNo, Long parentBatchId, String requestId,
                           String fingerprint, String status, BigDecimal totalQtyKg,
                           BigDecimal octane, BigDecimal sulfur, BigDecimal rvp, Instant createdAt) {
    }

    /** 组分明细行。 */
    public record ComponentRow(long id, long orderId, long batchId, String tankCode, BigDecimal qtyKg) {
    }

    /** 切罐流水行。 */
    public record MovementRow(long id, long orderId, long batchId, String tankCode, BigDecimal qtyKg,
                              Instant createdAt) {
    }

    /** 化验样本行：late=TRUE 表示封账后迟到数据，仅记录不改判定。 */
    public record SampleRow(long id, String requestId, String fingerprint, long orderId, long batchId,
                            int samplePoint, BigDecimal octane, BigDecimal sulfur, BigDecimal rvp,
                            boolean passed, boolean late, Instant createdAt) {
    }

    public Optional<TankRow> findTank(String code) {
        return jdbc.sql("SELECT * FROM component_tank WHERE code = :code")
                .param("code", code)
                .query(TankRow.class)
                .optional();
    }

    public List<TankRow> findAllTanks() {
        return jdbc.sql("SELECT * FROM component_tank ORDER BY code").query(TankRow.class).list();
    }

    public Optional<SpecRow> findSpec(String productCode) {
        return jdbc.sql("SELECT * FROM product_spec WHERE product_code = :code")
                .param("code", productCode)
                .query(SpecRow.class)
                .optional();
    }

    /**
     * 原子扣减罐余量：仅当余量充足时扣减，返回受影响行数；0 行表示余量不足。
     */
    public int deductTankStock(String code, BigDecimal qtyKg) {
        return jdbc.sql("UPDATE component_tank SET stock_kg = stock_kg - :qty "
                        + "WHERE code = :code AND stock_kg >= :qty")
                .param("qty", qtyKg)
                .param("code", code)
                .update();
    }

    public Optional<OrderRow> findOrderByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM blend_order WHERE request_id = :rid")
                .param("rid", requestId)
                .query(OrderRow.class)
                .optional();
    }

    public Optional<OrderRow> findOrder(long id) {
        return jdbc.sql("SELECT * FROM blend_order WHERE id = :id")
                .param("id", id)
                .query(OrderRow.class)
                .optional();
    }

    /** 悲观锁读取调合单，用于状态流转的串行化。 */
    public Optional<OrderRow> findOrderForUpdate(long id) {
        return jdbc.sql("SELECT * FROM blend_order WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(OrderRow.class)
                .optional();
    }

    public long insertOrder(String requestId, String fingerprint, String blendTankCode,
                            String productCode, BigDecimal targetQtyKg, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO blend_order (request_id, fingerprint, blend_tank_code, "
                        + "product_code, target_qty_kg, status) VALUES (:rid, :fp, :tank, :prod, :qty, :st)")
                .param("rid", requestId)
                .param("fp", fingerprint)
                .param("tank", blendTankCode)
                .param("prod", productCode)
                .param("qty", targetQtyKg)
                .param("st", status)
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public void updateOrderStatus(long orderId, String status, Instant releasedAt) {
        jdbc.sql("UPDATE blend_order SET status = :st, released_at = :ra WHERE id = :id")
                .param("st", status)
                .param("ra", releasedAt == null ? null : Timestamp.from(releasedAt))
                .param("id", orderId)
                .update();
    }

    /** 占用调合罐执行锁；主键冲突即表示该罐已有执行中调合单。 */
    public void acquireTankLock(String blendTankCode, long orderId) {
        jdbc.sql("INSERT INTO blend_tank_lock (blend_tank_code, order_id) VALUES (:code, :oid)")
                .param("code", blendTankCode)
                .param("oid", orderId)
                .update();
    }

    public void releaseTankLock(String blendTankCode) {
        jdbc.sql("DELETE FROM blend_tank_lock WHERE blend_tank_code = :code")
                .param("code", blendTankCode)
                .update();
    }

    public long insertBatch(long orderId, int batchNo, Long parentBatchId, String requestId,
                            String fingerprint, String status, BigDecimal totalQtyKg,
                            BlendModel.IndicatorSet ind) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO blend_batch (order_id, batch_no, parent_batch_id, request_id, "
                        + "fingerprint, status, total_qty_kg, octane, sulfur, rvp) "
                        + "VALUES (:oid, :no, :parent, :rid, :fp, :st, :qty, :oct, :sul, :rvp)")
                .param("oid", orderId)
                .param("no", batchNo)
                .param("parent", parentBatchId)
                .param("rid", requestId)
                .param("fp", fingerprint)
                .param("st", status)
                .param("qty", totalQtyKg)
                .param("oct", ind.octane())
                .param("sul", ind.sulfur())
                .param("rvp", ind.rvp())
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<BatchRow> findBatchByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM blend_batch WHERE request_id = :rid")
                .param("rid", requestId)
                .query(BatchRow.class)
                .optional();
    }

    public List<BatchRow> findBatches(long orderId) {
        return jdbc.sql("SELECT * FROM blend_batch WHERE order_id = :oid ORDER BY batch_no")
                .param("oid", orderId)
                .query(BatchRow.class)
                .list();
    }

    public void updateBatchStatus(long batchId, String status) {
        jdbc.sql("UPDATE blend_batch SET status = :st WHERE id = :id")
                .param("st", status)
                .param("id", batchId)
                .update();
    }

    public void insertComponent(long orderId, long batchId, String tankCode, BigDecimal qtyKg) {
        jdbc.sql("INSERT INTO blend_order_component (order_id, batch_id, tank_code, qty_kg) "
                        + "VALUES (:oid, :bid, :tank, :qty)")
                .param("oid", orderId)
                .param("bid", batchId)
                .param("tank", tankCode)
                .param("qty", qtyKg)
                .update();
    }

    public List<ComponentRow> findComponents(long orderId) {
        return jdbc.sql("SELECT * FROM blend_order_component WHERE order_id = :oid ORDER BY id")
                .param("oid", orderId)
                .query(ComponentRow.class)
                .list();
    }

    public void insertMovement(long orderId, long batchId, String tankCode, BigDecimal qtyKg) {
        jdbc.sql("INSERT INTO tank_movement (order_id, batch_id, tank_code, qty_kg) "
                        + "VALUES (:oid, :bid, :tank, :qty)")
                .param("oid", orderId)
                .param("bid", batchId)
                .param("tank", tankCode)
                .param("qty", qtyKg)
                .update();
    }

    public List<MovementRow> findMovements(long orderId) {
        return jdbc.sql("SELECT * FROM tank_movement WHERE order_id = :oid ORDER BY id")
                .param("oid", orderId)
                .query(MovementRow.class)
                .list();
    }

    public Optional<SampleRow> findSampleByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM lab_sample WHERE request_id = :rid")
                .param("rid", requestId)
                .query(SampleRow.class)
                .optional();
    }

    public List<SampleRow> findSamples(long orderId) {
        return jdbc.sql("SELECT * FROM lab_sample WHERE order_id = :oid ORDER BY id")
                .param("oid", orderId)
                .query(SampleRow.class)
                .list();
    }

    public List<SampleRow> findBatchSamples(long batchId) {
        return jdbc.sql("SELECT * FROM lab_sample WHERE batch_id = :bid AND late = FALSE ORDER BY sample_point")
                .param("bid", batchId)
                .query(SampleRow.class)
                .list();
    }

    public long insertSample(String requestId, String fingerprint, long orderId, long batchId,
                             int samplePoint, BlendModel.IndicatorSet ind, boolean passed, boolean late) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO lab_sample (request_id, fingerprint, order_id, batch_id, "
                        + "sample_point, octane, sulfur, rvp, passed, late) "
                        + "VALUES (:rid, :fp, :oid, :bid, :sp, :oct, :sul, :rvp, :passed, :late)")
                .param("rid", requestId)
                .param("fp", fingerprint)
                .param("oid", orderId)
                .param("bid", batchId)
                .param("sp", samplePoint)
                .param("oct", ind.octane())
                .param("sul", ind.sulfur())
                .param("rvp", ind.rvp())
                .param("passed", passed)
                .param("late", late)
                .update(kh, "id");
        return kh.getKey().longValue();
    }
}
