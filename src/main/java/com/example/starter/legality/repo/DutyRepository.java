package com.example.starter.legality.repo;

import com.example.starter.legality.domain.BatchStatus;
import com.example.starter.legality.domain.CheckBatch;
import com.example.starter.legality.domain.DutyRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 值勤合规数据访问层，基于 JdbcTemplate 访问嵌入式 H2（MODE=MySQL），全部使用参数化 SQL。
 * H2 的 DATETIME 不带时区，列内统一存放 UTC 墙钟时间：写入取 UTC 的 LocalDateTime，
 * 读取按 UTC 还原，从而与运行机器的默认时区解耦。
 */
@Repository
public class DutyRepository {

    private final JdbcTemplate jdbcTemplate;

    public DutyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<DutyRecord> DUTY_MAPPER = (rs, rowNum) -> new DutyRecord(
            rs.getString("request_id"),
            rs.getString("batch_id"),
            rs.getString("crew_id"),
            rs.getString("flight_no"),
            toOffset(rs.getTimestamp("report_time")),
            toOffset(rs.getTimestamp("scheduled_end_time")),
            rs.getInt("delay_minutes"),
            rs.getInt("legal") == 1,
            rs.getString("reasons"),
            toOffset(rs.getTimestamp("created_time")));

    /** 插入值勤记录；唯一键冲突时由 Spring 转换为 DuplicateKeyException。 */
    public void insertDuty(DutyRecord record) {
        jdbcTemplate.update("""
                INSERT INTO duty_record
                    (request_id, batch_id, crew_id, flight_no, report_time, scheduled_end_time,
                     delay_minutes, legal, reasons, created_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.requestId(),
                record.batchId(),
                record.crewId(),
                record.flightNo(),
                ts(record.reportTime()),
                ts(record.scheduledEndTime()),
                record.delayMinutes(),
                record.legal() ? 1 : 0,
                record.reasons(),
                ts(record.createdTime()));
    }

    /** 按幂等 requestId 查询值勤记录，不存在返回 null。 */
    public DutyRecord findByRequestId(String requestId) {
        List<DutyRecord> list = jdbcTemplate.query(
                "SELECT * FROM duty_record WHERE request_id = ?", DUTY_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按机组成员+报到时刻查询值勤记录（同一成员同一时段只允许一条），不存在返回 null。 */
    public DutyRecord findBySlot(String crewId, OffsetDateTime reportUtc) {
        List<DutyRecord> list = jdbcTemplate.query(
                "SELECT * FROM duty_record WHERE crew_id = ? AND report_time = ?",
                DUTY_MAPPER, crewId, ts(reportUtc));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询该机组成员报到时刻严格早于给定时刻的既有记录（按报到时刻升序）。 */
    public List<DutyRecord> findHistoryBefore(String crewId, OffsetDateTime reportUtc) {
        return jdbcTemplate.query(
                "SELECT * FROM duty_record WHERE crew_id = ? AND report_time < ? ORDER BY report_time ASC",
                DUTY_MAPPER, crewId, ts(reportUtc));
    }

    /** 查询该机组成员报到时刻严格晚于给定时刻的后续记录（按报到时刻升序），用于延误后的级联重判。 */
    public List<DutyRecord> findDutiesAfter(String crewId, OffsetDateTime reportUtc) {
        return jdbcTemplate.query(
                "SELECT * FROM duty_record WHERE crew_id = ? AND report_time > ? ORDER BY report_time ASC",
                DUTY_MAPPER, crewId, ts(reportUtc));
    }

    /** 统计机组成员在指定 UTC 日历日、排除指定请求后的已受理延误延长分钟合计。 */
    public int sumAppliedDelayOnDate(String crewId, LocalDate utcDate, String excludeRequestId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(delay_minutes), 0) FROM delay_event
                WHERE crew_id = ? AND duty_utc_date = ? AND applied = 1 AND request_id <> ?
                """, Integer.class, crewId, java.sql.Date.valueOf(utcDate), excludeRequestId);
        return total == null ? 0 : total;
    }

    /** 插入延误延长事件。 */
    public void insertDelayEvent(String requestId, String crewId, LocalDate utcDate,
                                 int delayMinutes, boolean applied, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO delay_event
                    (request_id, crew_id, duty_utc_date, delay_minutes, applied, created_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                requestId, crewId, java.sql.Date.valueOf(utcDate), delayMinutes,
                applied ? 1 : 0, ts(createdAt));
    }

    /** 更新值勤记录的累计延误分钟与最新判定。 */
    public void updateDutyVerdict(String requestId, int delayMinutes, boolean legal, String reasons) {
        jdbcTemplate.update(
                "UPDATE duty_record SET delay_minutes = ?, legal = ?, reasons = ? WHERE request_id = ?",
                delayMinutes, legal ? 1 : 0, reasons, requestId);
    }

