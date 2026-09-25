package com.example.starter.repo;

import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 制动试验数据访问。
 */
@Repository
public class BrakeTestRepository {

    private static final RowMapper<BrakeTestRow> ROW_MAPPER = (rs, n) -> new BrakeTestRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getInt("consist_version"),
            TestType.valueOf(rs.getString("test_type")),
            rs.getInt("pressure_reduction"),
            rs.getInt("pipe_pressure_before"),
            TestResult.valueOf(rs.getString("result")),
            rs.getBoolean("voided"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public BrakeTestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录试验结论。
     */
    public long insert(long jobId, int consistVersion, TestType testType, int pressureReduction,
                       int pipePressureBefore, Boolean allCarsBraked, Boolean emergencyOccurred,
                       TestResult result, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO brake_test (job_id, consist_version, test_type, pressure_reduction,"
                            + " pipe_pressure_before, all_cars_braked, emergency_occurred, result, voided,"
                            + " request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setInt(2, consistVersion);
            ps.setString(3, testType.name());
            ps.setInt(4, pressureReduction);
            ps.setInt(5, pipePressureBefore);
            if (allCarsBraked == null) {
                ps.setNull(6, java.sql.Types.BOOLEAN);
            } else {
                ps.setBoolean(6, allCarsBraked);
            }
            if (emergencyOccurred == null) {
                ps.setNull(7, java.sql.Types.BOOLEAN);
            } else {
                ps.setBoolean(7, emergencyOccurred);
            }
            ps.setString(8, result.name());
            ps.setString(9, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 作废作业全部未作废试验结论（编组变更同事务调用）；返回作废条数。
     */
    public int voidAllByJobId(long jobId) {
        return jdbc.update("UPDATE brake_test SET voided = TRUE WHERE job_id = ? AND voided = FALSE", jobId);
    }

    /**
     * 查询当前编组版本下指定类型的最新有效试验结论。
     */
    public Optional<BrakeTestRow> findLatestActive(long jobId, int consistVersion, TestType testType) {
        return jdbc.query("SELECT * FROM brake_test WHERE job_id = ? AND consist_version = ?"
                        + " AND test_type = ? AND voided = FALSE ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, jobId, consistVersion, testType.name()).stream().findFirst();
    }

    /**
     * 查询作业全部试验记录（试验曲线复算用）。
     */
    public List<BrakeTestRow> findByJobId(long jobId) {
        return jdbc.query("SELECT * FROM brake_test WHERE job_id = ? ORDER BY id", ROW_MAPPER, jobId);
    }
}
