package com.example.starter.rt.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 排程仓储：合成设备、分次排程批次与排程明细（UTC 半开窗口占用）。
 *
 * <p>并发约定：涉及同一患者的排程操作先锁患者行，涉及同一设备的占用检查在设备行锁保护下完成，
 * 锁顺序全局一致（患者→设备→排程明细），与 {@link PlanRepository} 的患者锁顺序兼容。</p>
 */
@Repository
public class ScheduleRepository {

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 设备行。 */
    public record DeviceRow(
            String deviceId,
            String deviceName,
            int maxLevel,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 排程批次行（时间列均为 UTC）。 */
    public record BatchRow(
            Long id,
            String patientId,
            String planCode,
            Long planVersionId,
            String deviceId,
            int level,
            int requiredMinutes,
            int fractionCount,
            String status,
            int version,
            String requestId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime cancelledAt) {
    }

    /** 排程明细行（窗口与实绩时间均为 UTC）。 */
    public record ItemRow(
            Long id,
            Long batchId,
            String patientId,
            String planCode,
            Long planVersionId,
            String deviceId,
            int level,
            int fractionNo,
            LocalDateTime startUtc,
            LocalDateTime endUtc,
            String status,
            Long deliveredDoseCgy,
            LocalDateTime executedAtUtc,
            LocalDateTime cancelledAt) {
    }

    // ============================ 设备 ============================

    /** 登记合成设备，初始版本为 1。 */
    public void insertDevice(String deviceId, String deviceName, int maxLevel, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO device (device_id, device_name, max_level, version, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """, deviceId, deviceName, maxLevel, now, now);
    }

    public Optional<DeviceRow> findDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, max_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /** 锁定设备行（SELECT ... FOR UPDATE），串行化同设备能力变更与占用窗口检查。 */
    public Optional<DeviceRow> lockDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, max_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                FOR UPDATE
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁能力变更：仅当当前版本等于 expectedVersion 时生效，版本加 1。
     *
     * @return 生效行数（0 表示版本冲突）
     */
    public int updateDeviceCapability(String deviceId, int maxLevel, int expectedVersion,
                                      LocalDateTime now) {
        return jdbc.update("""
                UPDATE device SET max_level = ?, version = version + 1, updated_at = ?
                WHERE device_id = ? AND version = ?
                """, maxLevel, now, deviceId, expectedVersion);
    }

    // ============================ 排程批次 ============================

    /** 插入排程批次（初始状态 ACTIVE、版本 1），返回批次ID。 */
    public long insertBatch(String patientId, String planCode, long planVersionId, String deviceId,
                            int level, int requiredMinutes, int fractionCount, String requestId,
                            LocalDateTime now) {
        jdbc.update("""
                INSERT INTO schedule_batch
                    (patient_id, plan_code, plan_version_id, device_id, level, required_minutes,
                     fraction_count, status, version, request_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?)
                """, patientId, planCode, planVersionId, deviceId, level, requiredMinutes,
                fractionCount, requestId, now, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM schedule_batch WHERE request_id = ?", Long.class, requestId);
        return id == null ? 0L : id;
    }

    public Optional<BatchRow> findBatchById(long batchId) {
        List<BatchRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, device_id, level,
                       required_minutes, fraction_count, status, version, request_id,
                       created_at, updated_at, cancelled_at
                FROM schedule_batch WHERE id = ?
                """, new BatchRowMapper(), batchId);
        return rows.stream().findFirst();
    }

    /** 锁定批次行（SELECT ... FOR UPDATE），串行化取消与执行竞态。 */
    public Optional<BatchRow> lockBatchById(long batchId) {
        List<BatchRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, device_id, level,
                       required_minutes, fraction_count, status, version, request_id,
                       created_at, updated_at, cancelled_at
                FROM schedule_batch WHERE id = ?
                FOR UPDATE
                """, new BatchRowMapper(), batchId);
        return rows.stream().findFirst();
    }

    /** 批次状态推进并乐观锁版本加 1。 */
    public void updateBatchStatus(long batchId, String status, LocalDateTime now) {
        jdbc.update("""
                UPDATE schedule_batch SET status = ?, version = version + 1, updated_at = ?
                WHERE id = ?
                """, status, now, batchId);
    }

    /** 批次取消落戳（状态推进由 {@link #updateBatchStatus} 完成）。 */
    public void markBatchCancelled(long batchId, LocalDateTime now) {
        jdbc.update("""
                UPDATE schedule_batch SET status = 'CANCELLED', version = version + 1,
                       updated_at = ?, cancelled_at = ?
                WHERE id = ?
                """, now, now, batchId);
    }

    public List<BatchRow> findBatchesByPlan(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, device_id, level,
                       required_minutes, fraction_count, status, version, request_id,
                       created_at, updated_at, cancelled_at
                FROM schedule_batch
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY id
                """, new BatchRowMapper(), patientId, planCode);
    }

    // ============================ 排程明细 ============================

    /** 插入排程明细（初始状态 ACTIVE，即占用窗口资源）。 */
    public void insertItem(long batchId, String patientId, String planCode, long planVersionId,
                           String deviceId, int level, int fractionNo,
                           LocalDateTime startUtc, LocalDateTime endUtc) {
        jdbc.update("""
                INSERT INTO schedule_item
                    (batch_id, patient_id, plan_code, plan_version_id, device_id, level,
                     fraction_no, start_utc, end_utc, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, batchId, patientId, planCode, planVersionId, deviceId, level,
                fractionNo, startUtc, endUtc);
    }

    public List<ItemRow> findItemsByBatch(long batchId) {
        return jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id, level,
                       fraction_no, start_utc, end_utc, status,
                       delivered_dose_cgy, executed_at_utc, cancelled_at
                FROM schedule_item WHERE batch_id = ?
                ORDER BY fraction_no
                """, new ItemRowMapper(), batchId);
    }

