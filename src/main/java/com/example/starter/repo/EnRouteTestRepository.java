package com.example.starter.repo;

import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 途中制动试验数据访问。
 */
@Repository
public class EnRouteTestRepository {

    private static final RowMapper<EnRouteTestRow> ROW_MAPPER = (rs, n) -> new EnRouteTestRow(
            rs.getLong("id"),
            rs.getLong("disposition_id"),
            rs.getLong("job_id"),
            rs.getInt("consist_version"),
            TestType.valueOf(rs.getString("test_type")),
            rs.getInt("pressure_reduction"),
            rs.getInt("pipe_pressure_before"),
            TestResult.valueOf(rs.getString("result")),
            rs.getBoolean("voided"),
            rs.getTimestamp("valid_until").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EnRouteTestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录途中试验结论（绑定处置单、当前编组版本与新压力曲线有效截止时刻）。
     */
    public long insert(long dispositionId, long jobId, int consistVersion, TestType testType,
                       int pressureReduction, int pipePressureBefore, Boolean allCarsBraked,
                       Boolean emergencyOccurred, TestResult result, Instant validUntil, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO en_route_test (disposition_id, job_id, consist_version, test_type,"
                            + " pressure_reduction, pipe_pressure_before, all_cars_braked, emergency_occurred,"
                            + " result, voided, valid_until, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, dispositionId);
            ps.setLong(2, jobId);
            ps.setInt(3, consistVersion);
            ps.setString(4, testType.name());
            ps.setInt(5, pressureReduction);
            ps.setInt(6, pipePressureBefore);
            if (allCarsBraked == null) {
                ps.setNull(7, Types.BOOLEAN);
            } else {
                ps.setBoolean(7, allCarsBraked);
            }
            if (emergencyOccurred == null) {
                ps.setNull(8, Types.BOOLEAN);
            } else {
                ps.setBoolean(8, emergencyOccurred);
            }
            ps.setString(9, result.name());
            ps.setTimestamp(10, Timestamp.from(validUntil));
            ps.setString(11, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 失效处置单下全部未失效途中试验（途中编组变更同事务调用）；返回失效条数。
     */
    public int voidAllByDispositionId(long dispositionId) {
        return jdbc.update("UPDATE en_route_test SET voided = TRUE"
                + " WHERE disposition_id = ? AND voided = FALSE", dispositionId);
    }

    /**
     * 查询处置单当前编组版本下指定类型的最新有效途中试验。
     */
    public Optional<EnRouteTestRow> findLatestActive(long dispositionId, int consistVersion, TestType testType) {
        return jdbc.query("SELECT * FROM en_route_test WHERE disposition_id = ? AND consist_version = ?"
                        + " AND test_type = ? AND voided = FALSE ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, dispositionId, consistVersion, testType.name()).stream().findFirst();
    }

    /**
     * 查询处置单全部途中试验记录（证据复算用，按时间正序）。
     */
    public List<EnRouteTestRow> findByDispositionId(long dispositionId) {
        return jdbc.query("SELECT * FROM en_route_test WHERE disposition_id = ? ORDER BY id",
                ROW_MAPPER, dispositionId);
    }
}
