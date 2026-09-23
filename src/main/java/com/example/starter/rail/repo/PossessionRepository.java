package com.example.starter.rail.repo;

import com.example.starter.rail.model.ConflictDetail;
import com.example.starter.rail.model.IdempotencyRecord;
import com.example.starter.rail.model.PlanSnapshot;
import com.example.starter.rail.model.PossessionRequest;
import com.example.starter.rail.model.RequestEvent;
import com.example.starter.rail.model.RequestStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 封锁窗数据访问层。所有时间字段为 UTC epoch 毫秒；SQL 均参数化。
 */
@Repository
public class PossessionRepository {

    private static final RowMapper<PossessionRequest> REQUEST_MAPPER = (rs, n) -> {
        PossessionRequest r = new PossessionRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setStartTime(rs.getLong("start_time"));
        r.setEndTime(rs.getLong("end_time"));
        r.setBufferBeforeMs(rs.getLong("buffer_before_ms"));
        r.setBufferAfterMs(rs.getLong("buffer_after_ms"));
        r.setPriority(rs.getInt("priority"));
        r.setVersion(rs.getInt("version"));
        r.setStatus(RequestStatus.valueOf(rs.getString("status")));
        r.setCreatedAt(rs.getLong("created_at"));
        r.setUpdatedAt(rs.getLong("updated_at"));
        return r;
    };

    private static final RowMapper<ConflictDetail> CONFLICT_MAPPER = (rs, n) -> {
        long otherStart = rs.getLong("start_time") - rs.getLong("buffer_before_ms");
        long otherEnd = rs.getLong("end_time") + rs.getLong("buffer_after_ms");
        ConflictDetail c = new ConflictDetail();
        c.setSectionId(rs.getString("section_id"));
        c.setOtherRequestId(rs.getString("request_id"));
        c.setOtherPriority(rs.getInt("priority"));
        c.setOtherStatus(RequestStatus.valueOf(rs.getString("status")));
        c.setOtherBufferedStart(otherStart);
        c.setOtherBufferedEnd(otherEnd);
        return c;
    };

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PossessionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public void insertRequest(PossessionRequest request) {
        jdbc.sql("""
                INSERT INTO possession_request
                  (request_id, start_time, end_time, buffer_before_ms, buffer_after_ms,
                   priority, version, status, created_at, updated_at)
                VALUES (:requestId, :startTime, :endTime, :bufferBeforeMs, :bufferAfterMs,
                        :priority, 0, 'PENDING', :now, :now)
                """)
                .param("requestId", request.getRequestId())
                .param("startTime", request.getStartTime())
                .param("endTime", request.getEndTime())
                .param("bufferBeforeMs", request.getBufferBeforeMs())
                .param("bufferAfterMs", request.getBufferAfterMs())
                .param("priority", request.getPriority())
                .param("now", request.getCreatedAt())
                .update();
    }

    /**
     * 登记申请涉及的区段（不存在则插入），并写入申请-区段关联。
     */
    public void insertSections(String requestId, List<String> sectionIds, long now) {
        int ordinal = 0;
        for (String sectionId : sectionIds) {
            // H2 MySQL 兼容模式下使用 MERGE 实现存在即跳过的登记。
            jdbc.sql("MERGE INTO section_registry (section_id, created_at) KEY (section_id) VALUES (?, ?)")
                    .param(sectionId)
                    .param(now)
                    .update();
            jdbc.sql("INSERT INTO request_section (request_id, section_id, ordinal) VALUES (?, ?, ?)")
                    .param(requestId)
                    .param(sectionId)
                    .param(ordinal++)
                    .update();
        }
    }

    public PossessionRequest findRequest(String requestId) {
        return jdbc.sql("SELECT * FROM possession_request WHERE request_id = :id")
                .param("id", requestId)
                .query(REQUEST_MAPPER)
                .optional()
                .orElse(null);
    }