    /** 同患者+计划+分次的 ACTIVE 占用明细（双占检查用）。 */
    public Optional<ItemRow> findActiveItem(String patientId, String planCode, int fractionNo) {
        List<ItemRow> rows = jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id, level,
                       fraction_no, start_utc, end_utc, status,
                       delivered_dose_cgy, executed_at_utc, cancelled_at
                FROM schedule_item
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ? AND status = 'ACTIVE'
                """, new ItemRowMapper(), patientId, planCode, fractionNo);
        return rows.stream().findFirst();
    }

    /** 按主键锁定明细行（SELECT ... FOR UPDATE），串行化取消与执行竞态。 */
    public Optional<ItemRow> lockItemById(long itemId) {
        List<ItemRow> rows = jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id, level,
                       fraction_no, start_utc, end_utc, status,
                       delivered_dose_cgy, executed_at_utc, cancelled_at
                FROM schedule_item WHERE id = ?
                FOR UPDATE
                """, new ItemRowMapper(), itemId);
        return rows.stream().findFirst();
    }

    /**
     * 同设备 ACTIVE 占用中与给定半开窗口 [startUtc, endUtc) 重叠的明细数。
     * 半开区间端点相接（end == start）不算重叠。
     */
    public int countOverlappingActiveItems(String deviceId, LocalDateTime startUtc,
                                           LocalDateTime endUtc) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM schedule_item
                WHERE device_id = ? AND status = 'ACTIVE'
                  AND start_utc < ? AND end_utc > ?
                """, Integer.class, deviceId, endUtc, startUtc);
        return count == null ? 0 : count;
    }

    /** 执行完成：原子释放占用并冻结实绩（剂量与 UTC 执行时间）。 */
    public void completeItem(long itemId, long deliveredDoseCgy, LocalDateTime executedAtUtc) {
        jdbc.update("""
                UPDATE schedule_item
                SET status = 'COMPLETED', delivered_dose_cgy = ?, executed_at_utc = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, deliveredDoseCgy, executedAtUtc, itemId);
    }

    /** 取消单个 ACTIVE 明细，释放占用。 */
    public void cancelItem(long itemId, LocalDateTime cancelledAt) {
        jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', cancelled_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, cancelledAt, itemId);
    }

    /**
     * 计划改版：取消同患者+计划下、指定保留版本以外的全部 ACTIVE 明细并释放资源。
     * 已执行（COMPLETED）明细的剂量与实绩不受影响。
     *
     * @return 受影响批次ID列表（用于批次状态推进）
     */
    public List<Long> cancelActiveItemsOfOtherVersions(String patientId, String planCode,
                                                       long keepVersionId,
                                                       LocalDateTime cancelledAt) {
        List<Long> batchIds = jdbc.query("""
                SELECT DISTINCT batch_id FROM schedule_item
                WHERE patient_id = ? AND plan_code = ? AND plan_version_id <> ? AND status = 'ACTIVE'
                """, (rs, n) -> rs.getLong(1), patientId, planCode, keepVersionId);
        jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', cancelled_at = ?
                WHERE patient_id = ? AND plan_code = ? AND plan_version_id <> ? AND status = 'ACTIVE'
                """, cancelledAt, patientId, planCode, keepVersionId);
        return batchIds;
    }

    /** 设备占用查询：返回与可选 UTC 区间重叠的全部明细（含已释放，便于核对释放结果）。 */
    public List<ItemRow> findItemsByDevice(String deviceId, LocalDateTime fromUtc,
                                           LocalDateTime toUtc) {
        return jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id, level,
                       fraction_no, start_utc, end_utc, status,
                       delivered_dose_cgy, executed_at_utc, cancelled_at
                FROM schedule_item
                WHERE device_id = ?
                  AND (? IS NULL OR end_utc > ?)
                  AND (? IS NULL OR start_utc < ?)
                ORDER BY start_utc, id
                """, new ItemRowMapper(), deviceId, fromUtc, fromUtc, toUtc, toUtc);
    }

    // ============================ 行映射 ============================

    private static final class DeviceRowMapper implements RowMapper<DeviceRow> {
        @Override
        public DeviceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DeviceRow(
                    rs.getString("device_id"),
                    rs.getString("device_name"),
                    rs.getInt("max_level"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    }

    private static final class BatchRowMapper implements RowMapper<BatchRow> {
        @Override
        public BatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BatchRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getString("device_id"),
                    rs.getInt("level"),
                    rs.getInt("required_minutes"),
                    rs.getInt("fraction_count"),
                    rs.getString("status"),
                    rs.getInt("version"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime(),
                    toLdt(rs, "cancelled_at"));
        }
    }

    private static final class ItemRowMapper implements RowMapper<ItemRow> {
        @Override
        public ItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long dose = (Long) rs.getObject("delivered_dose_cgy");
            return new ItemRow(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getString("device_id"),
                    rs.getInt("level"),
                    rs.getInt("fraction_no"),
                    rs.getTimestamp("start_utc").toLocalDateTime(),
                    rs.getTimestamp("end_utc").toLocalDateTime(),
                    rs.getString("status"),
                    dose,
                    toLdt(rs, "executed_at_utc"),
                    toLdt(rs, "cancelled_at"));
        }
    }

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }
}
