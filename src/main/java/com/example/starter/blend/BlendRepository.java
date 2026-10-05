package com.example.starter.blend;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 调合服务数据访问层，基于 JdbcTemplate 参数化 SQL。
 * 体积单位立方米(m3)，硫含量单位mg/kg，蒸气压单位kPa，时间时区Asia/Shanghai。
 */
@Repository
public class BlendRepository {

    private final JdbcTemplate jdbc;

    public BlendRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 组分罐行。remainingVolume 为罐余量(m3)。 */
    public record ComponentTankRow(long id, String code, BigDecimal remainingVolume,
                                   BigDecimal octane, BigDecimal sulfur, BigDecimal vaporPressure) {
    }

    /** 成品规格行。 */
    public record SpecRow(String productCode, BigDecimal octaneLimit, BigDecimal sulfurLimit,
                          BigDecimal vaporPressureLimit, BigDecimal octaneMargin,
                          BigDecimal sulfurMargin, BigDecimal vaporPressureMargin) {
    }

    /** 调合单行。 */
    public record OrderRow(long id, String orderNo, String requestId, String productCode, long blendTankId,
                           String status, BigDecimal totalVolume, int reblendCount,
                           LocalDateTime createdAt, LocalDateTime releasedAt) {
    }

    /** 调合批次行。 */
    public record BatchRow(long id, long orderId, int seq, Long originBatchId, String status,
                           LocalDateTime createdAt) {
    }

    /** 批次组分快照行。 */
    public record BatchComponentRow(long batchId, long tankId, String tankCode, BigDecimal volume,
                                    BigDecimal octane, BigDecimal sulfur, BigDecimal vaporPressure) {
    }

    /** 化验采样行。 */
    public record SampleRow(long id, long batchId, int samplePoint, String requestId,
                            BigDecimal octane, BigDecimal sulfur, BigDecimal vaporPressure,
                            boolean qualified, String hints, LocalDateTime createdAt) {
    }

    /** 幂等记录行。responseStatus 为 -1 表示处理中。 */
    public record IdemRecord(String requestId, String endpoint, String fingerprint,
                             int responseStatus, String responseBody) {
    }

    /** 清洗单行。占用时间窗为半开区间[windowStart, windowEnd)。 */
    public record CleaningRow(long id, String cleanNo, String requestId, long blendTankId,
                              String fromProduct, String toProduct, String status,
                              LocalDateTime windowStart, LocalDateTime windowEnd,
                              LocalDateTime createdAt, LocalDateTime completedAt) {
    }

    /** 调合罐状态台账行。currentProduct 为 NULL 表示已清洗复位空置。 */
    public record TankStatusRow(long blendTankId, String currentProduct, String state) {
    }

    /** 调合单占用窗行：releasedAt 为 NULL 表示执行中，占用窗无限延伸。 */
    public record OrderWindowRow(long orderId, LocalDateTime createdAt, LocalDateTime releasedAt) {
    }

    /** 罐占用视图行。 */
    public record TankOccupancyRow(long blendTankId, String tankCode, String currentProduct, String state,
                                   Long executingOrderId, Long executingCleaningId,
                                   LocalDateTime cleaningWindowStart, LocalDateTime cleaningWindowEnd) {
    }

    private static final RowMapper<ComponentTankRow> TANK_MAPPER = (rs, n) -> new ComponentTankRow(
            rs.getLong("id"), rs.getString("code"), rs.getBigDecimal("remaining_volume"),
            rs.getBigDecimal("octane"), rs.getBigDecimal("sulfur"), rs.getBigDecimal("vapor_pressure"));

