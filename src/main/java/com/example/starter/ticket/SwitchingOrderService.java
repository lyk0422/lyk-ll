package com.example.starter.ticket;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.ApiException;
import com.example.starter.groundwire.GroundWireService;
import com.example.starter.interlock.InterlockResult;
import com.example.starter.interlock.InterlockService;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.ConfirmView;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.HitView;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;
import com.example.starter.ticket.TicketDtos.StepSpec;
import com.example.starter.ticket.TicketDtos.StepView;
import com.example.starter.ticket.TicketDtos.TicketDetailView;
import com.example.starter.ticket.TicketDtos.TicketView;

/**
 * 倒闸操作票核心服务：拟票→审核→预发→逐项执行→归档流转，
 * 双人确认、24 小时有效期、防误闭锁联动与归档终态。
 */
@Service
public class SwitchingOrderService {

    /** 操作票自预发起的有效期：24 小时，恰满即作废。 */
    public static final Duration VALIDITY = Duration.ofHours(24);

    private static final RowMapper<TicketRow> TICKET_MAPPER = (rs, rowNum) -> new TicketRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("interval_id"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("reviewer_id"),
            toInstant(rs.getTimestamp("issued_at")),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<StepRow> STEP_MAPPER = (rs, rowNum) -> new StepRow(
            rs.getLong("id"),
            rs.getInt("seq"),
            rs.getString("operation_type"),
            rs.getString("device_id"),
            rs.getString("expected_state"),
            rs.getString("status"),
            rs.getString("operator_id"),
            rs.getString("guardian_id"),
            toInstant(rs.getTimestamp("executed_at")),
            rs.getBoolean("verified"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;
    private final GroundWireService groundWireService;
    private final TransactionTemplate requiresNewTx;

    public SwitchingOrderService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService,
                                 GroundWireService groundWireService, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
        this.groundWireService = groundWireService;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 拟票：创建 DRAFT 操作票及步骤。 */
    @Transactional
    public TicketDetailView create(CreateTicketRequest req) {
        Instant now = Instant.now(clock);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO switching_ticket (request_id, interval_id, title, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'DRAFT', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setString(2, req.intervalId());
            ps.setString(3, req.title());
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long ticketId = keyHolder.getKey().longValue();
        int seq = 1;
        for (StepSpec step : req.steps()) {
            jdbc.update(
                    "INSERT INTO switching_step (ticket_id, seq, operation_type, device_id, expected_state, status)"
                            + " VALUES (?, ?, ?, ?, ?, 'PENDING')",
                    ticketId, seq++, step.operationType(), step.deviceId(), step.expectedState());
        }
        return detail(ticketId);
    }

    /** 审核：DRAFT → REVIEWED。 */
    @Transactional
    public TicketDetailView review(long ticketId, ReviewRequest req) {
        TicketRow ticket = mustLoad(ticketId);
        if (!"DRAFT".equals(ticket.status())) {
            throw ApiException.conflict("TICKET_STATE", "仅拟票状态可审核，当前状态: " + ticket.status());
        }
        jdbc.update("UPDATE switching_ticket SET status = 'REVIEWED', reviewer_id = ?, updated_at = ? WHERE id = ?",
                req.reviewerId(), Timestamp.from(Instant.now(clock)), ticketId);
        return detail(ticketId);
    }

    /** 预发：REVIEWED → ISSUED，占用间隔执行锁，登记 24 小时有效期（UTC）。 */
    @Transactional
    public TicketDetailView issue(long ticketId, IssueRequest req) {
        TicketRow ticket = mustLoad(ticketId);
        if (!"REVIEWED".equals(ticket.status())) {
            throw ApiException.conflict("TICKET_STATE", "仅已审核状态可预发，当前状态: " + ticket.status());
        }
        Instant now = Instant.now(clock);
        try {
            jdbc.update("INSERT INTO interval_execution_lock (interval_id, ticket_id, acquired_at) VALUES (?, ?, ?)",
                    ticket.intervalId(), ticketId, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CONCURRENT_TICKET",
                    "间隔 " + ticket.intervalId() + " 已存在执行中操作票");
        }
        jdbc.update("UPDATE switching_ticket SET status = 'ISSUED', issued_at = ?, expires_at = ?, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now.plus(VALIDITY)), Timestamp.from(now), ticketId);
        return detail(ticketId);
    }

    /** 步骤双人确认：校验有效期、步序、双人确认与防误闭锁后执行一步。 */
    @Transactional
    public ConfirmView confirm(long ticketId, int seq, ConfirmStepRequest req) {
        TicketRow ticket = mustLoad(ticketId);
        refreshExpiry(ticket, true);
        if (!"ISSUED".equals(ticket.status()) && !"EXECUTING".equals(ticket.status())) {
            throw ApiException.conflict("TICKET_STATE", "仅预发或执行中状态可执行步骤，当前状态: " + ticket.status());
        }
        if (req.operatorId().equals(req.guardianId())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "操作人与监护人必须为两人，单人操作不生效");
        }
        StepRow step = jdbc.query("SELECT * FROM switching_step WHERE ticket_id = ? AND seq = ?",
                        STEP_MAPPER, ticketId, seq)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("STEP_NOT_FOUND", "步骤不存在: " + seq));
        if (!"PENDING".equals(step.status())) {
            throw ApiException.conflict("STEP_STATE", "步骤已执行，当前状态: " + step.status());
        }
        Integer nextSeq = jdbc.query(
                        "SELECT MIN(seq) FROM switching_step WHERE ticket_id = ? AND status = 'PENDING'",
                        (rs, rowNum) -> rs.getInt(1), ticketId)
                .stream().findFirst().orElse(null);
        if (nextSeq == null || nextSeq != seq) {
            throw ApiException.conflict("STEP_ORDER", "须逐项执行，当前应执行步序: " + nextSeq);
        }
        InterlockResult interlock = interlockService.evaluate(step.operationType(), step.deviceId());
        if (interlock.blocked()) {
            interlockService.recordHit(ticketId, seq, interlock.ruleCode(), interlock.detail());
            throw ApiException.conflict("INTERLOCK_BLOCKED",
                    interlock.detail() + " [" + interlock.ruleCode() + "]");
        }
        // 送电门禁：送电类步在事务内重新检查间隔临时接地线均已拆除，不信任拟票快照
        if ("CLOSE_BREAKER".equals(step.operationType())) {
            groundWireService.assertIntervalGroundsRemoved(ticket.intervalId(), ticketId, seq);
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE switching_step SET status = 'DONE', operator_id = ?, guardian_id = ?, executed_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                req.operatorId(), req.guardianId(), Timestamp.from(now), step.id());
        jdbc.update("UPDATE device_state SET state = ?, updated_at = ? WHERE device_id = ?",
                step.expectedState(), Timestamp.from(now), step.deviceId());
        jdbc.update("UPDATE switching_ticket SET status = 'EXECUTING', updated_at = ? WHERE id = ?",
                Timestamp.from(now), ticketId);
        return new ConfirmView(ticketId, seq, "DONE", "EXECUTING");
    }

    /** 归档：终态。重复归档返回幂等成功；到期票先转作废再归档。 */
    @Transactional
    public TicketDetailView archive(long ticketId, ArchiveRequest req) {
        TicketRow ticket = mustLoad(ticketId);
        if ("ARCHIVED".equals(ticket.status())) {
            return detail(ticketId);
        }
        ticket = refreshExpiry(ticket, false);
        switch (ticket.status()) {
            case "ISSUED", "EXECUTING", "EXCEPTION", "EXPIRED" -> {
                // 归档门禁：本票借用的临时接地线须全部归还，历史借用记录保留
                int outstanding = groundWireService.countOutstandingForTicket(ticketId);
                if (outstanding > 0) {
                    throw ApiException.unprocessable("GROUND_WIRE_OUTSTANDING",
                            "本票尚有 " + outstanding + " 条临时接地线未归还核销，不可归档");
                }
                jdbc.update("UPDATE switching_ticket SET status = 'ARCHIVED', updated_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now(clock)), ticketId);
                releaseLock(ticket.intervalId(), ticketId);
            }
            default -> throw ApiException.conflict("TICKET_STATE", "当前状态不可归档: " + ticket.status());
        }
        return detail(ticketId);
    }

    /** 查询操作票详情：票、步序轨迹与闭锁命中。 */
    @Transactional(readOnly = true)
    public TicketDetailView get(long ticketId) {
        mustLoad(ticketId);
        return detail(ticketId);
    }

    /**
     * 遥信核对：活动票最近一条已执行未核对步骤与回报比对，
     * 一致则标记核对，不符则中止操作票转异常并释放间隔锁。
     *
     * @return VERIFIED 核对一致 / ABORTED 不符中止 / RECORDED 仅记录
     */
    @Transactional
    public String processTelemetry(long ticketId, String deviceId, String state) {
        TicketRow ticket = mustLoad(ticketId);
        ticket = refreshExpiry(ticket, false);
        if (!"ISSUED".equals(ticket.status()) && !"EXECUTING".equals(ticket.status())) {
            return "RECORDED";
        }
        StepRow step = jdbc.query(
                        "SELECT * FROM switching_step WHERE ticket_id = ? AND status = 'DONE' AND verified = FALSE"
                                + " ORDER BY seq DESC LIMIT 1",
                        STEP_MAPPER, ticketId)
                .stream().findFirst().orElse(null);
        if (step == null || !step.deviceId().equals(deviceId)) {
            return "RECORDED";
        }
        if (step.expectedState().equals(state)) {
            jdbc.update("UPDATE switching_step SET verified = TRUE WHERE id = ?", step.id());
            return "VERIFIED";
        }
        jdbc.update("UPDATE switching_ticket SET status = 'EXCEPTION', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), ticketId);
        releaseLock(ticket.intervalId(), ticketId);
        return "ABORTED";
    }

    /** 查询间隔当前执行中操作票主键，无则返回 null。 */
    public Long activeTicketForInterval(String intervalId) {
        return jdbc.query("SELECT ticket_id FROM interval_execution_lock WHERE interval_id = ?",
                        (rs, rowNum) -> rs.getLong(1), intervalId)
                .stream().findFirst().orElse(null);
    }

    /** 判断间隔是否存在已归档操作票（用于迟到遥信标记）。 */
    public boolean hasArchivedTicket(String intervalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM switching_ticket WHERE interval_id = ? AND status = 'ARCHIVED'",
                Integer.class, intervalId);
        return count != null && count > 0;
    }

    /**
     * 有效期检查：预发或执行中的票恰满 24 小时（UTC）即作废并释放间隔锁，已执行步骤保留。
     *
     * @param throwOnExpire 为 true 时作废后抛 409，为 false 时静默转作废
     */
    private TicketRow refreshExpiry(TicketRow ticket, boolean throwOnExpire) {
        boolean expirable = "ISSUED".equals(ticket.status()) || "EXECUTING".equals(ticket.status());
        if (expirable && ticket.expiresAt() != null && !Instant.now(clock).isBefore(ticket.expiresAt())) {
            // 作废转换独立事务提交：即使外层操作随后拒绝回滚，作废状态与锁释放仍生效
            requiresNewTx.executeWithoutResult(s -> {
                jdbc.update("UPDATE switching_ticket SET status = 'EXPIRED', updated_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now(clock)), ticket.id());
                releaseLock(ticket.intervalId(), ticket.id());
            });
            if (throwOnExpire) {
                throw ApiException.conflict("TICKET_EXPIRED", "操作票自预发起已满24小时，恰满作废");
            }
            return mustLoad(ticket.id());
        }
        return ticket;
    }

    private void releaseLock(String intervalId, long ticketId) {
        jdbc.update("DELETE FROM interval_execution_lock WHERE interval_id = ? AND ticket_id = ?",
                intervalId, ticketId);
    }

    private TicketRow mustLoad(long ticketId) {
        return jdbc.query("SELECT * FROM switching_ticket WHERE id = ?", TICKET_MAPPER, ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private TicketDetailView detail(long ticketId) {
        TicketRow ticket = mustLoad(ticketId);
        List<StepView> steps = jdbc.query("SELECT * FROM switching_step WHERE ticket_id = ? ORDER BY seq",
                        STEP_MAPPER, ticketId)
                .stream()
                .map(s -> new StepView(s.seq(), s.operationType(), s.deviceId(), s.expectedState(), s.status(),
                        s.operatorId(), s.guardianId(), s.executedAt(), s.verified()))
                .toList();
        List<HitView> hits = jdbc.query(
                        "SELECT * FROM interlock_hit WHERE ticket_id = ? ORDER BY id",
                        (rs, rowNum) -> new HitView(rs.getLong("id"),
                                rs.getObject("step_seq") == null ? null : rs.getInt("step_seq"),
                                rs.getString("rule_code"), rs.getString("detail"),
                                toInstant(rs.getTimestamp("created_at"))),
                        ticketId);
        TicketView view = new TicketView(ticket.id(), ticket.intervalId(), ticket.title(), ticket.status(),
                ticket.reviewerId(), ticket.issuedAt(), ticket.expiresAt(), ticket.createdAt(), ticket.updatedAt());
        return new TicketDetailView(view, steps, hits);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 操作票行记录。 */
    record TicketRow(long id, String requestId, String intervalId, String title, String status,
                     String reviewerId, Instant issuedAt, Instant expiresAt,
                     Instant createdAt, Instant updatedAt) {
    }

    /** 步骤行记录。 */
    record StepRow(long id, int seq, String operationType, String deviceId, String expectedState,
                   String status, String operatorId, String guardianId, Instant executedAt, boolean verified) {
    }
}
