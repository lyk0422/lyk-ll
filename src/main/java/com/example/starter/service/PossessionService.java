package com.example.starter.service;

import com.example.starter.api.dto.ConflictView;
import com.example.starter.api.dto.CreateRequestRequest;
import com.example.starter.api.dto.OperationRequest;
import com.example.starter.api.dto.OperationResultView;
import com.example.starter.api.dto.RequestView;
import com.example.starter.api.dto.SnapshotView;
import com.example.starter.domain.OperationType;
import com.example.starter.domain.PossessionRequest;
import com.example.starter.domain.RequestStatus;
import com.example.starter.exception.BusinessRuleException;
import com.example.starter.exception.ConflictException;
import com.example.starter.exception.NotFoundException;
import com.example.starter.repository.PossessionRepository;
import com.example.starter.repository.PossessionRepository.ConflictRow;
import com.example.starter.repository.PossessionRepository.LedgerEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 铁路检修封锁窗审批与冲突协调服务。
 *
 * <p>核心规则：
 * <ul>
 *     <li>占用窗为申请窗加前后安全缓冲的半开区间 {@code [start-pre, end+post)}，端点相接不冲突；</li>
 *     <li>审批对全部区段原子完成：与已批准/已激活窗口正长度重叠则整笔拒绝并返回按区段、时间排序的冲突；</li>
 *     <li>审批事务先按字典序锁定区段行，无共同区段可并行，共同区段并发审批仅一个成功；</li>
 *     <li>紧急申请（priority≥{@link #EMERGENCY_PRIORITY}）可在同事务内抢占全部与之冲突、
 *         尚未激活且优先级严格更低的窗口；出现已激活、同级/更高优先级或只能部分抢占时整笔回滚拒绝；</li>
 *     <li>激活/完成/取消携带 requestId、expectedVersion、业务时间，指纹覆盖全部参数；
 *         同参重复返回首次结果且不追加事件，异参复用请求键返回 409；</li>
 *     <li>计划快照一经发布不可变。</li>
 * </ul>
 */
@Service
public class PossessionService {

    /** 紧急（可抢占）优先级阈值；普通申请使用 10。 */
    public static final int EMERGENCY_PRIORITY = 20;

    private final PossessionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PossessionService(PossessionRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 申请
    // ------------------------------------------------------------------

    /**
     * 提交封锁申请（状态 PENDING，版本 1）。
     */
    @Transactional
    public RequestView create(CreateRequestRequest request) {
        if (!request.end().isAfter(request.start())) {
            throw new BusinessRuleException("时间窗必须为正长度半开区间 [start,end)");
        }
        List<String> sections = request.sectionIds().stream().distinct().toList();
        if (sections.size() != request.sectionIds().size()) {
            throw new BusinessRuleException("区段列表不得包含重复区段");
        }
        Instant bufferedStart;
        Instant bufferedEnd;
        try {
            bufferedStart = request.start().minusSeconds(request.preBufferSeconds());
            bufferedEnd = request.end().plusSeconds(request.postBufferSeconds());
        } catch (ArithmeticException e) {
            throw new BusinessRuleException("安全缓冲导致时间越界");
        }

        PossessionRequest domain = new PossessionRequest(
                request.requestId(),
                sections,
                request.start(),
                request.end(),
                bufferedStart,
                bufferedEnd,
                request.preBufferSeconds(),
                request.postBufferSeconds(),
                request.priority(),
                RequestStatus.PENDING,
                1,
                request.businessTime(),
                Instant.now(clock));
        try {
            repository.insertRequest(domain, sections);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("requestId 已存在: " + request.requestId(), List.of());
        }
        return toView(domain);
    }

    /**
     * 查询申请详情。
     */
    public RequestView get(String requestId) {
        return toView(loadRequest(requestId));
    }

    /**
     * 冲突查询：返回当前与该申请占用窗正长度重叠的已批准/已激活窗口明细。
     */
    @Transactional(readOnly = true)
    public List<ConflictView> checkConflicts(String requestId) {
        PossessionRequest request = loadRequest(requestId);
        List<ConflictRow> rows = repository.findActiveConflicts(
                request.sectionIds(), request.bufferedStart(), request.bufferedEnd(), requestId);
        return rows.stream().map(row -> toConflictView(row, request)).toList();
    }

    // ------------------------------------------------------------------
    // 状态操作
    // ------------------------------------------------------------------

    /**
     * 审批：原子完成全部区段；紧急申请满足条件时同事务抢占。
     */
    @Transactional
    public OperationResultView approve(String requestId, OperationRequest request) {
        PossessionRequest pr = loadRequest(requestId);
        OperationResultView replayed = checkReplay(pr, OperationType.APPROVE, request);
        if (replayed != null) {
            return replayed;
        }
        ensureVersion(pr, request);
        if (pr.status() != RequestStatus.PENDING) {
            throw new BusinessRuleException("仅 PENDING 申请可审批，当前状态: " + pr.status());
        }

        // 按字典序锁定全部区段，串行化所有触及共同区段的审批/抢占
        repository.lockSections(pr.sectionIds());

        List<ConflictRow> conflicts = repository.findActiveConflicts(
                pr.sectionIds(), pr.bufferedStart(), pr.bufferedEnd(), pr.id());

        List<ConflictView> preemptedConflictViews = new ArrayList<>();
        List<String> preemptedIds = new ArrayList<>();

        if (!conflicts.isEmpty()) {
            if (pr.priority() < EMERGENCY_PRIORITY) {
                throwConflict(pr, conflicts, "存在时间窗冲突，普通申请不可抢占");
            }
            // 紧急抢占前置校验：已激活 / 同级或更高优先级 → 整笔拒绝
            for (ConflictRow row : conflicts) {
                if (row.status() == RequestStatus.ACTIVE) {
                    throwConflict(pr, conflicts, "存在已激活窗口，紧急申请不可抢占");
                }
                if (row.priority() >= pr.priority()) {
                    throwConflict(pr, conflicts, "存在同级或更高优先级窗口，不可抢占");
                }
            }
            // 全部冲突窗口必须可整体抢占（均为未激活的 APPROVED 低优先级窗口）
            Map<String, ConflictRow> distinct = new LinkedHashMap<>();
            for (ConflictRow row : conflicts) {
                distinct.putIfAbsent(row.requestId(), row);
            }
            for (ConflictRow victim : distinct.values()) {
                if (victim.status() != RequestStatus.APPROVED) {
                    throwConflict(pr, conflicts, "存在不可抢占窗口，仅能部分抢占");
                }
            }
            // 执行抢占：任一窗口因并发变化无法抢占 → 整笔回滚
            for (ConflictRow victim : distinct.values()) {
                int updated = repository.compareAndUpdateStatus(
                        victim.requestId(), RequestStatus.APPROVED, RequestStatus.PREEMPTED,
                        victim.version(), victim.version() + 1);
                if (updated == 0) {
                    throw new ConflictException("冲突窗口状态已并发变化，紧急抢占整笔回滚",
                            toConflictViews(pr, conflicts));
                }
                repository.insertEvent(victim.requestId(), "PREEMPTED",
                        RequestStatus.APPROVED, RequestStatus.PREEMPTED,
                        request.businessTime(), victim.version() + 1,
                        "preemptedBy=" + pr.id());
                preemptedIds.add(victim.requestId());
            }
            preemptedConflictViews = toConflictViews(pr, conflicts);
        }

        int newVersion = pr.version() + 1;
        casStatus(pr, RequestStatus.PENDING, RequestStatus.APPROVED, request.businessTime(), newVersion,
                "APPROVED",
                preemptedIds.isEmpty() ? null : "preempted=" + String.join(",", preemptedIds));

        OperationResultView result = new OperationResultView(
                pr.id(), OperationType.APPROVE.name(), RequestStatus.APPROVED.name(), newVersion,
                false, List.copyOf(preemptedConflictViews), List.copyOf(preemptedIds), request.businessTime());
        recordSuccess(pr, OperationType.APPROVE, request, result);
        return result;
    }

    /**
     * 激活：仅 APPROVED 可激活。
     */
    @Transactional
    public OperationResultView activate(String requestId, OperationRequest request) {
        return simpleTransition(requestId, request, OperationType.ACTIVATE,
                RequestStatus.APPROVED, RequestStatus.ACTIVE, "ACTIVATED");
    }

    /**
     * 完成：仅 ACTIVE 可完成（终态）。
     */
    @Transactional
    public OperationResultView complete(String requestId, OperationRequest request) {
        return simpleTransition(requestId, request, OperationType.COMPLETE,
                RequestStatus.ACTIVE, RequestStatus.COMPLETED, "COMPLETED");
    }

    /**
     * 取消：PENDING 或 APPROVED 可取消（终态）；已激活不可取消。
     */
    @Transactional
    public OperationResultView cancel(String requestId, OperationRequest request) {
        PossessionRequest pr = loadRequest(requestId);
        OperationResultView replayed = checkReplay(pr, OperationType.CANCEL, request);
        if (replayed != null) {
            return replayed;
        }
        ensureVersion(pr, request);
        if (pr.status() != RequestStatus.PENDING && pr.status() != RequestStatus.APPROVED) {
            throw new BusinessRuleException("仅 PENDING/APPROVED 申请可取消，当前状态: " + pr.status());
        }
        int newVersion = pr.version() + 1;
        casStatus(pr, pr.status(), RequestStatus.CANCELLED, request.businessTime(), newVersion,
                "CANCELLED", null);
        OperationResultView result = new OperationResultView(
                pr.id(), OperationType.CANCEL.name(), RequestStatus.CANCELLED.name(), newVersion,
                false, List.of(), List.of(), request.businessTime());
        recordSuccess(pr, OperationType.CANCEL, request, result);
        return result;
    }

    private OperationResultView simpleTransition(String requestId, OperationRequest request,
                                                 OperationType operation,
                                                 RequestStatus required, RequestStatus target,
                                                 String eventType) {
        PossessionRequest pr = loadRequest(requestId);
        OperationResultView replayed = checkReplay(pr, operation, request);
        if (replayed != null) {
            return replayed;
        }
        ensureVersion(pr, request);
        if (pr.status() != required) {
            throw new BusinessRuleException(
                    "操作 " + operation + " 仅允许从 " + required + " 迁移，当前状态: " + pr.status());
        }
        int newVersion = pr.version() + 1;
        casStatus(pr, required, target, request.businessTime(), newVersion, eventType, null);
        OperationResultView result = new OperationResultView(
                pr.id(), operation.name(), target.name(), newVersion,
                false, List.of(), List.of(), request.businessTime());
        recordSuccess(pr, operation, request, result);
        return result;
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    /**
     * 发布当前全部申请的不可变计划快照。
     */
    @Transactional
    public SnapshotView publishSnapshot(Instant businessTime) {
        List<RequestView> requests = repository.listAll().stream().map(this::toView).toList();
        String snapshotId = "snap-" + Instant.now(clock).toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        SnapshotView view = new SnapshotView(snapshotId, businessTime, Instant.now(clock), requests);
        repository.insertSnapshot(snapshotId, businessTime, writeJson(view));
        return view;
    }

    /**
     * 读取快照；后续状态变化不影响已发布内容。
     */
    public SnapshotView getSnapshot(String snapshotId) {
        PossessionRepository.SnapshotRow row = repository.findSnapshot(snapshotId);
        if (row == null) {
            throw new NotFoundException("快照不存在: " + snapshotId);
        }
        try {
            return objectMapper.readValue(row.contentJson(), SnapshotView.class);
        } catch (Exception e) {
            throw new IllegalStateException("快照内容解析失败: " + snapshotId, e);
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private PossessionRequest loadRequest(String requestId) {
        PossessionRequest pr = repository.findById(requestId);
        if (pr == null) {
            throw new NotFoundException("封锁申请不存在: " + requestId);
        }
        return pr;
    }

    private void ensureVersion(PossessionRequest pr, OperationRequest request) {
        if (pr.version() != request.expectedVersion()) {
            throw new BusinessRuleException(
                    "expectedVersion 不匹配: 期望 " + request.expectedVersion() + "，实际 " + pr.version());
        }
    }

    /**
     * 幂等账本检查：同请求键同操作已成功过——同参重放首次结果，异参拒绝（409）。
     *
     * @return 重放结果；null 表示首次操作
     */
    private OperationResultView checkReplay(PossessionRequest pr, OperationType operation,
                                            OperationRequest request) {
        LedgerEntry ledger = repository.findLedger(pr.id(), operation);
        if (ledger == null) {
            return null;
        }
        String fingerprint = fingerprint(operation, pr.id(), request);
        if (!MessageDigest.isEqual(
                fingerprint.getBytes(StandardCharsets.UTF_8),
                ledger.fingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new ConflictException(
                    "请求键 " + pr.id() + " 的 " + operation + " 操作已以不同参数成功过，异参复用被拒绝",
                    List.of());
        }
        try {
            OperationResultView first = objectMapper.readValue(ledger.responseJson(), OperationResultView.class);
            return new OperationResultView(
                    first.requestId(), first.operation(), first.status(), first.version(), true,
                    first.preemptedConflicts(), first.preemptedRequestIds(), first.businessTime());
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果解析失败: " + pr.id(), e);
        }
    }

    private void recordSuccess(PossessionRequest pr, OperationType operation,
                               OperationRequest request, OperationResultView result) {
        String fingerprint = fingerprint(operation, pr.id(), request);
        boolean inserted = repository.insertLedger(pr.id(), operation, fingerprint, writeJson(result));
        if (!inserted) {
            // 区段锁与版本检查已串行化同键操作；到达此处意味着并发侥幸同时成功，回滚保证唯一成功
            throw new ConflictException("同一操作存在并发提交，仅允许一个成功", List.of());
        }
    }

    private void casStatus(PossessionRequest pr, RequestStatus from, RequestStatus to,
                           Instant businessTime, int newVersion, String eventType, String detail) {
        int updated = repository.compareAndUpdateStatus(pr.id(), from, to, pr.version(), newVersion);
        if (updated == 0) {
            PossessionRequest latest = repository.findById(pr.id());
            throw new BusinessRuleException(
                    "状态已被并发操作改变，当前状态: " + (latest == null ? "不存在" : latest.status())
                            + "，版本: " + (latest == null ? "-" : latest.version()));
        }
        repository.insertEvent(pr.id(), eventType, from, to, businessTime, newVersion, detail);
    }

    private void throwConflict(PossessionRequest pr, List<ConflictRow> rows, String message) {
        throw new ConflictException(message, toConflictViews(pr, rows));
    }

    private List<ConflictView> toConflictViews(PossessionRequest pr, List<ConflictRow> rows) {
        // 一个冲突申请可能在多个区段上重叠：每个 (区段, 冲突申请) 一条明细，按区段、重叠起点排序
        List<ConflictView> views = rows.stream()
                .map(row -> toConflictView(row, pr))
                .sorted(ComparatorChain.chain())
                .toList();
        return views;
    }

    private ConflictView toConflictView(ConflictRow row, PossessionRequest self) {
        Instant overlapStart = row.bufferedStart().isAfter(self.bufferedStart())
                ? row.bufferedStart() : self.bufferedStart();
        Instant overlapEnd = row.bufferedEnd().isBefore(self.bufferedEnd())
                ? row.bufferedEnd() : self.bufferedEnd();
        return new ConflictView(
                row.sectionId(),
                row.requestId(),
                row.status().name(),
                row.bufferedStart(),
                row.bufferedEnd(),
                overlapStart,
                overlapEnd);
    }

    private RequestView toView(PossessionRequest pr) {
        return new RequestView(
                pr.id(),
                pr.sectionIds(),
                pr.start(),
                pr.end(),
                pr.bufferedStart(),
                pr.bufferedEnd(),
                pr.preBufferSeconds(),
                pr.postBufferSeconds(),
                pr.priority(),
                pr.status().name(),
                pr.version(),
                pr.businessTime());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 请求指纹：覆盖操作、请求键、期望版本与业务时间全部参数（SHA-256 十六进制）。
     */
    static String fingerprint(OperationType operation, String requestId, OperationRequest request) {
        String canonical = String.join("|",
                operation.name(),
                requestId,
                Integer.toString(request.expectedVersion()),
                request.businessTime().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 冲突明细排序：区段ID → 重叠起点 → 冲突申请ID。
     */
    private static final class ComparatorChain {
        static java.util.Comparator<ConflictView> chain() {
            return java.util.Comparator.comparing(ConflictView::sectionId)
                    .thenComparing(ConflictView::overlapStart)
                    .thenComparing(ConflictView::conflictingRequestId);
        }
    }
}