    private static final RowMapper<SpecRow> SPEC_MAPPER = (rs, n) -> new SpecRow(
            rs.getString("product_code"), rs.getBigDecimal("octane_limit"), rs.getBigDecimal("sulfur_limit"),
            rs.getBigDecimal("vapor_pressure_limit"), rs.getBigDecimal("octane_margin"),
            rs.getBigDecimal("sulfur_margin"), rs.getBigDecimal("vapor_pressure_margin"));

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"), rs.getString("order_no"), rs.getString("request_id"),
            rs.getString("product_code"), rs.getLong("blend_tank_id"), rs.getString("status"),
            rs.getBigDecimal("total_volume"), rs.getInt("reblend_count"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toLocalDateTime());

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getLong("order_id"), rs.getInt("seq"),
            rs.getObject("origin_batch_id") == null ? null : rs.getLong("origin_batch_id"),
            rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<BatchComponentRow> BATCH_COMPONENT_MAPPER = (rs, n) -> new BatchComponentRow(
            rs.getLong("batch_id"), rs.getLong("tank_id"), rs.getString("tank_code"), rs.getBigDecimal("volume"),
            rs.getBigDecimal("octane"), rs.getBigDecimal("sulfur"), rs.getBigDecimal("vapor_pressure"));

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("sample_point"), rs.getString("request_id"),
            rs.getBigDecimal("octane"), rs.getBigDecimal("sulfur"), rs.getBigDecimal("vapor_pressure"),
            rs.getBoolean("qualified"), rs.getString("hints"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<IdemRecord> IDEM_MAPPER = (rs, n) -> new IdemRecord(
            rs.getString("request_id"), rs.getString("endpoint"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private static final RowMapper<CleaningRow> CLEANING_MAPPER = (rs, n) -> new CleaningRow(
            rs.getLong("id"), rs.getString("clean_no"), rs.getString("request_id"),
            rs.getLong("blend_tank_id"), rs.getString("from_product"), rs.getString("to_product"),
            rs.getString("status"), rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getTimestamp("window_end").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime());

    private static final RowMapper<TankStatusRow> TANK_STATUS_MAPPER = (rs, n) -> new TankStatusRow(
            rs.getLong("blend_tank_id"), rs.getString("current_product"), rs.getString("state"));

    private static final RowMapper<OrderWindowRow> ORDER_WINDOW_MAPPER = (rs, n) -> new OrderWindowRow(
            rs.getLong("id"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toLocalDateTime());

    private static final RowMapper<TankOccupancyRow> TANK_OCCUPANCY_MAPPER = (rs, n) -> new TankOccupancyRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("current_product"), rs.getString("state"),
            rs.getObject("order_id") == null ? null : rs.getLong("order_id"),
            rs.getObject("cleaning_id") == null ? null : rs.getLong("cleaning_id"),
            rs.getTimestamp("window_start") == null ? null : rs.getTimestamp("window_start").toLocalDateTime(),
            rs.getTimestamp("window_end") == null ? null : rs.getTimestamp("window_end").toLocalDateTime());

    /**
     * 按牌号查询成品规格。
     */
    public Optional<SpecRow> findSpec(String productCode) {
        return queryOne("SELECT * FROM product_spec WHERE product_code = ?", SPEC_MAPPER, productCode);
    }

    /**
     * 调合罐是否存在。
     */
    public boolean blendTankExists(long blendTankId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM blend_tank WHERE id = ?",
                Integer.class, blendTankId);
        return count != null && count > 0;
    }

    /**
     * 按ID查询组分罐并加行锁（FOR UPDATE），用于切罐前校验余量。
     */
    public Optional<ComponentTankRow> findComponentTankForUpdate(long tankId) {
        return queryOne("SELECT * FROM component_tank WHERE id = ? FOR UPDATE", TANK_MAPPER, tankId);
    }

    /**
     * 扣减组分罐余量，带余量保护；返回受影响行数，0表示余量不足。
     */
    public int deductComponentVolume(long tankId, BigDecimal volume) {
        return jdbc.update("UPDATE component_tank SET remaining_volume = remaining_volume - ? "
                + "WHERE id = ? AND remaining_volume >= ?", volume, tankId, volume);
    }

    /**
     * 查询组分罐当前余量（测试与校验用）。
     */
    public BigDecimal componentRemainingVolume(long tankId) {
        return jdbc.queryForObject("SELECT remaining_volume FROM component_tank WHERE id = ?",
                BigDecimal.class, tankId);
    }

    /**
     * 新建调合单，返回自增ID。orderNo 先占位，由调用方随后回填。
     */
    public long insertOrder(String orderNo, String requestId, String productCode, long blendTankId,
                            String status, BigDecimal totalVolume, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_order (order_no, request_id, product_code, blend_tank_id, status, "
                            + "total_volume, reblend_count, created_at) VALUES (?,?,?,?,?,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo);
            ps.setString(2, requestId);
            ps.setString(3, productCode);
            ps.setLong(4, blendTankId);
            ps.setString(5, status);
            ps.setBigDecimal(6, totalVolume);
            ps.setTimestamp(7, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 回填调合单号。
     */
    public void updateOrderNo(long orderId, String orderNo) {
        jdbc.update("UPDATE blend_order SET order_no = ? WHERE id = ?", orderNo, orderId);
    }

    /**
     * 按ID查询调合单。
     */
    public Optional<OrderRow> findOrder(long orderId) {
        return queryOne("SELECT * FROM blend_order WHERE id = ?", ORDER_MAPPER, orderId);
    }

    /**
     * 更新调合单状态；releasedAt 仅在放行时非空。
     */
    public void updateOrderStatus(long orderId, String status, LocalDateTime releasedAt) {
        jdbc.update("UPDATE blend_order SET status = ?, released_at = ? WHERE id = ?",
                status, releasedAt == null ? null : Timestamp.valueOf(releasedAt), orderId);
    }

    /**
     * 返调次数加一并累加调合总量。
     */
    public void markReblended(long orderId, BigDecimal addedVolume) {
        jdbc.update("UPDATE blend_order SET reblend_count = reblend_count + 1, "
                + "total_volume = total_volume + ? WHERE id = ?", addedVolume, orderId);
    }

    /**
     * 新建调合批次，返回自增ID。
     */
    public long insertBatch(long orderId, int seq, Long originBatchId, String status, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_batch (order_id, seq, origin_batch_id, status, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setInt(2, seq);
            if (originBatchId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, originBatchId);
            }
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 查询调合单最新批次（序号最大）。
     */
    public Optional<BatchRow> findLatestBatch(long orderId) {
        return queryOne("SELECT * FROM blend_batch WHERE order_id = ? ORDER BY seq DESC LIMIT 1",
                BATCH_MAPPER, orderId);
    }

    /**
     * 查询调合单全部批次（按序号升序），用于批次链复算。
     */
    public List<BatchRow> findBatches(long orderId) {
        return jdbc.query("SELECT * FROM blend_batch WHERE order_id = ? ORDER BY seq", BATCH_MAPPER, orderId);
    }

    /**
     * 更新批次状态。
     */
    public void updateBatchStatus(long batchId, String status) {
        jdbc.update("UPDATE blend_batch SET status = ? WHERE id = ?", status, batchId);
    }

    /**
     * 记录批次组分快照。
     */
    public void insertBatchComponent(long batchId, long tankId, String tankCode, BigDecimal volume,
                                     BigDecimal octane, BigDecimal sulfur, BigDecimal vaporPressure) {
        jdbc.update("INSERT INTO blend_batch_component (batch_id, tank_id, tank_code, volume, octane, sulfur, "
                        + "vapor_pressure) VALUES (?,?,?,?,?,?,?)",
                batchId, tankId, tankCode, volume, octane, sulfur, vaporPressure);
    }

    /**
     * 查询批次组分快照。
     */
    public List<BatchComponentRow> findBatchComponents(long batchId) {
        return jdbc.query("SELECT * FROM blend_batch_component WHERE batch_id = ? ORDER BY id",
                BATCH_COMPONENT_MAPPER, batchId);
    }

    /**
     * 记录化验采样结果，返回自增ID。
     */
    public long insertSample(long batchId, int samplePoint, String requestId, BigDecimal octane,
                             BigDecimal sulfur, BigDecimal vaporPressure, boolean qualified,
                             String hints, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lab_sample (batch_id, sample_point, request_id, octane, sulfur, "
                            + "vapor_pressure, qualified, hints, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, samplePoint);
            ps.setString(3, requestId);
            ps.setBigDecimal(4, octane);
            ps.setBigDecimal(5, sulfur);
            ps.setBigDecimal(6, vaporPressure);
            ps.setBoolean(7, qualified);
            ps.setString(8, hints);
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 查询批次全部采样记录（按采样点升序）。
     */
    public List<SampleRow> findSamples(long batchId) {
        return jdbc.query("SELECT * FROM lab_sample WHERE batch_id = ? ORDER BY sample_point",
                SAMPLE_MAPPER, batchId);
    }

    /**
     * 记录封账后迟到化验事件。
     */
    public void insertLateEvent(long orderId, Long batchId, Integer samplePoint, String requestId,
                                String detail, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO late_event (order_id, batch_id, sample_point, request_id, detail, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                orderId, batchId, samplePoint, requestId, detail, Timestamp.valueOf(createdAt));
    }

    /**
     * 查询调合单迟到事件说明（按时间升序）。
     */
    public List<String> findLateEvents(long orderId) {
        return jdbc.queryForList("SELECT detail FROM late_event WHERE order_id = ? ORDER BY id",
                String.class, orderId);
    }

    /**
     * 查询幂等记录。
     */
    public Optional<IdemRecord> findIdempotency(String requestId) {
        return queryOne("SELECT * FROM idempotency_record WHERE request_id = ?", IDEM_MAPPER, requestId);
    }

    /**
     * 插入幂等占位记录（处理中）。唯一键冲突时抛出 DuplicateKeyException。
     */
    public void insertIdempotency(String requestId, String endpoint, String fingerprint,
                                  LocalDateTime createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, endpoint, fingerprint, response_status, "
                + "response_body, created_at) VALUES (?,?,?,-1,NULL,?)", requestId, endpoint, fingerprint,
                Timestamp.valueOf(createdAt));
    }

    /**
     * 完成幂等记录，写入首次响应。
     */
    public void completeIdempotency(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    /**
     * 删除幂等记录（系统异常时允许重试）。
     */
    public void deleteIdempotency(String requestId) {
        jdbc.update("DELETE FROM idempotency_record WHERE request_id = ?", requestId);
    }

    /**
     * 占用调合罐执行锁。唯一键冲突时抛出 DuplicateKeyException。
     */
    public void acquireTankLock(long blendTankId, long orderId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO blend_tank_lock (blend_tank_id, order_id, created_at) VALUES (?,?,?)",
                blendTankId, orderId, Timestamp.valueOf(createdAt));
    }

    /**
     * 释放调合罐执行锁。
     */
    public void releaseTankLock(long blendTankId) {
        jdbc.update("DELETE FROM blend_tank_lock WHERE blend_tank_id = ?", blendTankId);
    }

    /**
     * 查询调合罐编码。
     */
    public Optional<String> findBlendTankCode(long blendTankId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT code FROM blend_tank WHERE id = ?", String.class, blendTankId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 查询调合罐全部调合单占用窗（[createdAt, releasedAt) 半开区间；releasedAt 为 NULL 表示执行中无限延伸）。
     */
    public List<OrderWindowRow> findOrderWindowsForTank(long blendTankId) {
        return jdbc.query("SELECT id, created_at, released_at FROM blend_order WHERE blend_tank_id = ? ORDER BY id",
                ORDER_WINDOW_MAPPER, blendTankId);
    }

    /**
     * 新建清洗单，返回自增ID。cleanNo 先占位，由调用方随后回填。
     */
    public long insertCleaning(String cleanNo, String requestId, long blendTankId, String fromProduct,
                               String toProduct, String status, LocalDateTime windowStart,
                               LocalDateTime windowEnd, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cleaning_order (clean_no, request_id, blend_tank_id, from_product, to_product, "
                            + "status, window_start, window_end, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cleanNo);
            ps.setString(2, requestId);
            ps.setLong(3, blendTankId);
            if (fromProduct == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, fromProduct);
            }
            ps.setString(5, toProduct);
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.valueOf(windowStart));
            ps.setTimestamp(8, Timestamp.valueOf(windowEnd));
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 回填清洗单号。
     */
    public void updateCleaningNo(long cleaningId, String cleanNo) {
        jdbc.update("UPDATE cleaning_order SET clean_no = ? WHERE id = ?", cleanNo, cleaningId);
    }

    /**
     * 按ID查询清洗单。
     */
    public Optional<CleaningRow> findCleaning(long cleaningId) {
        return queryOne("SELECT * FROM cleaning_order WHERE id = ?", CLEANING_MAPPER, cleaningId);
    }

    /**
     * 按ID查询清洗单并加行锁（FOR UPDATE），用于完成确认与状态流转。
     */
    public Optional<CleaningRow> findCleaningForUpdate(long cleaningId) {
        return queryOne("SELECT * FROM cleaning_order WHERE id = ? FOR UPDATE", CLEANING_MAPPER, cleaningId);
    }

    /**
     * 查询调合罐执行中的清洗单（按罐唯一，至多一条）。
     */
    public Optional<CleaningRow> findExecutingCleaningForTank(long blendTankId) {
        return queryOne("SELECT * FROM cleaning_order WHERE blend_tank_id = ? AND status = 'EXECUTING'",
                CLEANING_MAPPER, blendTankId);
    }

    /**
     * 查询逾期未完成（占用窗终点已过，半开区间端点相接即视为结束）的执行中清洗单。
     */
    public List<CleaningRow> findOverdueExecutingCleanings(LocalDateTime now) {
        return jdbc.query("SELECT * FROM cleaning_order WHERE status = 'EXECUTING' AND window_end <= ?",
                CLEANING_MAPPER, Timestamp.valueOf(now));
    }

    /**
     * 更新清洗单状态；completedAt 仅在完成确认时非空。
     */
    public void updateCleaningStatus(long cleaningId, String status, LocalDateTime completedAt) {
        jdbc.update("UPDATE cleaning_order SET status = ?, completed_at = ? WHERE id = ?",
                status, completedAt == null ? null : Timestamp.valueOf(completedAt), cleaningId);
    }

    /**
     * 占用清洗罐级锁。唯一键冲突时抛出 DuplicateKeyException。
     */
    public void acquireCleaningLock(long blendTankId, long cleaningId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO cleaning_tank_lock (blend_tank_id, cleaning_id, created_at) VALUES (?,?,?)",
                blendTankId, cleaningId, Timestamp.valueOf(createdAt));
    }

    /**
     * 释放清洗罐级锁。
     */
    public void releaseCleaningLock(long blendTankId) {
        jdbc.update("DELETE FROM cleaning_tank_lock WHERE blend_tank_id = ?", blendTankId);
    }

    /**
     * 确保罐状态台账行存在（初始为IDLE空置）；已存在时忽略唯一键冲突。
     */
    public void ensureTankStatus(long blendTankId, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO blend_tank_status (blend_tank_id, current_product, state, updated_at) "
                    + "VALUES (?,NULL,'IDLE',?)", blendTankId, Timestamp.valueOf(now));
        } catch (DuplicateKeyException ignored) {
            // 并发或已初始化：台账行已存在，无需处理
        }
    }

    /**
     * 查询罐状态台账。
     */
    public Optional<TankStatusRow> findTankStatus(long blendTankId) {
        return queryOne("SELECT * FROM blend_tank_status WHERE blend_tank_id = ?", TANK_STATUS_MAPPER, blendTankId);
    }

    /**
     * 更新罐状态台账：当前品种与罐状态。currentProduct 为 NULL 表示清洗复位空置。
     */
    public void updateTankStatus(long blendTankId, String currentProduct, String state, LocalDateTime now) {
        jdbc.update("UPDATE blend_tank_status SET current_product = ?, state = ?, updated_at = ? "
                + "WHERE blend_tank_id = ?", currentProduct, state, Timestamp.valueOf(now), blendTankId);
    }

    /**
     * 罐占用视图：全部调合罐关联状态台账、执行中调合单与执行中清洗占用窗。
     */
    public List<TankOccupancyRow> findTankOccupancy() {
        return jdbc.query("SELECT t.id, t.code, s.current_product, s.state, l.order_id, c.id AS cleaning_id, "
                        + "c.window_start, c.window_end "
                        + "FROM blend_tank t "
                        + "LEFT JOIN blend_tank_status s ON s.blend_tank_id = t.id "
                        + "LEFT JOIN blend_tank_lock l ON l.blend_tank_id = t.id "
                        + "LEFT JOIN cleaning_tank_lock cl ON cl.blend_tank_id = t.id "
                        + "LEFT JOIN cleaning_order c ON c.id = cl.cleaning_id "
                        + "ORDER BY t.id", TANK_OCCUPANCY_MAPPER);
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