    public List<String> findSections(String requestId) {
        return jdbc.sql("SELECT section_id FROM request_section WHERE request_id = :id ORDER BY ordinal")
                .param("id", requestId)
                .query(String.class)
                .list();
    }

    /**
     * 按区段 ID 升序对区段行加写锁（SELECT ... FOR UPDATE）。
     * 必须在事务内调用：共用区段的审批因此串行，无共同区段的申请互不阻塞。
     */
    public void lockSections(List<String> sectionIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("ids", sectionIds);
        namedJdbc.queryForList(
                "SELECT section_id FROM section_registry WHERE section_id IN (:ids) ORDER BY section_id FOR UPDATE",
                params, String.class);
    }

    /**
     * 查询与给定缓冲后占用窗（半开）正长度重叠的已批准/已激活窗口，按区段逐行返回。
     * 端点相接（otherStart == bufferedEnd 或 otherEnd == bufferedStart）不构成重叠。
     */
    public List<ConflictDetail> findConflicts(List<String> sectionIds, long bufferedStart,
                                              long bufferedEnd, String excludeRequestId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", sectionIds)
                .addValue("bufferedStart", bufferedStart)
                .addValue("bufferedEnd", bufferedEnd)
                .addValue("exclude", excludeRequestId);
        return namedJdbc.query("""
                SELECT s.section_id, r.request_id, r.priority, r.status,
                       r.start_time, r.buffer_before_ms, r.end_time, r.buffer_after_ms
                FROM request_section s
                JOIN possession_request r ON r.request_id = s.request_id
                WHERE s.section_id IN (:ids)
                  AND r.status IN ('APPROVED', 'ACTIVE')
                  AND r.request_id <> :exclude
                  AND (r.start_time - r.buffer_before_ms) < :bufferedEnd
                  AND (r.end_time + r.buffer_after_ms) > :bufferedStart
                ORDER BY s.section_id,
                         r.start_time - r.buffer_before_ms,
                         r.request_id
                """, params, CONFLICT_MAPPER);
    }

    /**
     * 乐观条件状态迁移：版本与当前状态均匹配时才更新并递增版本。
     *
     * @return 受影响行数；为 0 表示版本不符或状态已变化
     */
    public int casStatus(String requestId, int expectedVersion, RequestStatus expectedStatus,
                         RequestStatus newStatus, long now) {
        return jdbc.sql("""
                UPDATE possession_request
                SET status = :newStatus, version = version + 1, updated_at = :now
                WHERE request_id = :id AND version = :expectedVersion AND status = :expectedStatus
                """)
                .param("newStatus", newStatus.name())
                .param("now", now)
                .param("id", requestId)
                .param("expectedVersion", expectedVersion)
                .param("expectedStatus", expectedStatus.name())
                .update();
    }

    /**
     * 抢占一个尚未激活的低优先级窗口：仅当仍为 APPROVED 且版本未变时置为 PREEMPTED。
     */
    public int preemptRequest(String requestId, int expectedVersion, long now) {
        return casStatus(requestId, expectedVersion, RequestStatus.APPROVED,
                RequestStatus.PREEMPTED, now);
    }

    public void appendEvent(RequestEvent event) {
        jdbc.sql("""
                INSERT INTO request_event
                  (request_id, event_type, from_status, to_status, business_time,
                   occurred_at, fingerprint, detail)
                VALUES (:requestId, :eventType, :fromStatus, :toStatus, :businessTime,
                        :occurredAt, :fingerprint, :detail)
                """)
                .param("requestId", event.getRequestId())
                .param("eventType", event.getEventType())
                .param("fromStatus", event.getFromStatus() == null ? null : event.getFromStatus().name())
                .param("toStatus", event.getToStatus().name())
                .param("businessTime", event.getBusinessTime())
                .param("occurredAt", event.getOccurredAt())
                .param("fingerprint", event.getFingerprint())
                .param("detail", event.getDetail())
                .update();
    }

