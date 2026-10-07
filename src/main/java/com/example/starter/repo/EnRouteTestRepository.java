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
            rs.getLong("incident_id"),
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
     * 记录途中试验结论（绑定处置单、当前编组版本与有效截止时刻）。
     */
    public long insert(long incidentId, long jobId, int consistVersion, TestType testType,
                       int pressureReduction, int pipePressureBefore,
                       Boolean allCarsBraked, Boolean emergencyOccurred,
                       TestResult result, Instant validUntil, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO en_route_test (incident_id, job_id, consist_version, test_type,"
                            + " pressure_reduction, pipe_pressure_before, all_cars_braked, emergency_occurred,"
                            + " result, voided, valid_until, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, incidentId);
            ps.setLong(2, jobId);
            ps.setInt(3, consistVersion);
            ps.setString(4, testType.name());
            ps.setInt(5, pressureReduction);
            ps.setInt(6, pipePressureBefore);
            if (allCarsBraked == null) {
                ps.setNull(7, java.sql.Types.BOOLEAN);
            } else {
                ps.setBoolean(7, allCarsBraked);
            }
            if (emergencyOccurred == null) {
                ps.setNull(8, java.sql.Types.BOOLEAN);
            } else {
                ps.setBoolean(8, emergencyOccurred);
            }
            ps.setString(9, result.name());
            ps.setTimestamp(10, Timestamp.from(validUntil));
            ps.setString(11, requestId);
            ps.setTimestamp(12, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 作废处置单全部未作废途中试验（阻断中编组变更同事务调用）；返回作废条数。
     */
    public int voidAllByIncidentId(long incidentId) {
        return jdbc.update("UPDATE en_route_test SET voided = TRUE"
                + " WHERE incident_id = ? AND voided = FALSE", incidentId);
    }

    /**
     * 查询处置单当前编组版本下指定类型的最新有效途中试验结论。
     */
    public Optional<EnRouteTestRow> findLatestActive(long incidentId, int consistVersion, TestType testType) {
        return jdbc.query("SELECT * FROM en_route_test WHERE incident_id = ? AND consist_version = ?"
                        + " AND test_type = ? AND voided = FALSE ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, incidentId, consistVersion, testType.name()).stream().findFirst();
    }

    /**
     * 查询处置单全部途中试验记录（证据复算用）。
     */
    public List<EnRouteTestRow> findByIncidentId(long incidentId) {
        return jdbc.query("SELECT * FROM en_route_test WHERE incident_id = ? ORDER BY id",
                ROW_MAPPER, incidentId);
    }
}
