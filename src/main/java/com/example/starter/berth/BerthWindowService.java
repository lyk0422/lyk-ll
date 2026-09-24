package com.example.starter.berth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 靠泊窗口业务服务：幂等建窗、半开区间冲突校验、审批/生效/离泊/取消状态机、改期重校验。
 */
@Service
public class BerthWindowService {

    private final BerthWindowRepository repository;
    private final Clock clock;

    public BerthWindowService(BerthWindowRepository repository, Clock businessClock) {
        this.repository = repository;
        this.clock = businessClock;
    }

    /**
     * 创建申请。按 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不新增记录；
     * 并发同键依赖数据库唯一约束，只允许一次插入成功且响应一致。
     */
    public BerthWindowResponse create(CreateBerthWindowRequest request) {
        validateTimeRange(request.startTime(), request.endTime());
        String fingerprint = fingerprint(request.requestId(), request.vesselName(),
                String.valueOf(request.berthId()),
                request.startTime().toString(), request.endTime().toString(),
                request.draft().stripTrailingZeros().toPlainString(),
                String.valueOf(request.pilotsRequired()));
        var existing = repository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return matchFingerprint(existing.get(), fingerprint);
        }
        validateBusiness(request.berthId(), request.startTime(), request.endTime(), request.draft(),
                request.pilotsRequired(), -1L);
        Instant now = clock.instant();
        BerthWindow window = new BerthWindow(null, request.requestId(), fingerprint, request.vesselName(),
                request.berthId(), request.startTime(), request.endTime(), request.draft(),
                request.pilotsRequired(), BerthWindowStatus.PENDING, now, now);
        try {
            long id = repository.insert(window);
            return toResponse(repository.findById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(request.requestId(), fingerprint);
        }
    }

