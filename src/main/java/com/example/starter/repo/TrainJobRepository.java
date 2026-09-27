package com.example.starter.repo;

import com.example.starter.domain.JobStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 列车作业数据访问。
 */
@Repository
public class TrainJobRepository {

    private static final RowMapper<JobRow> ROW_MAPPER = (rs, n) -> new JobRow(
            rs.getLong("id"),
            rs.getString("train_no"),
            rs.getString("track"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("consist_version"),
            rs.getInt("car_count"),
            rs.getBigDecimal("total_length"),
            rs.getBigDecimal("total_weight"),
            rs.getBoolean("consist_checked"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TrainJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新作业，占用股道（active_track 唯一约束保证同股道同时刻仅一列作业中列车）。
     */
    public long insert(String trainNo, String track, int carCount,
                       BigDecimal totalLength, BigDecimal totalWeight) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO train_job (train_no, track, active_track, status, consist_version,"
                            + " car_count, total_length, total_weight, consist_checked, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'ACCEPTED', 1, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setString(1, trainNo);
            ps.setString(2, track);
            ps.setString(3, track);
            ps.setInt(4, carCount);
            ps.setBigDecimal(5, totalLength);
            ps.setBigDecimal(6, totalWeight);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询作业。
     */
    public Optional<JobRow> findById(long id) {
        return jdbc.query("SELECT * FROM train_job WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键查询作业并加行锁（事务内串行化摘挂/列尾确认/发车/销记竞争）。
     */
    public Optional<JobRow> lockById(long id) {
        return jdbc.query("SELECT * FROM train_job WHERE id = ? FOR UPDATE", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 更新编组统计与版本；consistChecked 为当前版本是否已通过运统1核对。
     */
    public void updateConsist(long id, int consistVersion, int carCount,
                              BigDecimal totalLength, BigDecimal totalWeight,
                              boolean consistChecked, JobStatus status) {
        jdbc.update("UPDATE train_job SET consist_version = ?, car_count = ?, total_length = ?,"
                        + " total_weight = ?, consist_checked = ?, status = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?",
                consistVersion, carCount, totalLength, totalWeight, consistChecked,
                status.name(), id);
    }

    /**
     * 更新核对通过与状态。
     */
    public void updateStatusAndChecked(long id, JobStatus status, boolean consistChecked) {
        jdbc.update("UPDATE train_job SET status = ?, consist_checked = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?",
                status.name(), consistChecked, id);
    }

    /**
     * 更新状态。
     */
    public void updateStatus(long id, JobStatus status) {
        jdbc.update("UPDATE train_job SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status.name(), id);
    }

    /**
     * 销记终态：置状态并释放股道占用。
     */
    public void close(long id) {
        jdbc.update("UPDATE train_job SET status = 'CLOSED', active_track = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
