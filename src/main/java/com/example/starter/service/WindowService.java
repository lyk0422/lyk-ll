package com.example.starter.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.example.starter.domain.BerthWindow;
import com.example.starter.domain.WindowStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.repo.WindowRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 靠泊窗口核心业务：幂等建窗、冲突校验、审批状态机、引航员分配。
 *
 * <p>冲突按半开区间 [start, end) 判定：端点相接不算冲突，任意内部重叠即冲突。
 * 同一泊位同一时段只允许一个已批准/已生效窗口；每名引航员同一时段最多服务一艘船。</p>
 */
@Service
public class WindowService {

    /**
     * 建窗请求参数（业务字段全集，参与指纹计算）。
     */
    public record CreateCommand(
            String requestId,
            String vesselName,
            long berthId,
            Instant startAt,
            Instant endAt,
            BigDecimal draftM,
            int pilotsRequired) {
    }

    /**
     * 建窗结果：window 为窗口当前状态；created 表示是否本次新建（幂等重放为 false）。
     */
    public record CreateResult(BerthWindow window, boolean created) {
    }

    private final WindowRepository repo;
    private final Clock clock;

    public WindowService(WindowRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * 计算请求指纹：定长前缀拼接消除字段边界歧义，再取 SHA-256 十六进制。
     */
    public static String fingerprint(CreateCommand cmd) {
        String canonical = lengthPrefixed(cmd.requestId())
                + lengthPrefixed(cmd.vesselName())
                + lengthPrefixed(Long.toString(cmd.berthId()))
                + lengthPrefixed(Long.toString(cmd.startAt().getEpochSecond()))
                + lengthPrefixed(Long.toString(cmd.endAt().getEpochSecond()))
                + lengthPrefixed(cmd.draftM().stripTrailingZeros().toPlainString())
                + lengthPrefixed(Integer.toString(cmd.pilotsRequired()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String lengthPrefixed(String field) {
        return field.length() + ":" + field;
    }

    /**
     * 提交靠泊窗口申请。幂等：同键同参返回首次结果，同键异参返回 409。
     * 吃水超限直接拒绝（422）且不产生记录；不在允许时段内拒绝（422）；
     * 与已批准/已生效窗口内部重叠拒绝（409）。
     */
    @Transactional
    public CreateResult create(CreateCommand cmd) {
        validateTimeRange(cmd.startAt(), cmd.endAt());

        // 幂等优先：同键重放直接返回首次结果，不再重复校验当前冲突状态。
        var existing = repo.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            BerthWindow found = existing.get();
            if (!found.fingerprint().equals(fingerprint(cmd))) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENT_KEY_CONFLICT",
                        "requestId 已存在且业务参数不一致");
            }
            return new CreateResult(withPilots(found), false);
        }

        double maxDraft = repo.findBerthMaxDraft(cmd.berthId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BERTH_NOT_FOUND",
                        "泊位不存在: " + cmd.berthId()));
        if (cmd.draftM().doubleValue() > maxDraft) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "DRAFT_EXCEEDS_LIMIT",
                    "吃水超过泊位限深，拒绝且不产生记录");
        }
        if (!repo.isWithinAllowedWindow(cmd.startAt(), cmd.endAt())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_ALLOWED_WINDOW",
                    "申请起止未完整落在允许靠泊时段内");
        }

        repo.lockBerth(cmd.berthId());
        if (!repo.findActiveOverlaps(cmd.berthId(), cmd.startAt(), cmd.endAt(), 0L).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "BERTH_WINDOW_CONFLICT",
                    "同一泊位同一时段已存在已批准或已生效窗口");
        }

        Instant now = clock.instant();
        BerthWindow window = new BerthWindow(0L, cmd.requestId(), fingerprint(cmd),
                cmd.vesselName(), cmd.berthId(), cmd.startAt(), cmd.endAt(),
                cmd.draftM().doubleValue(), cmd.pilotsRequired(), WindowStatus.PENDING,
                List.of(), now, now);
        try {
            long id = repo.insert(window);
            return new CreateResult(withPilots(repo.findById(id).orElseThrow()), true);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束保证仅一次插入成功，失败方读取首次结果。
            BerthWindow found = repo.findByRequestId(cmd.requestId()).orElseThrow();
            if (!found.fingerprint().equals(fingerprint(cmd))) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENT_KEY_CONFLICT",
                        "requestId 已存在且业务参数不一致");
            }
            return new CreateResult(withPilots(found), false);
        }
    }

    /**
     * 审批通过：重校验泊位冲突并分配引航员。重复审批幂等成功；终态不得复活。
     */
    @Transactional
    public BerthWindow approve(long id) {
        BerthWindow window = requireWindow(id);
        repo.lockBerth(window.berthId());
        repo.lockPilots();
        window = refresh(requireWindow(id));
        switch (window.status()) {
            case APPROVED, ACTIVE -> {
                return withPilots(window);
            }
            case PENDING -> {
                if (!repo.findActiveOverlaps(window.berthId(), window.startAt(), window.endAt(), id)
                        .isEmpty()) {
                    throw new BusinessException(HttpStatus.CONFLICT, "BERTH_WINDOW_CONFLICT",
                            "同一泊位同一时段已存在已批准或已生效窗口");
                }
                List<Long> available = repo.findAvailablePilotIds(
                        window.startAt(), window.endAt(), id);
                if (available.size() < window.pilotsRequired()) {
                    throw new BusinessException(HttpStatus.CONFLICT, "PILOT_SHORTAGE",
                            "该时段可用引航员不足");
                }
                repo.replacePilotAssignments(id,
                        available.subList(0, window.pilotsRequired()));
                repo.updateStatus(id, WindowStatus.APPROVED, clock.instant());
                return withPilots(repo.findById(id).orElseThrow());
            }
            default -> throw new BusinessException(HttpStatus.CONFLICT, "WINDOW_TERMINATED",
                    "终态窗口不得复活: " + window.status());
        }
    }

    /**
     * 审批驳回：仅待审批可驳回；重复驳回幂等成功；终态不得复活。
     */
    @Transactional
    public BerthWindow reject(long id) {
        BerthWindow window = refresh(requireWindow(id));
        switch (window.status()) {
            case REJECTED -> {
                return withPilots(window);
            }
            case PENDING -> {
                repo.updateStatus(id, WindowStatus.REJECTED, clock.instant());
                return withPilots(repo.findById(id).orElseThrow());
            }
            default -> throw new BusinessException(HttpStatus.CONFLICT, "WINDOW_STATE_CONFLICT",
                    "当前状态不允许驳回: " + window.status());
        }
    }

    /**
     * 申请人取消：待审批/已批准可取消并释放引航员；重复取消幂等成功；终态不得复活。
     */
    @Transactional
    public BerthWindow cancel(long id) {
        BerthWindow window = refresh(requireWindow(id));
        switch (window.status()) {
            case CANCELLED -> {
                return withPilots(window);
            }
            case PENDING, APPROVED -> {
                repo.clearPilotAssignments(id);
                repo.updateStatus(id, WindowStatus.CANCELLED, clock.instant());
                return withPilots(repo.findById(id).orElseThrow());
            }
            default -> throw new BusinessException(HttpStatus.CONFLICT, "WINDOW_STATE_CONFLICT",
                    "当前状态不允许取消: " + window.status());
        }
    }

    /**
     * 登记离泊：已生效窗口转为已完成并释放引航员；重复登记幂等成功；终态不得复活。
     */
    @Transactional
    public BerthWindow complete(long id) {
        BerthWindow window = refresh(requireWindow(id));
        switch (window.status()) {
            case COMPLETED -> {
                return withPilots(window);
            }
            case ACTIVE -> {
                repo.clearPilotAssignments(id);
                repo.updateStatus(id, WindowStatus.COMPLETED, clock.instant());
                return withPilots(repo.findById(id).orElseThrow());
            }
            default -> throw new BusinessException(HttpStatus.CONFLICT, "WINDOW_STATE_CONFLICT",
                    "当前状态不允许登记离泊: " + window.status());
        }
    }

    /**
     * 已批准窗口改期：视为新申请，重新走完整冲突校验（与原窗口自身不算冲突），
     * 并重新分配引航员；状态保持已批准。
     */
    @Transactional
    public BerthWindow reschedule(long id, Instant newStart, Instant newEnd) {
        validateTimeRange(newStart, newEnd);
        BerthWindow window = requireWindow(id);
        repo.lockBerth(window.berthId());
        repo.lockPilots();
        window = refresh(requireWindow(id));
        if (window.status() != WindowStatus.APPROVED) {
            throw new BusinessException(HttpStatus.CONFLICT, "WINDOW_STATE_CONFLICT",
                    "仅已批准窗口允许改期: " + window.status());
        }
        if (!repo.isWithinAllowedWindow(newStart, newEnd)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_ALLOWED_WINDOW",
                    "改期起止未完整落在允许靠泊时段内");
        }
        if (!repo.findActiveOverlaps(window.berthId(), newStart, newEnd, id).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "BERTH_WINDOW_CONFLICT",
                    "改期后与其他已批准或已生效窗口冲突");
        }
        List<Long> available = repo.findAvailablePilotIds(newStart, newEnd, id);
        if (available.size() < window.pilotsRequired()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PILOT_SHORTAGE",
                    "改期后时段可用引航员不足");
        }
        repo.replacePilotAssignments(id, available.subList(0, window.pilotsRequired()));
        repo.updateSchedule(id, newStart, newEnd, clock.instant());
        return withPilots(repo.findById(id).orElseThrow());
    }

    /**
     * 查询窗口（应用惰性生效：已批准且到达起始时刻自动转为已生效）。
     */
    @Transactional
    public BerthWindow get(long id) {
        return withPilots(refresh(requireWindow(id)));
    }

    private BerthWindow requireWindow(long id) {
        return repo.findById(id).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND", "窗口不存在: " + id));
    }

    /**
     * 惰性状态推进：已批准窗口到达起始时刻自动转为已生效。
     */
    private BerthWindow refresh(BerthWindow window) {
        if (window.status() == WindowStatus.APPROVED
                && !clock.instant().isBefore(window.startAt())) {
            repo.updateStatus(window.id(), WindowStatus.ACTIVE, clock.instant());
            return repo.findById(window.id()).orElseThrow();
        }
        return window;
    }

    private BerthWindow withPilots(BerthWindow window) {
        return new BerthWindow(window.id(), window.requestId(), window.fingerprint(),
                window.vesselName(), window.berthId(), window.startAt(), window.endAt(),
                window.draftM(), window.pilotsRequired(), window.status(),
                repo.findAssignedPilotIds(window.id()), window.createdAt(), window.updatedAt());
    }

    private static void validateTimeRange(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE",
                    "结束时刻必须晚于开始时刻");
        }
    }
}
