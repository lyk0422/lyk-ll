package com.example.starter.berth.service;

import com.example.starter.berth.domain.BerthWindow;
import com.example.starter.berth.domain.WindowStatus;
import com.example.starter.berth.dto.CreateWindowRequest;
import com.example.starter.berth.dto.RescheduleRequest;
import com.example.starter.berth.dto.WindowResponse;
import com.example.starter.berth.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

/**
 * 靠泊窗口核心服务。
 *
 * <p>冲突判定：窗口按半开区间 [start, end) 处理，端点相接不算冲突；同一泊位同一时段
 * 只允许一个 PENDING/APPROVED/ACTIVE 窗口；每名引航员同一时段最多服务一艘船（仅统计
 * 上述三种占用状态的分配，终态释放）。申请创建在事务内对泊位行加锁并完成全部校验与写入，
 * 配合 request_id 唯一约束保证并发同键只成功一次。</p>
 */
@Service
public class BerthWindowService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate requiresNewTx;

    private final RowMapper<BerthWindow> rowMapper = (rs, n) -> new BerthWindow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("vessel_name"),
            rs.getString("berth_code"),
            rs.getTimestamp("start_at").toInstant(),
            rs.getTimestamp("end_at").toInstant(),
            rs.getBigDecimal("draft_m"),
            rs.getInt("pilots_required"),
            WindowStatus.valueOf(rs.getString("status")),
            rs.getObject("supersedes_id", Long.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public BerthWindowService(JdbcClient jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.requiresNewTx = new TransactionTemplate(transactionManager,
                new org.springframework.transaction.support.DefaultTransactionDefinition(PROPAGATION_REQUIRES_NEW));
    }

    /**
     * 创建靠泊窗口申请（按 requestId 幂等）。同键同参返回首次结果，同键异参返回 409。
     */
    public WindowResponse create(CreateWindowRequest request) {
        validateTimeRange(request);
        String fingerprint = RequestFingerprint.of(request);

        Optional<BerthWindow> existing = findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        try {
            return tx.execute(status -> doCreate(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束只放行一个事务，其余在此读取胜出记录并按指纹决定重放或 409
            BerthWindow winner = requiresNewTx.execute(status -> findByRequestId(request.requestId())
                    .orElseThrow(() -> e));
            return replayOrConflict(winner, fingerprint);
        }
    }

    /**
     * 按 id 查询窗口。
     */
    public WindowResponse get(long id) {
        return toResponse(findById(id).orElseThrow(() -> notFound(id)));
    }

    /**
     * 审批通过：PENDING → APPROVED（若已到达起始时刻则直接 ACTIVE）。重复批准幂等返回；
     * 窗口已结束时迟到审批返回 409；终态不得复活。
     */
    public WindowResponse approve(long id) {
        return tx.execute(status -> {
            BerthWindow window = lockById(id);
            switch (window.status()) {
                case APPROVED, ACTIVE -> {
                    return toResponse(window);
                }
                case PENDING -> {
                    Instant now = clock.instant();
                    if (!now.isBefore(window.endAt())) {
                        throw new ApiException(HttpStatus.CONFLICT, "TOO_LATE",
                                "窗口已结束，迟到审批无效");
                    }
                    ensureNoBerthConflict(window.berthCode(), window.startAt(), window.endAt(), window.id());
                    WindowStatus next = now.isBefore(window.startAt()) ? WindowStatus.APPROVED : WindowStatus.ACTIVE;
                    updateStatus(id, next, now);
                    return toResponse(lockById(id));
                }
                default -> throw illegalState("批准", window);
            }
        });
    }

    /**
     * 审批驳回：PENDING/APPROVED → REJECTED 并释放引航员。重复驳回幂等返回。
     */
    public WindowResponse reject(long id) {
        return tx.execute(status -> {
            BerthWindow window = lockById(id);
            switch (window.status()) {
                case REJECTED -> {
                    return toResponse(window);
                }
                case PENDING, APPROVED -> {
                    releasePilots(id);
                    updateStatus(id, WindowStatus.REJECTED, clock.instant());
                    return toResponse(lockById(id));
                }
                default -> throw illegalState("驳回", window);
            }
        });
    }

    /**
     * 申请人取消：PENDING/APPROVED → CANCELLED 并释放引航员。重复取消幂等返回。
     */
    public WindowResponse cancel(long id) {
        return tx.execute(status -> {
            BerthWindow window = lockById(id);
            switch (window.status()) {
                case CANCELLED -> {
                    return toResponse(window);
                }
                case PENDING, APPROVED -> {
                    releasePilots(id);
                    updateStatus(id, WindowStatus.CANCELLED, clock.instant());
                    return toResponse(lockById(id));
                }
                default -> throw illegalState("取消", window);
            }
        });
    }

    /**
     * 登记离泊：ACTIVE → COMPLETED 并释放引航员；已批准且已到达起始时刻的窗口视为已生效，
     * 可直接登记离泊。重复离泊幂等返回。
     */
    public WindowResponse depart(long id) {
        return tx.execute(status -> {
            BerthWindow window = lockById(id);
            switch (window.status()) {
                case COMPLETED -> {
                    return toResponse(window);
                }
                case ACTIVE -> {
                    releasePilots(id);
                    updateStatus(id, WindowStatus.COMPLETED, clock.instant());
                    return toResponse(lockById(id));
                }
                case APPROVED -> {
                    if (clock.instant().isBefore(window.startAt())) {
                        throw new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE",
                                "窗口尚未生效，不能登记离泊");
                    }
                    releasePilots(id);
                    updateStatus(id, WindowStatus.COMPLETED, clock.instant());
                    return toResponse(lockById(id));
                }
                default -> throw illegalState("登记离泊", window);
            }
        });
    }

    /**
     * 已批准/已生效窗口改期：继承原窗口业务字段，以新起止时刻发起新申请（重新走完整冲突
     * 校验，与原窗口自身不算冲突）；新申请成功后原窗口转为 SUPERSEDED 并释放其引航员。
     */
    public WindowResponse reschedule(long id, RescheduleRequest request) {
        BerthWindow original = findById(id).orElseThrow(() -> notFound(id));
        CreateWindowRequest createRequest = new CreateWindowRequest(
                request.requestId(),
                original.vesselName(),
                original.berthCode(),
                request.startAt(),
                request.endAt(),
                original.draftMeters(),
                original.pilotsRequired(),
                original.id());
        return create(createRequest);
    }

    /**
     * 将到达起始时刻的已批准窗口批量转为已生效。
     *
     * @return 转换的窗口数量
     */
    public int activateDueWindows() {
        Instant now = clock.instant();
        Integer updated = tx.execute(status -> jdbc.sql(
                        "UPDATE berth_window SET status = 'ACTIVE', updated_at = ? "
                                + "WHERE status = 'APPROVED' AND start_at <= ?")
                .params(Timestamp.from(now), Timestamp.from(now))
                .update());
        return updated == null ? 0 : updated;
    }

    private WindowResponse doCreate(CreateWindowRequest request, String fingerprint) {
        Instant now = clock.instant();

        // 先锁定泊位行，串行化同一泊位的并发申请
        var berth = jdbc.sql("SELECT max_depth_m FROM berth WHERE berth_code = ? FOR UPDATE")
                .param(request.berthCode())
                .query((rs, n) -> rs.getBigDecimal("max_depth_m"))
                .optional();
        if (berth.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BERTH_NOT_FOUND",
                    "泊位不存在: " + request.berthCode());
        }

        // 持锁后复查：并发同键在此重放首次结果，保证响应一致
        Optional<BerthWindow> raced = findByRequestId(request.requestId());
        if (raced.isPresent()) {
            return replayOrConflict(raced.get(), fingerprint);
        }

        if (request.supersedesId() != null) {
            BerthWindow superseded = lockById(request.supersedesId());
            if (superseded.status() != WindowStatus.APPROVED && superseded.status() != WindowStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "SUPERSEDE_NOT_ALLOWED",
                        "仅已批准或已生效窗口可改期，原窗口当前状态: " + superseded.status());
            }
        }

        if (request.draftMeters().compareTo(berth.get()) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DRAFT_EXCEEDS_LIMIT",
                    "吃水 " + request.draftMeters() + " 米超过泊位限深 " + berth.get() + " 米，直接拒绝");
        }

        Integer allowed = jdbc.sql("SELECT COUNT(*) FROM allowed_window "
                        + "WHERE berth_code = ? AND start_at <= ? AND end_at >= ?")
                .params(request.berthCode(), Timestamp.from(request.startAt()), Timestamp.from(request.endAt()))
                .query(Integer.class)
                .single();
        if (allowed == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_ALLOWED_WINDOW",
                    "申请时段未完整落在每日允许靠泊时段内");
        }

        ensureNoBerthConflict(request.berthCode(), request.startAt(), request.endAt(), request.supersedesId());

        List<String> pilots = selectAvailablePilots(request);
        if (pilots.size() < request.pilotsRequired()) {
            throw new ApiException(HttpStatus.CONFLICT, "PILOT_UNAVAILABLE",
                    "该时段可用引航员不足：需要 " + request.pilotsRequired() + " 名，可用 " + pilots.size() + " 名");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO berth_window (request_id, fingerprint, vessel_name, berth_code, start_at, end_at, "
                        + "draft_m, pilots_required, status, supersedes_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)")
                .params(request.requestId(), fingerprint, request.vesselName(), request.berthCode(),
                        Timestamp.from(request.startAt()), Timestamp.from(request.endAt()),
                        request.draftMeters(), request.pilotsRequired(), request.supersedesId(),
                        Timestamp.from(now), Timestamp.from(now))
                .update(keyHolder);
        long id = keyHolder.getKey().longValue();

        for (String pilot : pilots) {
            jdbc.sql("INSERT INTO pilot_assignment (window_id, pilot_code) VALUES (?, ?)")
                    .params(id, pilot)
                    .update();
        }

        if (request.supersedesId() != null) {
            releasePilots(request.supersedesId());
            updateStatus(request.supersedesId(), WindowStatus.SUPERSEDED, now);
        }
        return toResponse(lockById(id));
    }

    private void validateTimeRange(CreateWindowRequest request) {
        if (!request.startAt().isBefore(request.endAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                    "预计靠泊开始时刻必须早于结束时刻");
        }
        if (!request.endAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                    "窗口结束时刻必须晚于当前时刻，迟到申请无效");
        }
    }

    private void ensureNoBerthConflict(String berthCode, Instant startAt, Instant endAt, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM berth_window WHERE berth_code = ? "
                + "AND status IN ('PENDING', 'APPROVED', 'ACTIVE') "
                + "AND start_at < ? AND end_at > ? "
                + (excludeId != null ? "AND id <> " + excludeId : "");
        Integer conflicts = jdbc.sql(sql)
                .params(berthCode, Timestamp.from(endAt), Timestamp.from(startAt))
                .query(Integer.class)
                .single();
        if (conflicts > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BERTH_WINDOW_CONFLICT",
                    "该泊位在所申请时段已存在有效靠泊窗口");
        }
    }

    private List<String> selectAvailablePilots(CreateWindowRequest request) {
        if (request.pilotsRequired() == 0) {
            return List.of();
        }
        String sql = "SELECT p.pilot_code FROM pilot p WHERE NOT EXISTS ("
                + "SELECT 1 FROM pilot_assignment pa JOIN berth_window bw ON bw.id = pa.window_id "
                + "WHERE pa.pilot_code = p.pilot_code "
                + "AND bw.status IN ('PENDING', 'APPROVED', 'ACTIVE') "
                + "AND bw.start_at < ? AND bw.end_at > ? "
                + (request.supersedesId() != null ? "AND bw.id <> " + request.supersedesId() : "")
                + ") ORDER BY p.pilot_code LIMIT ?";
        return jdbc.sql(sql)
                .params(Timestamp.from(request.endAt()), Timestamp.from(request.startAt()), request.pilotsRequired())
                .query(String.class)
                .list();
    }

    private WindowResponse replayOrConflict(BerthWindow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务参数不一致");
        }
        return toResponse(existing);
    }

    private BerthWindow lockById(long id) {
        return jdbc.sql("SELECT * FROM berth_window WHERE id = ? FOR UPDATE")
                .param(id)
                .query(rowMapper)
                .optional()
                .orElseThrow(() -> notFound(id));
    }

    private Optional<BerthWindow> findById(long id) {
        return jdbc.sql("SELECT * FROM berth_window WHERE id = ?")
                .param(id)
                .query(rowMapper)
                .optional();
    }

    private Optional<BerthWindow> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM berth_window WHERE request_id = ?")
                .param(requestId)
                .query(rowMapper)
                .optional();
    }

    private void updateStatus(long id, WindowStatus status, Instant now) {
        jdbc.sql("UPDATE berth_window SET status = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), Timestamp.from(now), id)
                .update();
    }

    private void releasePilots(long windowId) {
        jdbc.sql("DELETE FROM pilot_assignment WHERE window_id = ?")
                .param(windowId)
                .update();
    }

    private WindowResponse toResponse(BerthWindow window) {
        List<String> pilots = jdbc.sql("SELECT pilot_code FROM pilot_assignment WHERE window_id = ? ORDER BY pilot_code")
                .param(window.id())
                .query(String.class)
                .list();
        return new WindowResponse(
                window.id(),
                window.requestId(),
                window.vesselName(),
                window.berthCode(),
                window.startAt(),
                window.endAt(),
                window.draftMeters(),
                window.pilotsRequired(),
                pilots,
                window.status(),
                window.supersedesId(),
                window.createdAt(),
                window.updatedAt());
    }

    private ApiException notFound(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND", "靠泊窗口不存在: " + id);
    }

    private ApiException illegalState(String operation, BerthWindow window) {
        return new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE",
                "当前状态 " + window.status() + " 不允许" + operation);
    }
}
