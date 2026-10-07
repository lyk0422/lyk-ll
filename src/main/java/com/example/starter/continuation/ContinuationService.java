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

import com.example.starter.continuation.ContinuationDtos.ApplyContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.EffectContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ReviewContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.WithdrawContinuationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.interlock.InterlockResult;
import com.example.starter.interlock.InterlockService;
import com.example.starter.ticket.SwitchingOrderService;
import com.example.starter.ticket.TicketDtos.StepSpec;

/**
 * 余步续票服务：异常/作废原票的剩余步骤冻结续做。
 * 申请冻结原步序、间隔与剩余步骤；两名不同审核人确认后生效；
 * 生效在同一事务内复核原票版本与当前设备状态、登记唯一后继、
 * 原子转移间隔执行权与接地线责任接续，并将原票登记中止归档。
 * 原票保持中止，不重开或推进已做步骤；已执行证据不复制。
 */
@Service
public class ContinuationService {

    private static final RowMapper<ContinuationRow> CONTINUATION_MAPPER = (rs, n) -> new ContinuationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("original_ticket_id"),
            rs.getLong("new_ticket_id"),
            rs.getString("interval_id"),
            rs.getString("status"),
            rs.getString("reviewer_a"),
            rs.getString("reviewer_b"),
            rs.getInt("original_ticket_version"),
            rs.getInt("version"),
            toInstant(rs.getTimestamp("effective_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;

    public ContinuationService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
    }

    /**
     * 申请续票：原票须为 EXCEPTION 或 EXPIRED 且未归档；冻结原步序、间隔与剩余步骤。
     * 拒绝跨间隔、删改剩余步骤与无剩余步骤。新票以 DRAFT 建档，只能经续票流程生效。
     */
    @Transactional
    public ContinuationView apply(ApplyContinuationRequest req) {
        OriginalTicket original = mustLoadOriginal(req.originalTicketId());
        if (!"EXCEPTION".equals(original.status()) && !"EXPIRED".equals(original.status())) {
            throw ApiException.unprocessable("ORIGINAL_STATE",
                    "仅异常中止或已作废且未归档的原票可申请续票，当前状态: " + original.status());
        }
        if (!original.intervalId().equals(req.intervalId())) {
            throw ApiException.unprocessable("CROSS_INTERVAL",
                    "续票不允许跨间隔：原票间隔 " + original.intervalId() + "，申请间隔 " + req.intervalId());
        }
        List<RemainingStep> remaining = remainingSteps(original.id());
        if (remaining.isEmpty()) {
            throw ApiException.unprocessable("NO_REMAINING_STEPS",
                    "原票 " + original.id() + " 无剩余步骤，不允许续票");
        }
        if (remaining.size() != req.steps().size() || !stepsMatch(remaining, req.steps())) {
            throw ApiException.unprocessable("STEPS_MODIFIED",
                    "续票剩余步骤必须与原票冻结内容一致，拒绝删改剩余步骤");
        }
        Instant now = Instant.now(clock);
        KeyHolder ticketKeys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO switching_ticket (request_id, interval_id, title, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'DRAFT', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setString(2, original.intervalId());
            ps.setString(3, req.title());
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, ticketKeys);
        long newTicketId = ticketKeys.getKey().longValue();
        // 冻结原步序：剩余步骤按原步序原样复制，已执行步骤与证据不复制
        for (int i = 0; i < remaining.size(); i++) {
            RemainingStep step = remaining.get(i);
            jdbc.update(
                    "INSERT INTO switching_step (ticket_id, seq, operation_type, device_id, expected_state, status)"
                            + " VALUES (?, ?, ?, ?, ?, 'PENDING')",
                    newTicketId, step.seq(), step.operationType(), step.deviceId(), step.expectedState());
        }
        KeyHolder continuationKeys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO continuation_order (request_id, original_ticket_id, new_ticket_id, interval_id,"
                            + " status, original_ticket_version, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 'APPLIED', ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setLong(2, original.id());
            ps.setLong(3, newTicketId);
            ps.setString(4, original.intervalId());
            ps.setInt(5, original.version());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, continuationKeys);
        return view(continuationKeys.getKey().longValue());
    }

    /** 审核确认：两名不同审核人各确认一次，均须携带当前单据版本。 */
    @Transactional
    public ContinuationView review(long continuationId, ReviewContinuationRequest req) {
        ContinuationRow doc = mustLoadForUpdate(continuationId);
        if (!"APPLIED".equals(doc.status())) {
            throw ApiException.conflict("CONTINUATION_STATE",
                    "仅已申请状态可审核确认，当前状态: " + doc.status());
        }
        assertVersion(doc, req.expectedVersion());
        Instant now = Instant.now(clock);
        if (doc.reviewerA() == null) {
            jdbc.update("UPDATE continuation_order SET reviewer_a = ?, version = version + 1, updated_at = ?"
                            + " WHERE id = ?",
                    req.reviewerId(), Timestamp.from(now), continuationId);
        } else {
            if (doc.reviewerA().equals(req.reviewerId())) {
                throw ApiException.badRequest("DOUBLE_REVIEW_REQUIRED",
                        "续票须两名不同审核人确认，审核人 " + req.reviewerId() + " 已确认过");
            }
            jdbc.update("UPDATE continuation_order SET reviewer_b = ?, status = 'REVIEWED',"
                            + " version = version + 1, updated_at = ? WHERE id = ?",
                    req.reviewerId(), Timestamp.from(now), continuationId);
        }
        return view(continuationId);
    }

    /**
     * 生效：同一事务内复核原票状态与版本、按当前设备状态复查五防（不以旧拟票快照跳过）、
     * 登记唯一生效后继、原子取得间隔执行权、转移接地线责任接续并将原票登记中止归档。
     * 新票按原签发有效期规则预发：自生效起 24 小时（UTC）。
     */
    @Transactional
    public ContinuationView effect(long continuationId, EffectContinuationRequest req) {
        ContinuationRow doc = mustLoadForUpdate(continuationId);
        if (!"REVIEWED".equals(doc.status())) {
            throw ApiException.conflict("CONTINUATION_STATE",
                    "仅双审完成状态可生效，当前状态: " + doc.status());
        }
        assertVersion(doc, req.expectedVersion());
        OriginalTicket original = mustLoadOriginal(doc.originalTicketId());
        if (!"EXCEPTION".equals(original.status()) && !"EXPIRED".equals(original.status())) {
            throw ApiException.conflict("ORIGINAL_STATE_CHANGED",
                    "原票状态已变化，不再处于异常/作废中止: " + original.status());
        }
        if (original.version() != doc.originalTicketVersion()) {
            throw ApiException.conflict("ORIGINAL_TICKET_CHANGED",
                    "原票版本已变化（申请时 " + doc.originalTicketVersion() + "，当前 " + original.version()
                            + "），续票签发拒绝，须重新申请");
        }
        // 签发复核：以当前设备实时状态对首个剩余步骤复查五防，不信任拟票快照
        RemainingStep first = remainingSteps(doc.newTicketId()).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("NO_REMAINING_STEPS", "续票无剩余步骤，不允许生效"));
        InterlockResult interlock = interlockService.evaluate(first.operationType(), first.deviceId());
        if (interlock.blocked()) {
            interlockService.recordHit(doc.newTicketId(), first.seq(), interlock.ruleCode(), interlock.detail());
            throw ApiException.conflict("INTERLOCK_BLOCKED",
                    "续票签发复核当前设备状态被闭锁: " + interlock.detail() + " [" + interlock.ruleCode() + "]");
        }
        Instant now = Instant.now(clock);
        try {
            jdbc.update("INSERT INTO continuation_succession (original_ticket_id, continuation_id, new_ticket_id,"
                            + " created_at) VALUES (?, ?, ?, ?)",
                    doc.originalTicketId(), continuationId, doc.newTicketId(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SUCCESSOR_EXISTS",
                    "原票 " + doc.originalTicketId() + " 已存在生效后继，每张原票最多一个生效后继");
        }
        try {
            jdbc.update("INSERT INTO interval_execution_lock (interval_id, ticket_id, acquired_at) VALUES (?, ?, ?)",
                    doc.intervalId(), doc.newTicketId(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CONCURRENT_TICKET",
                    "间隔 " + doc.intervalId() + " 已存在执行中操作票，续票生效拒绝");
        }
        jdbc.update("UPDATE switching_ticket SET status = 'ISSUED', issued_at = ?, expires_at = ?, updated_at = ?,"
                        + " version = version + 1 WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now.plus(SwitchingOrderService.VALIDITY)),
                Timestamp.from(now), doc.newTicketId());
        inheritGroundWires(doc, now);
        // 原票因继承得以登记中止归档：实物责任已接续，历史证据与借用记录保留可查
        jdbc.update("UPDATE switching_ticket SET status = 'ARCHIVED', updated_at = ?, version = version + 1"
                        + " WHERE id = ?",
                Timestamp.from(now), doc.originalTicketId());
        jdbc.update("UPDATE continuation_order SET status = 'EFFECTIVE', effective_at = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), continuationId);
        return view(continuationId);
    }

    /** 撤回：仅未生效申请可撤回；不释放原间隔或实物，原票状态与实物占用保持不变。 */
    @Transactional
    public ContinuationView withdraw(long continuationId, WithdrawContinuationRequest req) {
        ContinuationRow doc = mustLoadForUpdate(continuationId);
        if ("EFFECTIVE".equals(doc.status())) {
            throw ApiException.conflict("CONTINUATION_EFFECTIVE", "续票已生效，不能撤回");
        }
        if (!"APPLIED".equals(doc.status()) && !"REVIEWED".equals(doc.status())) {
            throw ApiException.conflict("CONTINUATION_STATE", "当前状态不可撤回: " + doc.status());
        }
        assertVersion(doc, req.expectedVersion());
        jdbc.update("UPDATE continuation_order SET status = 'WITHDRAWN', version = version + 1, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(Instant.now(clock)), continuationId);
        return view(continuationId);
    }

    /** 查询续票单据：含继承链接地线编号。 */
    @Transactional(readOnly = true)
    public ContinuationView get(long continuationId) {
        return view(continuationId);
    }

    /**
     * 责任接续：原票未归还实物逐条登记继承关系并把占用责任转移给续票新票；
     * 原借用编号与历史证据不改，续票只能合法拆除并归还本继承链实物。
     */
    private void inheritGroundWires(ContinuationRow doc, Instant now) {
        List<String> wires = jdbc.query(
                "SELECT wire_code FROM ground_wire_loan WHERE ticket_id = ? AND status = 'BORROWED' ORDER BY wire_code",
                (rs, n) -> rs.getString(1), doc.originalTicketId());
        for (String code : wires) {
            jdbc.update("INSERT INTO ground_wire_inheritance (continuation_id, wire_code, from_ticket_id,"
                            + " to_ticket_id, created_at) VALUES (?, ?, ?, ?, ?)",
                    doc.id(), code, doc.originalTicketId(), doc.newTicketId(), Timestamp.from(now));
            int updated = jdbc.update(
                    "UPDATE ground_wire SET occupied_by_ticket = ?, version = version + 1, updated_at = ?"
                            + " WHERE code = ? AND occupied_by_ticket = ?",
                    doc.newTicketId(), Timestamp.from(now), code, doc.originalTicketId());
            if (updated == 0) {
                throw ApiException.conflict("WIRE_STATE_CHANGED",
                        "接地线 " + code + " 占用责任已变化，责任接续失败，整事务回滚");
            }
        }
    }

    private void assertVersion(ContinuationRow doc, long expectedVersion) {
        if (doc.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "续票单据期望版本 " + expectedVersion + " 与当前版本 " + doc.version() + " 不符");
        }
    }

    private List<RemainingStep> remainingSteps(long ticketId) {
        return jdbc.query(
                "SELECT seq, operation_type, device_id, expected_state FROM switching_step"
                        + " WHERE ticket_id = ? AND status = 'PENDING' ORDER BY seq",
                (rs, n) -> new RemainingStep(rs.getInt("seq"), rs.getString("operation_type"),
                        rs.getString("device_id"), rs.getString("expected_state")),
                ticketId);
    }

    private static boolean stepsMatch(List<RemainingStep> remaining, List<StepSpec> declared) {
        for (int i = 0; i < remaining.size(); i++) {
            RemainingStep frozen = remaining.get(i);
            StepSpec step = declared.get(i);
            if (!frozen.operationType().equals(step.operationType())
                    || !frozen.deviceId().equals(step.deviceId())
                    || !frozen.expectedState().equals(step.expectedState())) {
                return false;
            }
        }
        return true;
    }

    private ContinuationRow mustLoadForUpdate(long continuationId) {
        return jdbc.query("SELECT * FROM continuation_order WHERE id = ? FOR UPDATE",
                        CONTINUATION_MAPPER, continuationId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CONTINUATION_NOT_FOUND", "续票单据不存在: " + continuationId));
    }

    private OriginalTicket mustLoadOriginal(long ticketId) {
        return jdbc.query(
                        "SELECT id, interval_id, status, version FROM switching_ticket WHERE id = ?",
                        (rs, n) -> new OriginalTicket(rs.getLong("id"), rs.getString("interval_id"),
                                rs.getString("status"), rs.getInt("version")),
                        ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "原操作票不存在: " + ticketId));
    }

    private ContinuationView view(long continuationId) {
        ContinuationRow doc = jdbc.query("SELECT * FROM continuation_order WHERE id = ?",
                        CONTINUATION_MAPPER, continuationId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CONTINUATION_NOT_FOUND", "续票单据不存在: " + continuationId));
        List<String> inherited = jdbc.query(
                "SELECT wire_code FROM ground_wire_inheritance WHERE continuation_id = ? ORDER BY wire_code",
                (rs, n) -> rs.getString(1), continuationId);
        return new ContinuationView(doc.id(), doc.originalTicketId(), doc.newTicketId(), doc.intervalId(),
                doc.status(), doc.reviewerA(), doc.reviewerB(), doc.originalTicketVersion(), doc.version(),
                inherited, doc.effectiveAt(), doc.createdAt(), doc.updatedAt());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 续票单据行。 */
    private record ContinuationRow(long id, String requestId, long originalTicketId, long newTicketId,
                                   String intervalId, String status, String reviewerA, String reviewerB,
                                   int originalTicketVersion, int version, Instant effectiveAt,
                                   Instant createdAt, Instant updatedAt) {
    }

    /** 原票引用（含版本）。 */
    private record OriginalTicket(long id, String intervalId, String status, int version) {
    }

    /** 冻结的剩余步骤。 */
    private record RemainingStep(int seq, String operationType, String deviceId, String expectedState) {
    }
}