    /** 按批次ID查询批次，不存在返回 null。 */
    public CheckBatch findBatch(String batchId) {
        List<CheckBatch> list = jdbcTemplate.query(
                "SELECT * FROM check_batch WHERE batch_id = ?", BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 创建批次。 */
    public void insertBatch(CheckBatch batch) {
        jdbcTemplate.update("""
                INSERT INTO check_batch (batch_id, status, legal, created_time, finalized_time)
                VALUES (?, ?, ?, ?, ?)
                """,
                batch.batchId(),
                batch.status().name(),
                batch.legal() ? 1 : 0,
                ts(batch.createdTime()),
                batch.finalizedTime() == null ? null : ts(batch.finalizedTime()));
    }

    /** 更新批次整组判定（仅作用于未封板批次）。 */
    public void updateBatchVerdict(String batchId, boolean legal) {
        jdbcTemplate.update(
                "UPDATE check_batch SET legal = ? WHERE batch_id = ? AND status = 'CHECKED'",
                legal ? 1 : 0, batchId);
    }

    /** 批次封板：写入终态状态、整组判定与封板时刻；已封板时不改动。 */
    public void markFinalized(String batchId, boolean legal, OffsetDateTime finalizedAt) {
        jdbcTemplate.update("""
                UPDATE check_batch
                SET status = 'FINALIZED', legal = ?, finalized_time = ?
                WHERE batch_id = ? AND status = 'CHECKED'
                """, legal ? 1 : 0, ts(finalizedAt), batchId);
    }

    /** 建立批次与值勤记录的关联（幂等）。 */
    public void linkDutyToBatch(String batchId, String requestId) {
        jdbcTemplate.update("""
                MERGE INTO batch_duty (batch_id, request_id) KEY (batch_id, request_id)
                VALUES (?, ?)
                """, batchId, requestId);
    }

    /** 写入/覆盖批次成员聚合判定。 */
    public void upsertBatchMember(String batchId, String crewId, boolean legal, String reasons) {
        jdbcTemplate.update("""
                MERGE INTO batch_member (batch_id, crew_id, legal, reasons)
                KEY (batch_id, crew_id)
                VALUES (?, ?, ?, ?)
                """, batchId, crewId, legal ? 1 : 0, reasons);
    }

    /** 查询批次成员聚合行：object[] = {crewId, legal(Integer), reasons}。 */
    public List<Object[]> findBatchMembers(String batchId) {
        return jdbcTemplate.query(
                "SELECT crew_id, legal, reasons FROM batch_member WHERE batch_id = ? ORDER BY crew_id",
                (rs, rowNum) -> new Object[]{
                        rs.getString("crew_id"),
                        rs.getInt("legal"),
                        rs.getString("reasons")},
                batchId);
    }

    /**
     * 查询批次内各成员值勤记录的聚合判定：object[] = {crewId, legal(Integer 全部段合法才为1), reasons(并集,按报到时刻排序)}。
     */
    public List<Object[]> aggregateBatchDuties(String batchId) {
        return jdbcTemplate.query("""
                SELECT d.crew_id AS crew_id,
                       CASE WHEN SUM(CASE WHEN d.legal = 1 THEN 0 ELSE 1 END) = 0 THEN 1 ELSE 0 END AS legal,
                       LISTAGG(d.reasons, ',') WITHIN GROUP (ORDER BY d.report_time) AS reasons
                FROM batch_duty b
                JOIN duty_record d ON d.request_id = b.request_id
                WHERE b.batch_id = ?
                GROUP BY d.crew_id
                ORDER BY d.crew_id
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("crew_id"),
                        rs.getInt("legal"),
                        rs.getString("reasons") == null ? "" : rs.getString("reasons")},
                batchId);
    }

    /** 将值勤记录归属到批次。 */
    public void attachBatch(String requestId, String batchId) {
        jdbcTemplate.update("UPDATE duty_record SET batch_id = ? WHERE request_id = ?",
                batchId, requestId);
    }

    /** OffsetDateTime（任意偏移）→ 列内 UTC 墙钟 Timestamp，与 JVM 默认时区无关。 */
    private static Timestamp ts(OffsetDateTime time) {
        return Timestamp.valueOf(time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    /** 列内 UTC 墙钟 Timestamp → UTC OffsetDateTime，与 JVM 默认时区无关。 */
    private static OffsetDateTime toOffset(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toLocalDateTime().atOffset(ZoneOffset.UTC);
    }

    private static final RowMapper<CheckBatch> BATCH_MAPPER = (rs, rowNum) -> new CheckBatch(
            rs.getString("batch_id"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getInt("legal") == 1,
            toOffset(rs.getTimestamp("created_time")),
            toOffset(rs.getTimestamp("finalized_time")));
}
