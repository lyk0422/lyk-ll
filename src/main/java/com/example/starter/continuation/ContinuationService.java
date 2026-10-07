package com.example.starter.continuation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.continuation.ContinuationDtos.ApplyRequest;
import com.example.starter.continuation.ContinuationDtos.ConfirmRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.IssueRequest;
import com.example.starter.continuation.ContinuationDtos.RemainingStepSpec;
import com.example.starter.continuation.ContinuationDtos.WithdrawRequest;
import com.example.starter.error.ApiException;
import com.example.starter.ground.GroundWireService;
import com.example.starter.interlock.InterlockResult;
import com.example.starter.interlock.InterlockService;
import com.example.starter.ticket.SwitchingOrderService;

/**
 * 余步续票服务：引用异常/作废未归档原票，冻结剩余步骤生成续票；
 * 两名不同审核人确认后签发，签发时复核当前设备状态与原票版本（不以旧拟票快照跳过五防），
 * 生效原子交接间隔执行权与原票实物责任，中间不释放给第三票；原票保持中止，不重开或推进已做步骤。
 */
@Service
public class ContinuationService {

    private static final RowMapper<ContinuationRow> CONTINUATION_MAPPER = (rs, n) -> new ContinuationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("source_ticket_id"),
            rs.getLong("new_ticket_id"),
            rs.getInt("source_version"),
            rs.getString("reviewer_a"),
            rs.getString("reviewer_b"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<StepRow> STEP_MAPPER = (rs, n) -> new StepRow(
            rs.getInt("seq"),
            rs.getString("operation_type"),
            rs.getString("device_id"),
            rs.getString("expected_state"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;
    private final GroundWireService groundWireService;

    public ContinuationService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService,
                               GroundWireService groundWireService) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
        this.groundWireService = groundWireService;
    }

    /**
     * 申请续票：原票须为 EXCEPTION/EXPIRED 且未归档；冻结原步序、间隔与剩余步骤生成续票；
     * 无剩余步骤拒绝；拒绝跨间隔与删改剩余步骤。
     */
    @Transactional
    public ContinuationView apply(ApplyRequest req) {
        SourceTicket source = mustLoadSource(req.sourceTicketId());
        if (!"EXCEPTION".equals(source.status()) && !"EXPIRED".equals(source.status())) {
            throw ApiException.conflict("SOURCE_STATE",
                    "仅异常或已作废且未归档的原票可申请续票，当前状态: " + source.status());
        }
        if (source.version() != req.expectedSourceVersion()) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "原票期望版本 " + req.expectedSourceVersion() + " 与当前版本 " + source.version() + " 不符");
        }
        if (!source.intervalId().equals(req.intervalId())) {
            throw ApiException.conflict("CROSS_INTERVAL",
                    "续票不允许跨间隔：原票间隔 " + source.intervalId() + "，请求间隔 " + req.intervalId());
        }
        List<StepRow> remaining = jdbc.query(
                "SELECT seq, operation_type, device_id, expected_state FROM switching_step"
                        + " WHERE ticket_id = ? AND status = 'PENDING' ORDER BY seq",
                STEP_MAPPER, req.sourceTicketId());
        if (remaining.isEmpty()) {
            throw ApiException.unprocessable("NO_REMAINING_STEPS",
                    "原票 " + req.sourceTicketId() + " 无剩余待执行步骤，不允许续票");
        }
        if (req.remainingSteps() != null) {
            assertRemainingStepsUnchanged(req.sourceTicketId(), remaining, req.remainingSteps());
        }
        Instant now = Instant.now(clock);
        long newTicketId = insertTicket(req, source, now);
        for (StepRow step : remaining) {
            jdbc.update(
                    "INSERT INTO switching_step (ticket_id, seq, operation_type, device_id, expected_state, status)"
                            + " VALUES (?, ?, ?, ?, ?, 'PENDING')",
                    newTicketId, step.seq(), step.operationType(), step.deviceId(), step.expectedState());
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO continuation_request (request_id, source_ticket_id, new_ticket_id, source_version,"
                            + " status, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setLong(2, req.sourceTicketId());
            ps.setLong(3, newTicketId);
            ps.setInt(4, source.version());
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return toView(mustLoad(keyHolder.getKey().longValue()));
    }

    /** 双人确认：两名不同审核人；申请单 PENDING → CONFIRMED，续票 DRAFT → REVIEWED。 */
    @Transactional
    public ContinuationView confirm(long continuationId, ConfirmRequest req) {
        ContinuationRow row = mustLoad(continuationId);
        if (!"PENDING".equals(row.status())) {
            throw ApiException.conflict("CONTINUATION_STATE",
                    "仅待确认申请可双人确认，当前状态: " + row.status());
        }
        assertVersion(row, req.expectedVersion());
        if (req.reviewerA().equals(req.reviewerB())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "续票须两名不同审核人确认，同一审核人不生效");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE continuation_request SET status = 'CONFIRMED', reviewer_a = ?, reviewer_b = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ?",
                req.reviewerA(), req.reviewerB(), Timestamp.from(now), continuationId);
        jdbc.update("UPDATE switching_ticket SET status = 'REVIEWED', reviewer_id = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ? AND status = 'DRAFT'",
                req.reviewerA(), Timestamp.from(now), row.newTicketId());
        return toView(mustLoad(continuationId));
    }

