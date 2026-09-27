package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 列尾装置档案数据访问；bound_job_id 为 null 表示空闲。
 */
@Repository
public class EotDeviceRepository {

    private static final RowMapper<EotDeviceRow> ROW_MAPPER = (rs, n) -> new EotDeviceRow(
            rs.getLong("id"),
            rs.getString("device_no"),
            rs.getTimestamp("self_check_valid_until").toInstant(),
            rs.getObject("bound_job_id", Long.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EotDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 装置建档（合成数据），初始版本为 1、空闲。
     *
     * @throws DuplicateKeyException 编号已存在
     */
    public long insert(String deviceNo, Instant selfCheckValidUntil) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO eot_device (device_no, self_check_valid_until, bound_job_id, version,"
                            + " created_at, updated_at) VALUES (?, ?, NULL, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setString(1, deviceNo);
            ps.setTimestamp(2, Timestamp.from(selfCheckValidUntil));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按装置编号查询。
     */
    public Optional<EotDeviceRow> findByDeviceNo(String deviceNo) {
        return jdbc.query("SELECT * FROM eot_device WHERE device_no = ?", ROW_MAPPER, deviceNo)
                .stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<EotDeviceRow> findById(long id) {
        return jdbc.query("SELECT * FROM eot_device WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 乐观条件占用装置：仅当装置空闲且版本等于期望版本时成功，版本加 1。
     *
     * @return 是否占用成功（false 表示已被占用或版本不匹配）
     */
    public boolean occupyIfFree(long deviceId, long jobId, int expectedVersion) {
        return jdbc.update("UPDATE eot_device SET bound_job_id = ?, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND bound_job_id IS NULL"
                        + " AND version = ?",
                jobId, deviceId, expectedVersion) > 0;
    }

    /**
     * 乐观条件递增版本（一致性确认用）：仅当版本等于期望版本且装置仍被该作业占用时成功。
     *
     * @return 是否成功
     */
    public boolean bumpVersionIfBound(long deviceId, long jobId, int expectedVersion) {
        return jdbc.update("UPDATE eot_device SET version = version + 1, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND bound_job_id = ? AND version = ?",
                deviceId, jobId, expectedVersion) > 0;
    }

    /**
     * 原子解除占用：仅当装置被该作业占用时释放并版本加 1。
     *
     * @return 是否成功释放
     */
    public boolean releaseIfBound(long deviceId, long jobId) {
        return jdbc.update("UPDATE eot_device SET bound_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND bound_job_id = ?",
                deviceId, jobId) > 0;
    }

    /**
     * 带乐观版本条件的原子解除占用：仅当装置被该作业占用且版本等于期望版本时释放并版本加 1。
     *
     * @return 是否成功释放（false 表示占用已变化或版本不匹配）
     */
    public boolean releaseIfBoundAndVersion(long deviceId, long jobId, int expectedVersion) {
        return jdbc.update("UPDATE eot_device SET bound_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND bound_job_id = ? AND version = ?",
                deviceId, jobId, expectedVersion) > 0;
    }
}
