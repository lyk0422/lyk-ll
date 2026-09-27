package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 列尾装置数据访问；装置版本自1起，所有成功变更使版本加1。
 */
@Repository
public class TailDeviceRepository {

    private static final RowMapper<TailDeviceRow> ROW_MAPPER = (rs, n) -> new TailDeviceRow(
            rs.getLong("id"),
            rs.getString("device_no"),
            rs.getTimestamp("self_test_valid_until").toLocalDateTime().toInstant(ZoneOffset.UTC),
            rs.getObject("current_job_id") == null ? null : rs.getLong("current_job_id"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public TailDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建档新装置，初始版本为1。
     */
    public void insert(String deviceNo, Instant selfTestValidUntil) {
        jdbc.update("INSERT INTO tail_device (device_no, self_test_valid_until, current_job_id, version,"
                        + " created_at, updated_at) VALUES (?, ?, NULL, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                deviceNo, Timestamp.valueOf(LocalDateTime.ofInstant(selfTestValidUntil, ZoneOffset.UTC)));
    }

    /**
     * 按编号查询装置。
     */
    public Optional<TailDeviceRow> findByDeviceNo(String deviceNo) {
        return jdbc.query("SELECT * FROM tail_device WHERE device_no = ?", ROW_MAPPER, deviceNo)
                .stream().findFirst();
    }

    /**
     * 按主键查询装置。
     */
    public Optional<TailDeviceRow> findById(long id) {
        return jdbc.query("SELECT * FROM tail_device WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 自检更新：仅当期望版本匹配时生效，版本加1；返回是否命中。
     */
    public boolean updateSelfTest(String deviceNo, int expectedVersion, Instant selfTestValidUntil) {
        return jdbc.update("UPDATE tail_device SET self_test_valid_until = ?, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE device_no = ? AND version = ?",
                Timestamp.valueOf(LocalDateTime.ofInstant(selfTestValidUntil, ZoneOffset.UTC)),
                deviceNo, expectedVersion) > 0;
    }

    /**
     * 占用装置（绑定）：仅当期望版本匹配且装置空闲时生效，版本加1；返回是否命中。
     * 并发绑定时行锁串行化，仅一个事务命中。
     */
    public boolean occupy(String deviceNo, int expectedVersion, long jobId) {
        return jdbc.update("UPDATE tail_device SET current_job_id = ?, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE device_no = ? AND version = ? AND current_job_id IS NULL",
                jobId, deviceNo, expectedVersion) > 0;
    }

    /**
     * 释放装置（解绑/销记）：仅当期望版本匹配且装置正绑定该作业时生效，版本加1；返回是否命中。
     */
    public boolean release(String deviceNo, int expectedVersion, long jobId) {
        return jdbc.update("UPDATE tail_device SET current_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE device_no = ? AND version = ? AND current_job_id = ?",
                deviceNo, expectedVersion, jobId) > 0;
    }

    /**
     * 销记时系统内部释放装置（无期望版本，按当前绑定作业匹配），版本加1；返回是否命中。
     */
    public boolean releaseOnClosure(long deviceId, long jobId) {
        return jdbc.update("UPDATE tail_device SET current_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND current_job_id = ?",
                deviceId, jobId) > 0;
    }
}
