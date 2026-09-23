package com.example.starter.repository;

import com.example.starter.domain.OperationType;
import com.example.starter.domain.PossessionRequest;
import com.example.starter.domain.RequestStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 封锁窗数据访问层（H2 MySQL 兼容模式，JdbcTemplate）。
 *
 * <p>并发控制依赖区段行锁：审批/抢占事务先以确定顺序对相关区段加行锁，
 * 无共同区段的申请互不阻塞；共同区段上的并发事务串行化，配合状态更新保证
 * 同一区段同一时刻只可能有一个提交成功。
 */
@Repository
public class PossessionRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PossessionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 冲突查询原始行：冲突申请在某个共有区段上的占用窗信息。
     * 同一冲突申请可能在多个区段上产生多行。
     */
    public record ConflictRow(
            String sectionId,
            String requestId,
            RequestStatus status,
            int priority,
            int version,
            Instant bufferedStart,
            Instant bufferedEnd
    ) {
    }

    private static final RowMapper<PossessionRequest> REQUEST_MAPPER = (rs, n) -> new PossessionRequest(
            rs.getString("id"),
            List.of(),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time").toInstant(),
            rs.getTimestamp("buffered_start").toInstant(),
            rs.getTimestamp("buffered_end").toInstant(),
            rs.getLong("pre_buffer_seconds"),
            rs.getLong("post_buffer_seconds"),
            rs.getInt("priority"),
            RequestStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("business_time").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    /**
     * 插入新申请：主表行、区段登记与关联、SUBMITTED 事件，同一事务完成。
     *
     * @throws DuplicateKeyException requestId 已存在
     */
    @Transactional
    public void insertRequest(PossessionRequest request, List<String> orderedSectionIds) {
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO possession_request
                  (id, start_time, end_time, buffered_start, buffered_end,
                   pre_buffer_seconds, post_buffer_seconds, priority, status, version,
                   business_time, created_at)
                VALUES (?,?,?,?,?,?,?,?, 'PENDING', 1, ?,?)
                """,
                request.id(),
                Timestamp.from(request.start()),
                Timestamp.from(request.end()),
                Timestamp.from(request.bufferedStart()),
                Timestamp.from(request.bufferedEnd()),
                request.preBufferSeconds(),
                request.postBufferSeconds(),
                request.priority(),
                Timestamp.from(request.businessTime()),
                Timestamp.from(now));

        for (int i = 0; i < orderedSectionIds.size(); i++) {
            String sectionId = orderedSectionIds.get(i);
            jdbc.update("MERGE INTO rail_section (id) KEY (id) VALUES (?)", sectionId);
            jdbc.update("INSERT INTO possession_section (request_id, section_id, ordinal) VALUES (?,?,?)",
                    request.id(), sectionId, i);
        }
        insertEvent(request.id(), "SUBMITTED", null, RequestStatus.PENDING,
                request.businessTime(), 1, null, now);
    }

    /**
     * 按申请提交顺序返回其区段列表；申请不存在时返回 null。
     */
    public PossessionRequest findById(String requestId) {
        List<PossessionRequest> rows = jdbc.query(
                "SELECT * FROM possession_request WHERE id = ?", REQUEST_MAPPER, requestId);
        if (rows.isEmpty()) {
            return null;
        }
        PossessionRequest base = rows.get(0);
        List<String> sections = jdbc.queryForList(
                "SELECT section_id FROM possession_section WHERE request_id = ? ORDER BY ordinal",
                String.class, requestId);
        return new PossessionRequest(
                base.id(), sections, base.start(), base.end(), base.bufferedStart(), base.bufferedEnd(),
                base.preBufferSeconds(), base.postBufferSeconds(), base.priority(), base.status(),
                base.version(), base.businessTime(), base.createdAt());
    }

    /**
     * 列出全部申请（按业务时间、ID 稳定排序），用于发布快照。
     */
    public List<PossessionRequest> listAll() {
        List<PossessionRequest> all = jdbc.query(
                "SELECT * FROM possession_request ORDER BY business_time, id", REQUEST_MAPPER);
        List<PossessionRequest> result = new ArrayList<>();
        for (PossessionRequest base : all) {
            List<String> sections = jdbc.queryForList(
                    "SELECT section_id FROM possession_section WHERE request_id = ? ORDER BY ordinal",
                    String.class, base.id());
            result.add(new PossessionRequest(
                    base.id(), sections, base.start(), base.end(), base.bufferedStart(), base.bufferedEnd(),
                    base.preBufferSeconds(), base.postBufferSeconds(), base.priority(), base.status(),
                    base.version(), base.businessTime(), base.createdAt()));
        }
        return result;
    }

    /**
     * 以确定顺序（字典序）锁定全部相关区段行。调用方须在事务内；
     * 调用前须保证区段已登记（findById 的申请必然已登记）。
     */
    public void lockSections(Collection<String> sectionIds) {
        List<String> ordered = sectionIds.stream().sorted().distinct().toList();
        if (ordered.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ordered.stream().map(s -> "?").toList());
        jdbc.queryForList(
                "SELECT id FROM rail_section WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                String.class, ordered.toArray());
    }

    /**
     * 查询与给定区段集、占用窗正长度重叠的已批准/已激活窗口（半开区间，端点相接不算）。
     * 必须在区段行锁保护下调用，保证并发审批决策串行一致。
     *
     * @param excludeRequestId 需排除的申请ID（通常为申请自身）；null 表示不排除
     */
    public List<ConflictRow> findActiveConflicts(Collection<String> sectionIds,
                                                 Instant bufferedStart, Instant bufferedEnd,
                                                 String excludeRequestId) {
        List<String> ordered = sectionIds.stream().sorted().distinct().toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ordered.stream().map(s -> "?").toList());
        Object[] args = new Object[ordered.size() + 3];
        for (int i = 0; i < ordered.size(); i++) {
            args[i] = ordered.get(i);
        }
        args[ordered.size()] = Timestamp.from(bufferedEnd);
        args[ordered.size() + 1] = Timestamp.from(bufferedStart);
        args[ordered.size() + 2] = excludeRequestId;
        return jdbc.query("""
                SELECT ps.section_id AS section_id, r.id AS id, r.status AS status,
                       r.priority AS priority, r.version AS version,
                       r.buffered_start AS buffered_start, r.buffered_end AS buffered_end
                FROM possession_request r
                JOIN possession_section ps ON ps.request_id = r.id
                WHERE ps.section_id IN (""" + placeholders + ") "
                        + "AND r.status IN ('APPROVED','ACTIVE') "
                        + "AND r.buffered_start < ? AND r.buffered_end > ? "
                        + "AND r.id <> ? "
                        + "ORDER BY ps.section_id, r.buffered_start, r.id",
                (rs, n) -> new ConflictRow(
                        rs.getString("section_id"),
                        rs.getString("id"),
                        RequestStatus.valueOf(rs.getString("status")),
                        rs.getInt("priority"),
                        rs.getInt("version"),
                        rs.getTimestamp("buffered_start").toInstant(),
                        rs.getTimestamp("buffered_end").toInstant()),
                args);
    }

    /**
     * 乐观条件更新状态：仅当当前版本等于 expectedVersion 时生效。
     *
     * @return 受影响行数；0 表示版本已被并发事务改写
     */
    public int compareAndUpdateStatus(String requestId, RequestStatus from, RequestStatus to,
                                      int expectedVersion, int newVersion) {
        return jdbc.update("""
                UPDATE possession_request
                SET status = ?, version = ?
                WHERE id = ? AND status = ? AND version = ?
                """,
                to.name(), newVersion, requestId, from.name(), expectedVersion);
    }

    /**
     * 追加领域事件（事件流只增不改）。
     */
    public void insertEvent(String requestId, String eventType, RequestStatus from, RequestStatus to,
                            Instant businessTime, int versionAfter, String detail) {
        insertEvent(requestId, eventType, from, to, businessTime, versionAfter, detail, Instant.now(clock));
    }

    private void insertEvent(String requestId, String eventType, RequestStatus from, RequestStatus to,
                             Instant businessTime, int versionAfter, String detail, Instant now) {
        jdbc.update("""
                INSERT INTO possession_event
                  (request_id, event_type, from_status, to_status, business_time, version_after, detail, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                requestId, eventType,
                from == null ? null : from.name(),
                to.name(),
                Timestamp.from(businessTime),
                versionAfter,
                detail,
                Timestamp.from(now));
    }

    /**
     * 统计某申请的事件数，幂等重放校验“不追加事件”。
     */
    public int countEvents(String requestId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM possession_event WHERE request_id = ?", Integer.class, requestId);
        return c == null ? 0 : c;
    }

    /**
     * 幂等账本记录。
     */
    public record LedgerEntry(String fingerprint, String responseJson) {
    }

    public LedgerEntry findLedger(String requestId, OperationType operation) {
        List<LedgerEntry> rows = jdbc.query(
                "SELECT fingerprint, response_json FROM operation_ledger WHERE request_id = ? AND operation = ?",
                (rs, n) -> new LedgerEntry(rs.getString("fingerprint"), rs.getString("response_json")),
                requestId, operation.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写入首次成功结果；并发下若键已存在返回 false（由调用方重读复用）。
     */
    public boolean insertLedger(String requestId, OperationType operation,
                                String fingerprint, String responseJson) {
        try {
            jdbc.update("""
                    INSERT INTO operation_ledger (request_id, operation, fingerprint, response_json, created_at)
                    VALUES (?,?,?,?,?)
                    """,
                    requestId, operation.name(), fingerprint, responseJson,
                    Timestamp.from(Instant.now(clock)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 发布不可变快照。
     */
    public void insertSnapshot(String snapshotId, Instant businessTime, String contentJson) {
        jdbc.update("""
                INSERT INTO plan_snapshot (id, business_time, content_json, created_at)
                VALUES (?,?,?,?)
                """,
                snapshotId, Timestamp.from(businessTime), contentJson, Timestamp.from(Instant.now(clock)));
    }

    /**
     * 读取快照内容（JSON 原文与发布业务时间）；不存在返回 null。
     */
    public SnapshotRow findSnapshot(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT id, business_time, created_at, content_json FROM plan_snapshot WHERE id = ?",
                (rs, n) -> new SnapshotRow(
                        rs.getString("id"),
                        rs.getTimestamp("business_time").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("content_json")),
                snapshotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 快照原始行。
     */
    public record SnapshotRow(String id, Instant businessTime, Instant createdAt, String contentJson) {
    }
}