    /**
     * 签发生效：复核原票版本未被改动、当前设备状态五防放行（不以旧拟票快照跳过）；
     * 同一事务内原子取得间隔执行权、登记实物责任接续并生效续票，中间不释放给第三票。
     * 每张原票最多一个生效后继。
     */
    @Transactional
    public ContinuationView issue(long continuationId, IssueRequest req) {
        ContinuationRow row = mustLoad(continuationId);
        if (!"CONFIRMED".equals(row.status())) {
            throw ApiException.conflict("CONTINUATION_STATE",
                    "仅已双人确认的申请可签发，当前状态: " + row.status());
        }
        assertVersion(row, req.expectedVersion());
        SourceTicket source = mustLoadSource(row.sourceTicketId());
        if (!"EXCEPTION".equals(source.status()) && !"EXPIRED".equals(source.status())) {
            throw ApiException.conflict("SOURCE_STATE",
                    "签发时原票状态已变化，不再允许续票生效，当前状态: " + source.status());
        }
        if (source.version() != row.sourceVersion()) {
            throw ApiException.conflict("SOURCE_CHANGED",
                    "签发复核：原票版本 " + source.version() + " 与申请快照 " + row.sourceVersion()
                            + " 不一致，原票在申请后已被改动，拒绝生效");
        }
        Integer effective = jdbc.queryForObject(
                "SELECT COUNT(*) FROM continuation_request WHERE source_ticket_id = ? AND status = 'EFFECTIVE'",
                Integer.class, row.sourceTicketId());
        if (effective != null && effective > 0) {
            throw ApiException.conflict("DUPLICATE_SUCCESSOR",
                    "原票 " + row.sourceTicketId() + " 已存在生效后继，每张原票最多一个生效续票");
        }
        // 复核当前设备状态：对续票第一个剩余步骤按实时状态重做五防评估，不使用拟票快照
        StepRow first = jdbc.query(
                        "SELECT seq, operation_type, device_id, expected_state FROM switching_step"
                                + " WHERE ticket_id = ? AND status = 'PENDING' ORDER BY seq LIMIT 1",
                        STEP_MAPPER, row.newTicketId())
                .stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("NO_REMAINING_STEPS", "续票无剩余步骤，不允许签发"));
        InterlockResult interlock = interlockService.evaluate(first.operationType(), first.deviceId());
        if (interlock.blocked()) {
            interlockService.recordHit(row.newTicketId(), first.seq(), interlock.ruleCode(), interlock.detail());
            throw ApiException.conflict("INTERLOCK_BLOCKED",
                    "签发复核当前设备状态被闭锁: " + interlock.detail() + " [" + interlock.ruleCode() + "]");
        }
        Instant now = Instant.now(clock);
        try {
            jdbc.update("INSERT INTO interval_execution_lock (interval_id, ticket_id, acquired_at) VALUES (?, ?, ?)",
                    source.intervalId(), row.newTicketId(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CONCURRENT_TICKET",
                    "间隔 " + source.intervalId() + " 执行权已被其他操作票持有，续票不能生效");
        }
        jdbc.update("UPDATE switching_ticket SET status = 'ISSUED', issued_at = ?, expires_at = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ? AND status = 'REVIEWED'",
                Timestamp.from(now), Timestamp.from(now.plus(SwitchingOrderService.VALIDITY)),
                Timestamp.from(now), row.newTicketId());
        // 实物责任接续与签发同事务：原借用编号与历史证据不改，占用权登记到续票名下
        groundWireService.transferCustody(continuationId, row.sourceTicketId(), row.newTicketId());
        jdbc.update("UPDATE continuation_request SET status = 'EFFECTIVE', version = version + 1, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(now), continuationId);
        return toView(mustLoad(continuationId));
    }

    /** 撤回：仅未生效（待确认/已确认）申请可撤回；不释放原间隔或实物；已生效不能撤回。 */
    @Transactional
    public ContinuationView withdraw(long continuationId, WithdrawRequest req) {
        ContinuationRow row = mustLoad(continuationId);
        if ("EFFECTIVE".equals(row.status())) {
            throw ApiException.conflict("CONTINUATION_STATE", "续票已生效，不能撤回");
        }
        if (!"PENDING".equals(row.status()) && !"CONFIRMED".equals(row.status())) {
            throw ApiException.conflict("CONTINUATION_STATE", "当前状态不可撤回: " + row.status());
        }
        assertVersion(row, req.expectedVersion());
        jdbc.update("UPDATE continuation_request SET status = 'WITHDRAWN', version = version + 1, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(Instant.now(clock)), continuationId);
        return toView(mustLoad(continuationId));
    }

    /** 查询续票申请单。 */
    @Transactional(readOnly = true)
    public ContinuationView get(long continuationId) {
        return toView(mustLoad(continuationId));
    }

    private void assertRemainingStepsUnchanged(long sourceTicketId, List<StepRow> remaining,
                                               List<RemainingStepSpec> declared) {
        List<RemainingStepSpec> actual = remaining.stream()
                .map(s -> new RemainingStepSpec(s.seq(), s.operationType(), s.deviceId(), s.expectedState()))
                .toList();
        if (!actual.equals(declared)) {
            throw ApiException.conflict("STEPS_MODIFIED",
                    "剩余步骤必须与原票冻结一致，不允许删改: 原票 " + sourceTicketId);
        }
    }

    private long insertTicket(ApplyRequest req, SourceTicket source, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO switching_ticket (request_id, interval_id, title, status, continuation_of,"
                            + " version, created_at, updated_at) VALUES (?, ?, ?, 'DRAFT', ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setString(2, source.intervalId());
            ps.setString(3, req.title());
            ps.setLong(4, req.sourceTicketId());
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void assertVersion(ContinuationRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "续票申请单期望版本 " + expectedVersion + " 与当前版本 " + row.version() + " 不符");
        }
    }

    private ContinuationRow mustLoad(long continuationId) {
        return jdbc.query("SELECT * FROM continuation_request WHERE id = ?", CONTINUATION_MAPPER, continuationId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CONTINUATION_NOT_FOUND", "续票申请单不存在: " + continuationId));
    }

    private SourceTicket mustLoadSource(long ticketId) {
        return jdbc.query(
                        "SELECT id, interval_id, status, version FROM switching_ticket WHERE id = ?",
                        (rs, n) -> new SourceTicket(rs.getLong("id"), rs.getString("interval_id"),
                                rs.getString("status"), rs.getInt("version")),
                        ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "原票不存在: " + ticketId));
    }

    private ContinuationView toView(ContinuationRow row) {
        return new ContinuationView(row.id(), row.sourceTicketId(), row.newTicketId(), row.sourceVersion(),
                row.status(), row.reviewerA(), row.reviewerB(), row.version(), row.createdAt(), row.updatedAt());
    }

    /** 续票申请单行记录。 */
    record ContinuationRow(long id, String requestId, long sourceTicketId, long newTicketId, int sourceVersion,
                           String reviewerA, String reviewerB, String status, int version,
                           Instant createdAt, Instant updatedAt) {
    }

    /** 步骤行记录。 */
    record StepRow(int seq, String operationType, String deviceId, String expectedState) {
    }

    /** 原票行记录。 */
    record SourceTicket(long id, String intervalId, String status, int version) {
    }
}