    /**
     * 查询单个窗口；到达起始时刻的已批准窗口惰性转为已生效。
     */
    @Transactional
    public BerthWindowResponse get(long id) {
        BerthWindow window = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("窗口不存在: " + id));
        window = activateIfDue(window);
        return toResponse(window);
    }

    /**
     * 审批通过：PENDING -> APPROVED，重新执行完整冲突校验并分配引航员。
     * 已批准/已生效重复审批返回幂等成功；终态窗口不得复活。
     */
    @Transactional
    public BerthWindowResponse approve(long id) {
        BerthWindow window = requireWindow(id);
        if (window.status() == BerthWindowStatus.APPROVED || window.status() == BerthWindowStatus.ACTIVE) {
            return toResponse(window);
        }
        if (window.status() != BerthWindowStatus.PENDING) {
            throw ApiException.conflict("窗口已处于终态，不得复活: " + window.status());
        }
        validateBerthConflict(window.berthId(), window.startTime(), window.endTime(), window.id());
        List<Long> available = availablePilots(window.startTime(), window.endTime(), window.id());
        if (available.size() < window.pilotsRequired()) {
            throw ApiException.conflict("该时段可用引航员不足");
        }
        int updated = repository.updateStatusIfIn(window.id(), BerthWindowStatus.APPROVED,
                List.of(BerthWindowStatus.PENDING), clock.instant());
        if (updated == 0) {
            throw ApiException.conflict("窗口状态已变更，请重试");
        }
        for (int i = 0; i < window.pilotsRequired(); i++) {
            repository.assignPilot(window.id(), available.get(i));
        }
        return toResponse(repository.findById(window.id()).orElseThrow());
    }

    /**
     * 驳回：PENDING -> REJECTED。重复驳回返回幂等成功；其他状态驳回视为迟到操作，返回 409。
     */
    @Transactional
    public BerthWindowResponse reject(long id) {
        BerthWindow window = requireWindow(id);
        if (window.status() == BerthWindowStatus.REJECTED) {
            return toResponse(window);
        }
        if (window.status() != BerthWindowStatus.PENDING) {
            throw ApiException.conflict("当前状态不允许驳回: " + window.status());
        }
        repository.updateStatusIfIn(window.id(), BerthWindowStatus.REJECTED,
                List.of(BerthWindowStatus.PENDING), clock.instant());
        return toResponse(repository.findById(window.id()).orElseThrow());
    }

    /**
     * 取消：PENDING/APPROVED -> CANCELLED，并释放引航员。重复取消返回幂等成功。
     */
    @Transactional
    public BerthWindowResponse cancel(long id) {
        BerthWindow window = requireWindow(id);
        if (window.status() == BerthWindowStatus.CANCELLED) {
            return toResponse(window);
        }
        if (window.status() != BerthWindowStatus.PENDING && window.status() != BerthWindowStatus.APPROVED) {
            throw ApiException.conflict("当前状态不允许取消: " + window.status());
        }
        repository.updateStatusIfIn(window.id(), BerthWindowStatus.CANCELLED,
                List.of(BerthWindowStatus.PENDING, BerthWindowStatus.APPROVED), clock.instant());
        repository.releasePilots(window.id());
        return toResponse(repository.findById(window.id()).orElseThrow());
    }

    /**
     * 登记离泊：APPROVED/ACTIVE -> COMPLETED，并释放引航员。重复登记返回幂等成功。
     */
    @Transactional
    public BerthWindowResponse complete(long id) {
        BerthWindow window = requireWindow(id);
        if (window.status() == BerthWindowStatus.COMPLETED) {
            return toResponse(window);
        }
        if (window.status() != BerthWindowStatus.APPROVED && window.status() != BerthWindowStatus.ACTIVE) {
            throw ApiException.conflict("当前状态不允许登记离泊: " + window.status());
        }
        repository.updateStatusIfIn(window.id(), BerthWindowStatus.COMPLETED,
                List.of(BerthWindowStatus.APPROVED, BerthWindowStatus.ACTIVE), clock.instant());
        repository.releasePilots(window.id());
        return toResponse(repository.findById(window.id()).orElseThrow());
    }

    /**
     * 已批准窗口改期：视为新申请，重新走完整冲突校验；与原窗口自身不算冲突。
     * 新申请按新 requestId 幂等；校验通过后原窗口转为已取消并释放引航员。
     */
    @Transactional
    public BerthWindowResponse reschedule(long id, RescheduleRequest request) {
        BerthWindow original = requireWindow(id);
        validateTimeRange(request.startTime(), request.endTime());
        String fingerprint = fingerprint(request.requestId(), original.vesselName(),
                String.valueOf(original.berthId()),
                request.startTime().toString(), request.endTime().toString(),
                original.draft().stripTrailingZeros().toPlainString(),
                String.valueOf(original.pilotsRequired()));
        var existing = repository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return matchFingerprint(existing.get(), fingerprint);
        }
        if (original.status() != BerthWindowStatus.APPROVED && original.status() != BerthWindowStatus.ACTIVE) {
            throw ApiException.conflict("仅已批准窗口可改期，当前状态: " + original.status());
        }
        validateBusiness(original.berthId(), request.startTime(), request.endTime(), original.draft(),
                original.pilotsRequired(), original.id());
        Instant now = clock.instant();
        BerthWindow window = new BerthWindow(null, request.requestId(), fingerprint, original.vesselName(),
                original.berthId(), request.startTime(), request.endTime(), original.draft(),
                original.pilotsRequired(), BerthWindowStatus.PENDING, now, now);
        final long newId;
        try {
            newId = repository.insert(window);
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(request.requestId(), fingerprint);
        }
        repository.updateStatusIfIn(original.id(), BerthWindowStatus.CANCELLED,
                List.of(BerthWindowStatus.APPROVED, BerthWindowStatus.ACTIVE), now);
        repository.releasePilots(original.id());
        return toResponse(repository.findById(newId).orElseThrow());
    }

    /**
     * 业务校验：泊位存在、限深、允许时段、泊位冲突、引航员可用数。excludeId 用于改期排除自身。
     */
    private void validateBusiness(long berthId, Instant start, Instant end, java.math.BigDecimal draft,
            int pilotsRequired, long excludeId) {
        var depthLimit = repository.findBerthDepthLimit(berthId)
                .orElseThrow(() -> ApiException.unprocessable("泊位不存在: " + berthId));
        if (draft.compareTo(depthLimit) > 0) {
            throw ApiException.unprocessable("吃水 " + draft + " 米超过泊位限深 " + depthLimit + " 米，拒绝");
        }
        if (!repository.isWithinAllowedWindow(start, end)) {
            throw ApiException.unprocessable("申请起止未完整落在每日允许靠泊时段内");
        }
        validateBerthConflict(berthId, start, end, excludeId);
        int available = availablePilots(start, end, excludeId).size();
        if (available < pilotsRequired) {
            throw ApiException.conflict("该时段可用引航员不足：需要 " + pilotsRequired + "，可用 " + available);
        }
    }

    private void validateBerthConflict(long berthId, Instant start, Instant end, long excludeId) {
        List<BerthWindow> blocking = repository.findBlockingOnBerth(berthId, start, end, excludeId);
        if (!blocking.isEmpty()) {
            throw ApiException.conflict("泊位时段冲突，与窗口 " + blocking.get(0).id() + " 重叠");
        }
    }

    private List<Long> availablePilots(Instant start, Instant end, long excludeWindowId) {
        Set<Long> busy = new HashSet<>(repository.findBusyPilotIds(start, end, excludeWindowId));
        return repository.findAllPilotIds().stream().filter(pid -> !busy.contains(pid)).toList();
    }

    private BerthWindow requireWindow(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("窗口不存在: " + id));
    }

    private BerthWindow activateIfDue(BerthWindow window) {
        if (window.status() == BerthWindowStatus.APPROVED && !clock.instant().isBefore(window.startTime())) {
            repository.updateStatusIfIn(window.id(), BerthWindowStatus.ACTIVE,
                    List.of(BerthWindowStatus.APPROVED), clock.instant());
            return repository.findById(window.id()).orElseThrow();
        }
        return window;
    }

    private BerthWindowResponse resolveDuplicate(String requestId, String fingerprint) {
        BerthWindow existing = repository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.conflict("幂等键冲突且无法读取已有记录: " + requestId));
        return matchFingerprint(existing, fingerprint);
    }

    private BerthWindowResponse matchFingerprint(BerthWindow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("相同 requestId 携带不同业务参数，拒绝: " + existing.requestId());
        }
        return toResponse(existing);
    }

    private void validateTimeRange(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw ApiException.unprocessable("起始时刻必须早于结束时刻");
        }
    }

    /**
     * 幂等指纹：覆盖 requestId 与全部业务字段；逐字段长度前缀序列化，消除拼接歧义。
     */
    static String fingerprint(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            sb.append(value.length()).append(':').append(value).append(';');
        }
        return sb.toString();
    }

    private BerthWindowResponse toResponse(BerthWindow window) {
        return new BerthWindowResponse(window.id(), window.requestId(), window.vesselName(), window.berthId(),
                window.startTime(), window.endTime(), window.draft(), window.pilotsRequired(), window.status(),
                repository.findPilotIdsOfWindow(window.id()), window.createdAt(), window.updatedAt());
    }
}
