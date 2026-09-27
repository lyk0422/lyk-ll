package com.example.starter.rt.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 分次设备时长排程仓储：合成设备登记/能力下调、排程发布占用/取消释放/受控执行释放、
 * 活跃分次占用守卫与占用查询。
 *
 * <p>设备窗口列以 UTC 本地时刻（{@link LocalDateTime}）存储，语义为 UTC；
 * 业务时间列（published_at 等）沿用 Asia/Shanghai 本地时刻。</p>
 */
@Repository
public class SchedulingRepository {

    private final JdbcTemplate jdbc;

    public SchedulingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 合成设备行。 */
    public record DeviceRow(
            String deviceId,
            String deviceName,
            int capabilityLevel,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 分次排程行（含历史终态）。 */
    public record ScheduleRow(
            Long id,
            String patientId,
            String planCode,
            Long planVersionId,
            int versionNo,
            int fractionNo,
            String deviceId,
            int complexityLevel,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            String status,
            int version,
            String publishRequestId,
            String cancelRequestId,
            Long executedFractionId,
            LocalDateTime publishedAt,
            LocalDateTime cancelledAt,
            LocalDateTime executedAt) {
    }

    // ============================ 设备 ============================

    /** 登记合成设备，初始版本为1；设备ID重复由唯一约束拒绝。 */
    public int insertDevice(String deviceId, String deviceName, int capabilityLevel, LocalDateTime now) {
        return jdbc.update("""
                INSERT INTO device (device_id, device_name, capability_level, version, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """, deviceId, deviceName, capabilityLevel, now, now);
    }

    public Optional<DeviceRow> findDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, capability_level, version, created_at, updated_at
                FROM device WHERE device_id = ?
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    /** 按主键锁定设备行（SELECT ... FOR UPDATE），串行化同设备的发布与能力下调。 */
    public Optional<DeviceRow> lockDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query("""
                SELECT device_id, device_name, capability_level, version, created_at, updated_at
                FROM device WHERE device_id = ? FOR UPDATE
                """, new DeviceRowMapper(), deviceId);
        return rows.stream().findFirst();
    }

    public List<DeviceRow> findAllDevices() {
        return jdbc.query("""
                SELECT device_id, device_name, capability_level, version, created_at, updated_at
                FROM device ORDER BY device_id
                """, new DeviceRowMapper());
    }

    /**
     * 能力下调（条件更新）：仅当版本与 expectedVersion 一致且新等级低于当前等级时生效。
     *
     * @return 受影响行数；0 表示版本冲突或非下调
     */
    public int downgradeDevice(String deviceId, int expectedVersion, int newCapabilityLevel,
                               LocalDateTime now) {
        return jdbc.update("""
                UPDATE device
                SET capability_level = ?, version = version + 1, updated_at = ?
                WHERE device_id = ? AND version = ? AND capability_level > ?
                """, newCapabilityLevel, now, deviceId, expectedVersion, newCapabilityLevel);
    }

    // ============================ 排程发布 ============================

    /** 插入一条 BOOKED 排程（version=1），并按 (publish_request_id, fraction_no) 唯一约束取回主键。 */
    public long insertSchedule(String patientId, String planCode, long planVersionId, int versionNo,
                               int fractionNo, String deviceId, int complexityLevel,
                               LocalDateTime windowStartUtc, LocalDateTime windowEndUtc,
                               String publishRequestId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO fraction_schedule
                    (patient_id, plan_code, plan_version_id, version_no, fraction_no, device_id,
                     complexity_level, window_start_utc, window_end_utc, status, version,
                     publish_request_id, cancel_request_id, executed_fraction_id,
                     published_at, cancelled_at, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOOKED', 1, ?, NULL, NULL, ?, NULL, NULL)
                """, patientId, planCode, planVersionId, versionNo, fractionNo, deviceId,
                complexityLevel, windowStartUtc, windowEndUtc, publishRequestId, now);
        Long id = jdbc.queryForObject("""
                SELECT id FROM fraction_schedule
                WHERE publish_request_id = ? AND fraction_no = ?
                """, Long.class, publishRequestId, fractionNo);
        return id == null ? 0L : id;
    }

    /** 发布同批幂等重放：按发布 requestId 取回全部排程行，按分次号升序。 */
    public List<ScheduleRow> findByPublishRequestId(String publishRequestId) {
        return jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE publish_request_id = ? ORDER BY fraction_no, id
                """, new ScheduleRowMapper(), publishRequestId);
    }

