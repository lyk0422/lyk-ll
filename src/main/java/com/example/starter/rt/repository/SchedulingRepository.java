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
 * 设备与排程仓储：设备登记/能力版本、连续分次批量排程批次与占用项。
 * 占用窗口一律按 UTC 存储（半开区间 [start, end)）。
 */
@Repository
public class SchedulingRepository {

    private final JdbcTemplate jdbc;

    public SchedulingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 设备行。 */
    public record DeviceRow(
            String deviceId,
            String deviceName,
            int maxComplexityLevel,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 排程批次行。 */
    public record ScheduleBatchRow(
            Long id,
            String patientId,
            String planCode,
            Long planVersionId,
            int complexityLevel,
            String deviceId,
            String status,
            int version,
            String requestId,
            LocalDateTime createdAt,
            LocalDateTime cancelledAt) {
    }

    /** 排程占用项行（窗口为 UTC）。 */
    public record ScheduleItemRow(
            Long id,
            Long batchId,
            String patientId,
            String planCode,
            Long planVersionId,
            String deviceId,
            int complexityLevel,
            int fractionNo,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            String status,
            LocalDateTime executedAtUtc,
            Long deliveredDoseCgy,
            String treatmentRequestId,
            String cancelReason) {
    }

    // ============================ 设备 ============================

    /** 登记设备，版本从 1 开始；同 ID 由主键约束抛出 DuplicateKeyException。 */
    public void insertDevice(String deviceId, String deviceName, int maxComplexityLevel,
                             LocalDateTime now) {
        jdbc.update("""
                INSERT INTO device (device_id, device_name, max_complexity_level, version,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """, deviceId, deviceName, maxComplexityLevel, now, now);
    }

    public Optional<DeviceRow> findDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, max_complexity_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /** 锁定设备行（SELECT ... FOR UPDATE），串行化同设备能力调整与排程发布。 */
    public Optional<DeviceRow> lockDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, max_complexity_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                FOR UPDATE
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /**
     * 按期望版本更新设备能力上限（乐观并发），成功时版本号 +1。
     *
     * @return 更新行数（0 表示版本不匹配）
     */
    public int updateCapability(String deviceId, int newLevel, int expectedVersion,
                                LocalDateTime now) {
        return jdbc.update("""
                UPDATE device
                SET max_complexity_level = ?, version = version + 1, updated_at = ?
                WHERE device_id = ? AND version = ?
                """, newLevel, now, deviceId, expectedVersion);
    }

    // ============================ 排程批次 ============================

    /** 插入排程批次，版本从 1 开始，返回批次ID。 */
    public long insertBatch(String patientId, String planCode, long planVersionId, int level,
                            String deviceId, String requestId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO schedule_batch
                    (patient_id, plan_code, plan_version_id, complexity_level, device_id,
                     status, version, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """, patientId, planCode, planVersionId, level, deviceId, requestId, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM schedule_batch WHERE request_id = ?", Long.class, requestId);
        return id == null ? 0L : id;
    }

    public Optional<ScheduleBatchRow> findBatch(long batchId) {
        List<ScheduleBatchRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, complexity_level, device_id,
                       status, version, request_id, created_at, cancelled_at
                FROM schedule_batch WHERE id = ?
                """, new BatchRowMapper(), batchId);
        return rows.stream().findFirst();
    }

    /** 锁定排程批次行（SELECT ... FOR UPDATE）。 */
    public Optional<ScheduleBatchRow> lockBatch(long batchId) {
        List<ScheduleBatchRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, complexity_level, device_id,
                       status, version, request_id, created_at, cancelled_at
                FROM schedule_batch WHERE id = ?
                FOR UPDATE
                """, new BatchRowMapper(), batchId);
        return rows.stream().findFirst();
    }

    /** 按期望版本取消批次（乐观并发），成功时版本号 +1。 */
    public int cancelBatch(long batchId, int expectedVersion, LocalDateTime now) {
        return jdbc.update("""
                UPDATE schedule_batch
                SET status = 'CANCELLED', version = version + 1, cancelled_at = ?
                WHERE id = ? AND version = ? AND status = 'ACTIVE'
                """, now, batchId, expectedVersion);
    }

    /** 批次内全部项已完成时把批次置为 COMPLETED 并版本号 +1。 */
    public int completeBatchIfDone(long batchId) {
        return jdbc.update("""
                UPDATE schedule_batch
                SET status = 'COMPLETED', version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM schedule_item
                                  WHERE batch_id = ? AND status = 'SCHEDULED')
                  AND NOT EXISTS (SELECT 1 FROM schedule_item
                                  WHERE batch_id = ? AND status = 'CANCELLED')
                """, batchId, batchId, batchId);
    }

    /**
     * 计划改版同事务调用：把患者+计划已无未开始项且含取消项的 ACTIVE 批次置为 CANCELLED 并版本号 +1。
     */
    public int cancelEmptyActiveBatches(String patientId, String planCode, LocalDateTime now) {
        return jdbc.update("""
                UPDATE schedule_batch
                SET status = 'CANCELLED', version = version + 1, cancelled_at = ?
                WHERE patient_id = ? AND plan_code = ? AND status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM schedule_item
                                  WHERE batch_id = schedule_batch.id AND status = 'SCHEDULED')
                  AND EXISTS (SELECT 1 FROM schedule_item
                              WHERE batch_id = schedule_batch.id AND status = 'CANCELLED')
                """, now, patientId, planCode);
    }

    // ============================ 排程占用项 ============================

    /** 插入占用项（初始 SCHEDULED，即占用设备窗口）。 */
    public void insertItem(long batchId, String patientId, String planCode, long planVersionId,
                           String deviceId, int level, int fractionNo,
                           LocalDateTime windowStartUtc, LocalDateTime windowEndUtc) {
        jdbc.update("""
                INSERT INTO schedule_item
                    (batch_id, patient_id, plan_code, plan_version_id, device_id,
                     complexity_level, fraction_no, window_start_utc, window_end_utc, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                """, batchId, patientId, planCode, planVersionId, deviceId, level, fractionNo,
                windowStartUtc, windowEndUtc);
    }

    public List<ScheduleItemRow> findItemsByBatch(long batchId) {
        return jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id,
                       complexity_level, fraction_no, window_start_utc, window_end_utc, status,
                       executed_at_utc, delivered_dose_cgy, treatment_request_id, cancel_reason
                FROM schedule_item WHERE batch_id = ?
                ORDER BY fraction_no
                """, new ItemRowMapper(), batchId);
    }

    /** 按批次+分次号锁定占用项（SELECT ... FOR UPDATE）。 */
    public Optional<ScheduleItemRow> lockItem(long batchId, int fractionNo) {
        List<ScheduleItemRow> rows = jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id,
                       complexity_level, fraction_no, window_start_utc, window_end_utc, status,
                       executed_at_utc, delivered_dose_cgy, treatment_request_id, cancel_reason
                FROM schedule_item WHERE batch_id = ? AND fraction_no = ?
                FOR UPDATE
                """, new ItemRowMapper(), batchId, fractionNo);
        return rows.stream().findFirst();
    }

    /** 同设备在 SCHEDULED 状态下与新窗口 [startUtc, endUtc) 重叠（半开区间）的占用项数。 */
    public int countDeviceOverlap(String deviceId, LocalDateTime startUtc, LocalDateTime endUtc) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM schedule_item
                WHERE device_id = ? AND status = 'SCHEDULED'
                  AND window_start_utc < ? AND window_end_utc > ?
                """, Integer.class, deviceId, endUtc, startUtc);
        return count == null ? 0 : count;
    }

    /** 同患者+计划+分次号处于 SCHEDULED 状态的占用项数（同分次双占检测）。 */
    public int countActiveFractionOccupancy(String patientId, String planCode, int fractionNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM schedule_item
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ? AND status = 'SCHEDULED'
                """, Integer.class, patientId, planCode, fractionNo);
        return count == null ? 0 : count;
    }

    /** 设备在指定 UTC 范围内的全部占用项（含各状态，按窗口起点升序），用于占用查询。 */
    public List<ScheduleItemRow> findDeviceItems(String deviceId, LocalDateTime fromUtc,
                                                 LocalDateTime toUtc) {
        return jdbc.query("""
                SELECT id, batch_id, patient_id, plan_code, plan_version_id, device_id,
                       complexity_level, fraction_no, window_start_utc, window_end_utc, status,
                       executed_at_utc, delivered_dose_cgy, treatment_request_id, cancel_reason
                FROM schedule_item
                WHERE device_id = ?
                  AND window_start_utc < ? AND window_end_utc > ?
                ORDER BY window_start_utc, id
                """, new ItemRowMapper(), deviceId, toUtc, fromUtc);
    }

    /** 完成占用项：原子释放占用并冻结实绩（执行时刻 UTC、投递剂量、治疗 requestId）。 */
    public int completeItem(long itemId, LocalDateTime executedAtUtc, long deliveredDoseCgy,
                            String treatmentRequestId) {
        return jdbc.update("""
                UPDATE schedule_item
                SET status = 'COMPLETED', executed_at_utc = ?, delivered_dose_cgy = ?,
                    treatment_request_id = ?
                WHERE id = ? AND status = 'SCHEDULED'
                """, executedAtUtc, deliveredDoseCgy, treatmentRequestId, itemId);
    }

    /** 取消批次内全部未开始项（人工取消），返回取消条数。 */
    public int cancelScheduledItems(long batchId, String reason) {
        return jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', cancel_reason = ?
                WHERE batch_id = ? AND status = 'SCHEDULED'
                """, reason, batchId);
    }

    /** 计划改版同事务调用：取消患者+计划全部未开始项并释放占用，返回取消条数。 */
    public int cancelScheduledItemsForPlan(String patientId, String planCode, String reason) {
        return jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', cancel_reason = ?
                WHERE patient_id = ? AND plan_code = ? AND status = 'SCHEDULED'
                """, reason, patientId, planCode);
    }

    // ============================ 行映射 ============================

    private static final class DeviceRowMapper implements RowMapper<DeviceRow> {
        @Override
        public DeviceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DeviceRow(
                    rs.getString("device_id"),
                    rs.getString("device_name"),
                    rs.getInt("max_complexity_level"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    }

    private static final class BatchRowMapper implements RowMapper<ScheduleBatchRow> {
        @Override
        public ScheduleBatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ScheduleBatchRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getInt("complexity_level"),
                    rs.getString("device_id"),
                    rs.getString("status"),
                    rs.getInt("version"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("cancelled_at") == null
                            ? null : rs.getTimestamp("cancelled_at").toLocalDateTime());
        }
    }

    private static final class ItemRowMapper implements RowMapper<ScheduleItemRow> {
        @Override
        public ScheduleItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long dose = (Long) rs.getObject("delivered_dose_cgy");
            return new ScheduleItemRow(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getString("device_id"),
                    rs.getInt("complexity_level"),
                    rs.getInt("fraction_no"),
                    rs.getTimestamp("window_start_utc").toLocalDateTime(),
                    rs.getTimestamp("window_end_utc").toLocalDateTime(),
                    rs.getString("status"),
                    rs.getTimestamp("executed_at_utc") == null
                            ? null : rs.getTimestamp("executed_at_utc").toLocalDateTime(),
                    dose,
                    rs.getString("treatment_request_id"),
                    rs.getString("cancel_reason"));
        }
    }
}
