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
 * 排程门禁仓储：合成设备、排程批次与排程占用明细。
 * 时间窗口窗口列（window_start_utc/window_end_utc/executed_at）为 UTC，其余时间列为 Asia/Shanghai。
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
            int maxLevel,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 排程批次行。 */
    public record ScheduleRow(
            Long id,
            String patientId,
            String planCode,
            long planVersionId,
            int level,
            String deviceId,
            int version,
            String status,
            String requestId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 排程占用明细行（窗口为 UTC）。 */
    public record ScheduleItemRow(
            Long id,
            long scheduleId,
            String patientId,
            String planCode,
            long planVersionId,
            int fractionNo,
            String deviceId,
            int level,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            String status,
            Long deliveredDoseCgy,
            LocalDateTime executedAt,
            Long fractionExecutionId) {
    }

    // ---------------- 设备 ----------------

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

    /** 锁定设备行（SELECT ... FOR UPDATE），串行化能力变更与发布/执行的能力校验。 */
    public Optional<DeviceRow> lockDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, max_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                FOR UPDATE
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /** 能力变更成功：等级上限更新且版本加 1。 */
    public void updateDeviceLevel(String deviceId, int maxLevel, LocalDateTime now) {
        jdbc.update("""
                UPDATE device SET max_level = ?, version = version + 1, updated_at = ?
                WHERE device_id = ?
                """, maxLevel, now, deviceId);
    }

    // ---------------- 排程批次 ----------------

    public long insertSchedule(String patientId, String planCode, long planVersionId, int level,
                               String deviceId, String requestId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO plan_schedule
                    (patient_id, plan_code, plan_version_id, level, device_id,
                     version, status, request_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?, ?)
                """, patientId, planCode, planVersionId, level, deviceId, requestId, now, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM plan_schedule WHERE request_id = ?", Long.class, requestId);
        return id == null ? 0L : id;
    }

    public Optional<ScheduleRow> findSchedule(long scheduleId) {
        List<ScheduleRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, level, device_id,
                       version, status, request_id, created_at, updated_at
                FROM plan_schedule WHERE id = ?
                """, new ScheduleRowMapper(), scheduleId);
        return rows.stream().findFirst();
    }

    /** 锁定排程批次行（SELECT ... FOR UPDATE）。 */
    public Optional<ScheduleRow> lockSchedule(long scheduleId) {
        List<ScheduleRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, level, device_id,
                       version, status, request_id, created_at, updated_at
                FROM plan_schedule WHERE id = ?
                FOR UPDATE
                """, new ScheduleRowMapper(), scheduleId);
        return rows.stream().findFirst();
    }

    public List<ScheduleRow> findSchedules(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, level, device_id,
                       version, status, request_id, created_at, updated_at
                FROM plan_schedule
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY id
                """, new ScheduleRowMapper(), patientId, planCode);
    }

    /** 取消批次：状态置 CANCELLED 且版本加 1。 */
    public void cancelSchedule(long scheduleId, LocalDateTime now) {
        jdbc.update("""
                UPDATE plan_schedule SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE id = ?
                """, now, scheduleId);
    }

    /** 计划改版：同患者+计划下旧版本（≠保留版本）的生效批次同事务取消并加版本。 */
    public int cancelActiveSchedulesOfOtherVersions(String patientId, String planCode,
                                                    long keepPlanVersionId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE plan_schedule
                SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE patient_id = ? AND plan_code = ? AND plan_version_id <> ? AND status = 'ACTIVE'
                """, now, patientId, planCode, keepPlanVersionId);
    }

    // ---------------- 排程项 ----------------

    public void insertItem(long scheduleId, String patientId, String planCode, long planVersionId,
                           int fractionNo, String deviceId, int level,
                           LocalDateTime windowStartUtc, LocalDateTime windowEndUtc,
                           LocalDateTime now) {
        jdbc.update("""
                INSERT INTO schedule_item
                    (schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                     device_id, level, window_start_utc, window_end_utc, status,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?)
                """, scheduleId, patientId, planCode, planVersionId, fractionNo,
                deviceId, level, windowStartUtc, windowEndUtc, now, now);
    }

    public List<ScheduleItemRow> findItemsBySchedule(long scheduleId) {
        return jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item WHERE schedule_id = ?
                ORDER BY fraction_no
                """, new ScheduleItemRowMapper(), scheduleId);
    }

    /** 患者+计划的全部占用中（SCHEDULED）排程项，用于同分次双占校验。 */
    public List<ScheduleItemRow> findScheduledItems(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item
                WHERE patient_id = ? AND plan_code = ? AND status = 'SCHEDULED'
                ORDER BY fraction_no
                """, new ScheduleItemRowMapper(), patientId, planCode);
    }

    /** 设备当前占用（SCHEDULED）且与给定半开窗口重叠的排程项；端点相接不算重叠。 */
    public List<ScheduleItemRow> findOverlappingItems(String deviceId,
                                                      LocalDateTime windowStartUtc,
                                                      LocalDateTime windowEndUtc) {
        return jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item
                WHERE device_id = ? AND status = 'SCHEDULED'
                  AND window_start_utc < ? AND window_end_utc > ?
                ORDER BY window_start_utc
                """, new ScheduleItemRowMapper(), deviceId, windowEndUtc, windowStartUtc);
    }

    /** 设备当前占用查询（SCHEDULED 项，按窗口开始升序）。 */
    public List<ScheduleItemRow> findDeviceOccupancy(String deviceId) {
        return jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item
                WHERE device_id = ? AND status = 'SCHEDULED'
                ORDER BY window_start_utc
                """, new ScheduleItemRowMapper(), deviceId);
    }

    /** 查找患者+计划+分次号的占用中排程项（受控治疗入口定位用）。 */
    public Optional<ScheduleItemRow> findScheduledItem(String patientId, String planCode,
                                                       int fractionNo) {
        List<ScheduleItemRow> rows = jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ? AND status = 'SCHEDULED'
                """, new ScheduleItemRowMapper(), patientId, planCode, fractionNo);
        return rows.stream().findFirst();
    }

    /** 锁定排程项（SELECT ... FOR UPDATE），串行化取消与执行竞态。 */
    public Optional<ScheduleItemRow> lockItem(long itemId) {
        List<ScheduleItemRow> rows = jdbc.query("""
                SELECT id, schedule_id, patient_id, plan_code, plan_version_id, fraction_no,
                       device_id, level, window_start_utc, window_end_utc, status,
                       delivered_dose_cgy, executed_at, fraction_execution_id
                FROM schedule_item WHERE id = ?
                FOR UPDATE
                """, new ScheduleItemRowMapper(), itemId);
        return rows.stream().findFirst();
    }

    /** 取消批次内全部占用中项，释放资源；已完成项保留实绩不动。 */
    public int cancelScheduledItems(long scheduleId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', updated_at = ?
                WHERE schedule_id = ? AND status = 'SCHEDULED'
                """, now, scheduleId);
    }

    /** 计划改版：同患者+计划下旧版本（≠保留版本）的占用中项同事务取消释放。 */
    public int cancelScheduledItemsOfOtherVersions(String patientId, String planCode,
                                                   long keepPlanVersionId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE schedule_item SET status = 'CANCELLED', updated_at = ?
                WHERE patient_id = ? AND plan_code = ? AND plan_version_id <> ? AND status = 'SCHEDULED'
                """, now, patientId, planCode, keepPlanVersionId);
    }

    /** 受控治疗完成：占用释放并冻结实绩（剂量、执行时刻、台账关联）。 */
    public void completeItem(long itemId, long deliveredDoseCgy, LocalDateTime executedAtUtc,
                             long fractionExecutionId, LocalDateTime now) {
        jdbc.update("""
                UPDATE schedule_item
                SET status = 'COMPLETED', delivered_dose_cgy = ?, executed_at = ?,
                    fraction_execution_id = ?, updated_at = ?
                WHERE id = ? AND status = 'SCHEDULED'
                """, deliveredDoseCgy, executedAtUtc, fractionExecutionId, now, itemId);
    }

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

    private static final class ScheduleRowMapper implements RowMapper<ScheduleRow> {
        @Override
        public ScheduleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ScheduleRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getInt("level"),
                    rs.getString("device_id"),
                    rs.getInt("version"),
                    rs.getString("status"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    }

    private static final class ScheduleItemRowMapper implements RowMapper<ScheduleItemRow> {
        @Override
        public ScheduleItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ScheduleItemRow(
                    rs.getLong("id"),
                    rs.getLong("schedule_id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getInt("fraction_no"),
                    rs.getString("device_id"),
                    rs.getInt("level"),
                    rs.getTimestamp("window_start_utc").toLocalDateTime(),
                    rs.getTimestamp("window_end_utc").toLocalDateTime(),
                    rs.getString("status"),
                    (Long) rs.getObject("delivered_dose_cgy"),
                    rs.getTimestamp("executed_at") == null
                            ? null : rs.getTimestamp("executed_at").toLocalDateTime(),
                    (Long) rs.getObject("fraction_execution_id"));
        }
    }
}
