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
 * 合成列尾装置档案数据访问。
 * 装置占用与版本递增全部使用带版本/占用谓词的条件 UPDATE，保证并发绑定唯一成功。
 */
@Repository
public class TailDeviceRepository {

    private static final RowMapper<TailDeviceRow> ROW_MAPPER = (rs, n) -> new TailDeviceRow(
            rs.getLong("id"),
            rs.getString("device_no"),
            rs.getTimestamp("self_check_expires_at").toInstant(),
            rs.getInt("version"),
            (Long) rs.getObject("bound_job_id"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TailDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建档装置，初始版本 1、空闲。
     *
     * @throws DuplicateKeyException 编号已建档
     */
    public long insert(String deviceNo, Instant selfCheckExpiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tail_device (device_no, self_check_expires_at, version, bound_job_id,"
                            + " created_at, updated_at) VALUES (?, ?, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setString(1, deviceNo);
            ps.setTimestamp(2, Timestamp.from(selfCheckExpiresAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按编号查询装置。 */
    public Optional<TailDeviceRow> findByDeviceNo(String deviceNo) {
        return jdbc.query("SELECT * FROM tail_device WHERE device_no = ?", ROW_MAPPER, deviceNo)
                .stream().findFirst();
    }

    /** 按主键查询装置。 */
    public Optional<TailDeviceRow> findById(long id) {
        return jdbc.query("SELECT * FROM tail_device WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键查询装置并加行锁（事务内串行化占用/释放/确认竞争）。 */
    public Optional<TailDeviceRow> lockById(long id) {
        return jdbc.query("SELECT * FROM tail_device WHERE id = ? FOR UPDATE", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 原子占用装置：仅当期望版本匹配且当前空闲时绑定作业并版本加 1。
     * 并发绑定同一装置时仅一条条件 UPDATE 命中。
     *
     * @return 是否占用成功
     */
    public boolean occupy(long id, int expectedVersion, long jobId) {
        return jdbc.update("UPDATE tail_device SET bound_job_id = ?, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ? AND bound_job_id IS NULL",
                jobId, id, expectedVersion) > 0;
    }

    /**
     * 原子释放装置占用（销记或发车前解绑）：仅当期望版本匹配且确为该作业占有时置空闲并版本加 1。
     *
     * @return 是否释放成功
     */
    public boolean release(long id, int expectedVersion, long jobId) {
        return jdbc.update("UPDATE tail_device SET bound_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ? AND bound_job_id = ?",
                id, expectedVersion, jobId) > 0;
    }

    /**
     * 版本递增（一致性确认成功）：仅当期望版本匹配时加 1。
     *
     * @return 是否命中
     */
    public boolean bumpVersion(long id, int expectedVersion) {
        return jdbc.update("UPDATE tail_device SET version = version + 1, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND version = ?", id, expectedVersion) > 0;
    }

    /**
     * 销记原子释放：装置确为该作业占有时置空闲并版本加 1（内部流程，无 expectedVersion）。
     *
     * @return 是否命中（0 表示该作业未占用装置）
     */
    public boolean releaseForClosure(long id, long jobId) {
        return jdbc.update("UPDATE tail_device SET bound_job_id = NULL, version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND bound_job_id = ?",
                id, jobId) > 0;
    }
}
