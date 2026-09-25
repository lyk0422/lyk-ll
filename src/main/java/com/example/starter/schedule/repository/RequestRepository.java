package com.example.starter.schedule.repository;

import com.example.starter.schedule.domain.ScheduleRequest;
import com.example.starter.schedule.model.RequestOutcome;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 编排申请幂等记录访问。request_id 主键保证并发同键只插入一条。
 */
@Repository
public class RequestRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public RequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("schedule_request");
    }

    private static final String COLUMNS = """
            request_id, fingerprint, satellite_id, station_id, window_id, priority,
            outcome, result_entry_id, conflict_detail, completed, created_at, updated_at
            """;

    private static final RowMapper<ScheduleRequest> MAPPER = (rs, n) -> {
        ScheduleRequest r = new ScheduleRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setSatelliteId(rs.getLong("satellite_id"));
        r.setStationId(rs.getLong("station_id"));
        r.setWindowId(rs.getLong("window_id"));
        r.setPriority(rs.getInt("priority"));
        r.setOutcome(RequestOutcome.valueOf(rs.getString("outcome")));
        long entry = rs.getLong("result_entry_id");
        r.setResultEntryId(rs.wasNull() ? null : entry);
        r.setConflictDetail(rs.getString("conflict_detail"));
        r.setCompleted(rs.getBoolean("completed"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        r.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return r;
    };

    public Optional<ScheduleRequest> findById(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM schedule_request WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    public Optional<ScheduleRequest> findByIdForUpdate(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + """
                         FROM schedule_request WHERE request_id = ? FOR UPDATE""",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 尝试插入幂等占位（处理中，completed=FALSE）。并发同键时抛 {@link DuplicateKeyException}。
     */
    public void tryInsertProcessing(String requestId, String fingerprint,
                                    long satelliteId, long stationId,
                                    long windowId, int priority)
            throws DuplicateKeyException {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("request_id", requestId);
        params.put("fingerprint", fingerprint);
        params.put("satellite_id", satelliteId);
        params.put("station_id", stationId);
        params.put("window_id", windowId);
        params.put("priority", priority);
        params.put("outcome", RequestOutcome.SCHEDULED.name());
        params.put("result_entry_id", null);
        params.put("conflict_detail", null);
        params.put("completed", false);
        params.put("created_at", new Timestamp(System.currentTimeMillis()));
        params.put("updated_at", new Timestamp(System.currentTimeMillis()));
        insert.execute(params);
    }

    public int completeOutcome(String requestId, RequestOutcome outcome,
                               Long resultEntryId, String conflictDetail) {
        return jdbc.update("""
                        UPDATE schedule_request
                        SET outcome = ?, result_entry_id = ?, conflict_detail = ?,
                            completed = TRUE, updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ?""",
                outcome.name(), resultEntryId, conflictDetail, requestId);
    }
}