    public List<RequestEvent> findEvents(String requestId) {
        return jdbc.sql("SELECT * FROM request_event WHERE request_id = :id ORDER BY id")
                .param("id", requestId)
                .query((rs, n) -> {
                    RequestEvent e = new RequestEvent();
                    e.setId(rs.getLong("id"));
                    e.setRequestId(rs.getString("request_id"));
                    e.setEventType(rs.getString("event_type"));
                    String from = rs.getString("from_status");
                    e.setFromStatus(from == null ? null : RequestStatus.valueOf(from));
                    e.setToStatus(RequestStatus.valueOf(rs.getString("to_status")));
                    long business = rs.getLong("business_time");
                    e.setBusinessTime(rs.wasNull() ? null : business);
                    e.setOccurredAt(rs.getLong("occurred_at"));
                    e.setFingerprint(rs.getString("fingerprint"));
                    e.setDetail(rs.getString("detail"));
                    return e;
                })
                .list();
    }

    public IdempotencyRecord findIdempotency(String requestKey) {
        return jdbc.sql("SELECT * FROM idempotency_record WHERE request_key = :key")
                .param("key", requestKey)
                .query((rs, n) -> {
                    IdempotencyRecord rec = new IdempotencyRecord();
                    rec.setRequestKey(rs.getString("request_key"));
                    rec.setFingerprint(rs.getString("fingerprint"));
                    rec.setOperation(rs.getString("operation"));
                    rec.setRequestId(rs.getString("request_id"));
                    rec.setResponseCode(rs.getInt("response_code"));
                    rec.setResponseBody(rs.getString("response_body"));
                    rec.setCreatedAt(rs.getLong("created_at"));
                    return rec;
                })
                .optional()
                .orElse(null);
    }

    public void insertIdempotency(IdempotencyRecord record) {
        jdbc.sql("""
                INSERT INTO idempotency_record
                  (request_key, fingerprint, operation, request_id, response_code,
                   response_body, created_at)
                VALUES (:requestKey, :fingerprint, :operation, :requestId, :responseCode,
                        :responseBody, :createdAt)
                """)
                .param("requestKey", record.getRequestKey())
                .param("fingerprint", record.getFingerprint())
                .param("operation", record.getOperation())
                .param("requestId", record.getRequestId())
                .param("responseCode", record.getResponseCode())
                .param("responseBody", record.getResponseBody())
                .param("createdAt", record.getCreatedAt())
                .update();
    }

    public void insertSnapshot(PlanSnapshot snapshot) {
        jdbc.sql("""
                INSERT INTO plan_snapshot
                  (snapshot_id, request_id, version_at_publish, status_at_publish,
                   content, published_at)
                VALUES (:snapshotId, :requestId, :version, :status, :content, :now)
                """)
                .param("snapshotId", snapshot.getSnapshotId())
                .param("requestId", snapshot.getRequestId())
                .param("version", snapshot.getVersionAtPublish())
                .param("status", snapshot.getStatusAtPublish().name())
                .param("content", snapshot.getContent())
                .param("now", snapshot.getPublishedAt())
                .update();
    }

    public PlanSnapshot findSnapshot(String snapshotId) {
        return jdbc.sql("SELECT * FROM plan_snapshot WHERE snapshot_id = :id")
                .param("id", snapshotId)
                .query((rs, n) -> {
                    PlanSnapshot s = new PlanSnapshot();
                    s.setSnapshotId(rs.getString("snapshot_id"));
                    s.setRequestId(rs.getString("request_id"));
                    s.setVersionAtPublish(rs.getInt("version_at_publish"));
                    s.setStatusAtPublish(RequestStatus.valueOf(rs.getString("status_at_publish")));
                    s.setContent(rs.getString("content"));
                    s.setPublishedAt(rs.getLong("published_at"));
                    return s;
                })
                .optional()
                .orElse(null);
    }
}