    public Optional<ScheduleRow> findScheduleById(long scheduleId) {
        List<ScheduleRow> rows = jdbc.query(
                "SELECT * FROM fraction_schedule WHERE id = ?", new ScheduleRowMapper(), scheduleId);
        return rows.stream().findFirst();
    }

    /** 按主键锁定排程行，串行化取消与受控执行竞态。 */
    public Optional<ScheduleRow> lockScheduleById(long scheduleId) {
        List<ScheduleRow> rows = jdbc.query(
                "SELECT * FROM fraction_schedule WHERE id = ? FOR UPDATE",
                new ScheduleRowMapper(), scheduleId);
        return rows.stream().findFirst();
    }

    /** 锁定指定分次的当前 BOOKED 排程；无活跃排程时返回空。 */
    public Optional<ScheduleRow> lockBookedSchedule(String patientId, String planCode, int fractionNo) {
        List<ScheduleRow> rows = jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ? AND status = 'BOOKED'
                FOR UPDATE
                """, new ScheduleRowMapper(), patientId, planCode, fractionNo);
        return rows.stream().findFirst();
    }

    /** 患者+计划的全部分次排程（含历史终态），按分次号、发布先后排序。 */
    public List<ScheduleRow> findSchedulesForPlan(String patientId, String planCode) {
        return jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY fraction_no, id
                """, new ScheduleRowMapper(), patientId, planCode);
    }

    /** 查询给定分次号集合中已有 BOOKED 排程的行（发布前同分次双占检查）。 */
    public List<ScheduleRow> findBookedByFractionNumbers(String patientId, String planCode,
                                                         Collection<Integer> fractionNos) {
        if (fractionNos.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", fractionNos.stream().map(n -> "?").toList());
        return jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE patient_id = ? AND plan_code = ? AND status = 'BOOKED'
                  AND fraction_no IN (%s)
                """.formatted(placeholders), new ScheduleRowMapper(),
                joinArgs(patientId, planCode, fractionNos));
    }

    /**
     * 同设备窗口重叠检查（半开区间）：[start,end) 与已 BOOKED 窗口 [w.start,w.end)
     * 重叠当且仅当 start &lt; w.end 且 end &gt; w.start；端点相接（end=w.start）不重叠。
     */
    public List<ScheduleRow> findBookedOverlappingDevice(String deviceId, LocalDateTime startUtc,
                                                         LocalDateTime endUtc) {
        return jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE device_id = ? AND status = 'BOOKED'
                  AND window_start_utc < ? AND window_end_utc > ?
                ORDER BY window_start_utc, id
                """, new ScheduleRowMapper(), deviceId, endUtc, startUtc);
    }

    /** 占用查询：设备在给定 UTC 区间内重叠的 BOOKED 排程（区间为空时返回该设备全部 BOOKED）。 */
    public List<ScheduleRow> findDeviceOccupancies(String deviceId, LocalDateTime fromUtc,
                                                   LocalDateTime toUtc) {
        if (fromUtc == null || toUtc == null) {
            return jdbc.query("""
                    SELECT * FROM fraction_schedule
                    WHERE device_id = ? AND status = 'BOOKED'
                    ORDER BY window_start_utc, id
                    """, new ScheduleRowMapper(), deviceId);
        }
        return jdbc.query("""
                SELECT * FROM fraction_schedule
                WHERE device_id = ? AND status = 'BOOKED'
                  AND window_start_utc < ? AND window_end_utc > ?
                ORDER BY window_start_utc, id
                """, new ScheduleRowMapper(), deviceId, toUtc, fromUtc);
    }

    /** 该患者+计划是否存在任意排程行（用于判定是否已启用排程门禁）。 */
    public boolean scheduleExistsForPlan(String patientId, String planCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM fraction_schedule
                WHERE patient_id = ? AND plan_code = ?
                """, Integer.class, patientId, planCode);
        return count != null && count > 0;
    }

    // ============================ 取消 / 执行 / 改版释放 ============================

    /** 手动取消：仅 BOOKED 行可迁移，版本加1并冻结取消 requestId；返回受影响行数。 */
    public int markCancelled(long scheduleId, String cancelRequestId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE fraction_schedule
                SET status = 'CANCELLED', version = version + 1, cancel_request_id = ?,
                    cancelled_at = ?
                WHERE id = ? AND status = 'BOOKED'
                """, cancelRequestId, now, scheduleId);
    }

    /** 受控执行：BOOKED→EXECUTED，版本加1并冻结台账分次ID；返回受影响行数。 */
    public int markExecuted(long scheduleId, long fractionId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE fraction_schedule
                SET status = 'EXECUTED', version = version + 1, executed_fraction_id = ?,
                    executed_at = ?
                WHERE id = ? AND status = 'BOOKED'
                """, fractionId, now, scheduleId);
    }

    /**
     * 计划改版同事务调用：把该患者+计划全部 BOOKED 排程置 CANCELLED（系统级取消，
     * cancel_request_id 保留 NULL），版本加1；已执行实绩行（EXECUTED）绝不修改。
     *
     * @return 被释放的排程条数
     */
    public int cancelAllBookedForPlan(String patientId, String planCode, LocalDateTime now) {
        return jdbc.update("""
                UPDATE fraction_schedule
                SET status = 'CANCELLED', version = version + 1, cancelled_at = ?
                WHERE patient_id = ? AND plan_code = ? AND status = 'BOOKED'
                """, now, patientId, planCode);
    }

    // ============================ 活跃分次占用守卫 ============================

    /** 插入活跃占用；同患者+计划+分次已占用时由主键唯一约束抛出 DuplicateKeyException。 */
    public void insertOccupancy(String patientId, String planCode, int fractionNo, long scheduleId,
                                LocalDateTime now) {
        jdbc.update("""
                INSERT INTO fraction_occupancy (patient_id, plan_code, fraction_no, schedule_id, occupied_at)
                VALUES (?, ?, ?, ?, ?)
                """, patientId, planCode, fractionNo, scheduleId, now);
    }

    public void deleteOccupancyBySchedule(long scheduleId) {
        jdbc.update("DELETE FROM fraction_occupancy WHERE schedule_id = ?", scheduleId);
    }

    /** 改版释放：删除该患者+计划的全部活跃分次占用。 */
    public void deleteOccupanciesForPlan(String patientId, String planCode) {
        jdbc.update("""
                DELETE FROM fraction_occupancy
                WHERE patient_id = ? AND plan_code = ?
                """, patientId, planCode);
    }

    public long countOccupancies() {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM fraction_occupancy", Long.class);
        return count == null ? 0L : count;
    }

    // ============================ 行映射与内部工具 ============================

    private Object[] joinArgs(String patientId, String planCode, Collection<Integer> fractionNos) {
        Object[] args = new Object[2 + fractionNos.size()];
        args[0] = patientId;
        args[1] = planCode;
        int i = 2;
        for (Integer no : fractionNos) {
            args[i++] = no;
        }
        return args;
    }

    private static final class DeviceRowMapper implements RowMapper<DeviceRow> {
        @Override
        public DeviceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DeviceRow(
                    rs.getString("device_id"),
                    rs.getString("device_name"),
                    rs.getInt("capability_level"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    }

    private static final class ScheduleRowMapper implements RowMapper<ScheduleRow> {
        @Override
        public ScheduleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long executedFractionId = (Long) rs.getObject("executed_fraction_id");
            String cancelRequestId = rs.getString("cancel_request_id");
            return new ScheduleRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getInt("version_no"),
                    rs.getInt("fraction_no"),
                    rs.getString("device_id"),
                    rs.getInt("complexity_level"),
                    rs.getTimestamp("window_start_utc").toLocalDateTime(),
                    rs.getTimestamp("window_end_utc").toLocalDateTime(),
                    rs.getString("status"),
                    rs.getInt("version"),
                    rs.getString("publish_request_id"),
                    cancelRequestId,
                    executedFractionId,
                    rs.getTimestamp("published_at").toLocalDateTime(),
                    rs.getTimestamp("cancelled_at") == null ? null
                            : rs.getTimestamp("cancelled_at").toLocalDateTime(),
                    rs.getTimestamp("executed_at") == null ? null
                            : rs.getTimestamp("executed_at").toLocalDateTime());
        }
    }
}
