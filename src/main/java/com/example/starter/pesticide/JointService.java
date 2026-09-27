package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAllocEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionView;
import com.example.starter.pesticide.dto.ApiDtos.JointDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointItemVersionEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointItemView;
import com.example.starter.pesticide.dto.ApiDtos.JointResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 定量联检服务：疑似冻结批次建立联检单（冻结逐项限值与机构能力版本），
 * 一次性覆盖全部项目的分样、逐项结果与复测（保留旧结果、项内版本递增）、
 * 携带全部项版本的综合判定（合格放行、不合格转处置留样，同一事务）。
 */
@Service
public class JointService {

    /** 综合判定不合格转处置后的留样时长（单位：小时），与原复检规则一致 */
    public static final int RETENTION_HOURS = TestService.RETENTION_HOURS;

    private final JointDao jointDao;
    private final BatchDao batchDao;
    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final Clock clock;

    public JointService(JointDao jointDao, BatchDao batchDao, TestDao testDao,
                        LedgerDao ledgerDao, Clock clock) {
        this.jointDao = jointDao;
        this.batchDao = batchDao;
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.clock = clock;
    }

    /**
     * 创建联检单：requestId 幂等；批次须疑似冻结并引用该批次的疑似快检记录；
     * 项目至少两种且唯一，逐项限值与机构能力版本一并冻结；初始版本 1。
     */
    @Transactional
    public JointResponse create(JointCreateRequest request) {
        List<String> items = request.items();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("联检项目列表存在重复项目");
        }
        List<String> sortedItems = items.stream().sorted().toList();
        List<String> parts = new ArrayList<>(List.of(request.requestId(),
                String.valueOf(request.batchId()), String.valueOf(request.testId()),
                request.agencyCode()));
        parts.addAll(sortedItems);
        String fingerprint = Fingerprint.structured(parts.toArray(String[]::new));
        var existing = jointDao.findJointByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointDao.JointRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toJointResponse(row, true);
        }
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        if (!BatchStatus.FROZEN.name().equals(batch.status())) {
            throw ApiException.unprocessable("批次非疑似冻结状态，不得建立联检单: " + batch.status());
        }
        TestDao.TestRow test = testDao.findTestById(request.testId())
                .orElseThrow(() -> ApiException.notFound("快检记录不存在: " + request.testId()));
        if (test.batchId() != request.batchId() || !"SUSPECT".equals(test.result())) {
            throw ApiException.unprocessable("联检单须引用本批次已冻结的疑似快检结果");
        }
        Map<String, BigDecimal> limits = new LinkedHashMap<>();
        Map<String, Integer> capabilityVersions = new LinkedHashMap<>();
        for (String item : sortedItems) {
            limits.put(item, jointDao.findCatalogLimit(item)
                    .orElseThrow(() -> ApiException.unprocessable("项目不在合成项目目录: " + item)));
            capabilityVersions.put(item, jointDao.findCapabilityVersion(request.agencyCode(), item)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "机构不具备该项目能力: " + request.agencyCode() + "/" + item)));
        }
        LocalDateTime now = nowUtc();
        long jointId;
        try {
            jointId = jointDao.insertJoint(request.requestId(), fingerprint, batch.id(),
                    test.id(), request.agencyCode(), now);
        } catch (DuplicateKeyException e) {
            var raced = jointDao.findJointByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toJointResponse(raced.get(), true);
            }
            throw ApiException.conflict("requestId 冲突");
        }
        for (String item : sortedItems) {
            jointDao.insertJointItem(jointId, item, limits.get(item), capabilityVersions.get(item));
        }
        return toJointResponse(jointDao.findJointById(jointId).orElseThrow(), false);
    }

    /**
     * 分样分配：requestId 幂等；须携带 expectedVersion；分配须覆盖全部项目、
     * 样本号全局唯一、机构具备对应项目能力，任一项失败整单不分样；成功后版本加 1。
     */
    @Transactional
    public JointAllocateResponse allocate(long jointId, JointAllocateRequest request) {
        List<JointAllocEntry> allocations = request.allocations();
        Set<String> allocItems = new HashSet<>();
        Set<String> sampleNos = new HashSet<>();
        for (JointAllocEntry entry : allocations) {
            if (!allocItems.add(entry.item())) {
                throw ApiException.badRequest("分样列表存在重复项目: " + entry.item());
            }
            if (!sampleNos.add(entry.sampleNo())) {
                throw ApiException.badRequest("分样列表存在重复样本号: " + entry.sampleNo());
            }
        }
        List<JointAllocEntry> sorted = allocations.stream()
                .sorted((a, b) -> a.item().compareTo(b.item())).toList();
        List<String> parts = new ArrayList<>(List.of(request.requestId(), String.valueOf(jointId),
                String.valueOf(request.expectedVersion())));
        sorted.forEach(e -> {
            parts.add(e.item());
            parts.add(e.sampleNo());
        });
        String fingerprint = Fingerprint.structured(parts.toArray(String[]::new));
        var existing = jointDao.findAllocationByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointDao.AllocationRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            JointDao.JointRow joint = jointDao.findJointById(row.jointId()).orElseThrow();
            return toAllocateResponse(joint, row.createdAt(), true);
        }
        JointDao.JointRow joint = jointDao.lockJointById(jointId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + jointId));
        // 锁内重查：并发同 requestId 在锁释放后到达时按幂等重放处理
        var racedAlloc = jointDao.findAllocationByRequestId(request.requestId());
        if (racedAlloc.isPresent()) {
            JointDao.AllocationRow row = racedAlloc.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            JointDao.JointRow current = jointDao.findJointById(row.jointId()).orElseThrow();
            return toAllocateResponse(current, row.createdAt(), true);
        }
        if (!JointStatus.OPEN.name().equals(joint.status())) {
            throw ApiException.unprocessable("联检单状态不允许分样: " + joint.status());
        }
        if (joint.version() != request.expectedVersion()) {
            throw ApiException.conflict("联检单版本失配: 当前 " + joint.version()
                    + ", 期望 " + request.expectedVersion());
        }
        List<JointDao.JointItemRow> jointItems = jointDao.findItems(jointId);
        Set<String> required = jointItems.stream().map(JointDao.JointItemRow::item)
                .collect(Collectors.toSet());
        if (!allocItems.equals(required)) {
            throw ApiException.unprocessable("分样须覆盖联检全部项目，任一项缺失整单不分样");
        }
        for (JointAllocEntry entry : sorted) {
            jointDao.findCapabilityVersion(joint.agencyCode(), entry.item())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "机构不具备该项目能力，整单不分样: " + joint.agencyCode() + "/" + entry.item()));
        }
        LocalDateTime now = nowUtc();
        try {
            for (JointAllocEntry entry : sorted) {
                jointDao.updateSampleNo(jointId, entry.item(), entry.sampleNo());
            }
            jointDao.insertAllocation(jointId, request.requestId(), fingerprint, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("样本号已被占用（样本号须全局唯一）或 requestId 冲突");
        }
        jointDao.updateJointState(jointId, JointStatus.ALLOCATED.name(), joint.version() + 1, now);
        return toAllocateResponse(jointDao.findJointById(jointId).orElseThrow(), now, false);
    }

    /**
     * 录入/复测联检结果：requestId 幂等；结果引用样本、项目和联检版本；
     * 复测在综合判定前替换该项当前结果并保留旧结果（项内版本递增）；
     * 已综合判定后只记补充信息，不重开判定。
     */
    @Transactional
    public JointResultResponse addResult(long jointId, JointResultRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(jointId),
                String.valueOf(request.expectedVersion()), request.item(), request.sampleNo(),
                request.concentration().toPlainString(), request.testedAt().toString());
        var existing = jointDao.findResultByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointDao.JointResultRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            JointDao.JointRow joint = jointDao.findJointById(row.jointId()).orElseThrow();
            return toResultResponse(row, joint, false, true);
        }
        JointDao.JointRow joint = jointDao.lockJointById(jointId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + jointId));
        // 锁内重查：并发同 requestId 在锁释放后到达时按幂等重放处理
        var racedResult = jointDao.findResultByRequestId(request.requestId());
        if (racedResult.isPresent()) {
            JointDao.JointResultRow row = racedResult.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            JointDao.JointRow current = jointDao.findJointById(row.jointId()).orElseThrow();
            return toResultResponse(row, current, false, true);
        }
        LocalDateTime now = nowUtc();
        if (JointStatus.DECIDED.name().equals(joint.status())) {
            ledgerDao.insertLateEvent(joint.batchId(), "LATE_JOINT_RESULT",
                    "jointId=" + jointId + ", item=" + request.item()
                            + ", concentration=" + request.concentration(), now);
            return new JointResultResponse(null, request.requestId(), jointId, request.item(),
                    request.sampleNo(), request.concentration(), null, joint.version(),
                    joint.status(), now.toInstant(ZoneOffset.UTC), false, true);
        }
        if (!JointStatus.ALLOCATED.name().equals(joint.status())) {
            throw ApiException.unprocessable("联检单未分样，不得录入结果: " + joint.status());
        }
        if (joint.version() != request.expectedVersion()) {
            throw ApiException.conflict("联检单版本失配: 当前 " + joint.version()
                    + ", 期望 " + request.expectedVersion());
        }
        Map<String, JointDao.JointItemRow> itemMap = jointDao.findItems(jointId).stream()
                .collect(Collectors.toMap(JointDao.JointItemRow::item, Function.identity()));
        JointDao.JointItemRow jointItem = itemMap.get(request.item());
        if (jointItem == null) {
            throw ApiException.unprocessable("项目不属于该联检单: " + request.item());
        }
        if (!request.sampleNo().equals(jointItem.sampleNo())) {
            throw ApiException.unprocessable("样本号与该项分样不一致: " + request.sampleNo());
        }
        int currentVersion = jointDao.findCurrentResults(jointId).stream()
                .filter(r -> r.item().equals(request.item()))
                .mapToInt(JointDao.JointResultRow::version).max().orElse(0);
        long resultId;
        try {
            resultId = jointDao.insertResult(jointId, request.item(), request.sampleNo(),
                    request.concentration(), currentVersion + 1, request.requestId(), fingerprint, now);
        } catch (DuplicateKeyException e) {
            var raced = jointDao.findResultByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                JointDao.JointRow current = jointDao.findJointById(jointId).orElseThrow();
                return toResultResponse(raced.get(), current, false, true);
            }
            throw ApiException.conflict("结果版本冲突，请刷新后重试");
        }
        jointDao.updateJointState(jointId, joint.status(), joint.version() + 1, now);
        JointDao.JointResultRow row = jointDao.findResultByRequestId(request.requestId()).orElseThrow();
        JointDao.JointRow updated = jointDao.findJointById(jointId).orElseThrow();
        return toResultResponse(new JointDao.JointResultRow(resultId, row.jointId(), row.item(),
                row.sampleNo(), row.concentration(), row.version(), row.requestId(),
                row.fingerprint(), row.createdAt()), updated, false, false);
    }

    /**
     * 综合判定：requestId 幂等；须携带联检版本与全部项当前结果版本，失配则判定零写入；
     * 尚缺任一项结果只能待定；恰等限值合格、任一超限整体不合格；
     * 合格同事务更新原批准入状态，不合格转原处置流程并留样 48 小时。
     */
    @Transactional
    public JointDecisionResponse decide(long jointId, JointDecisionRequest request) {
        List<JointItemVersionEntry> itemVersions = request.itemVersions();
        Set<String> versionItems = new HashSet<>();
        for (JointItemVersionEntry entry : itemVersions) {
            if (!versionItems.add(entry.item())) {
                throw ApiException.badRequest("项版本集合存在重复项目: " + entry.item());
            }
        }
        List<JointItemVersionEntry> sorted = itemVersions.stream()
                .sorted((a, b) -> a.item().compareTo(b.item())).toList();
        List<String> parts = new ArrayList<>(List.of(request.requestId(), String.valueOf(jointId),
                String.valueOf(request.expectedVersion())));
        sorted.forEach(e -> {
            parts.add(e.item());
            parts.add(String.valueOf(e.version()));
        });
        String fingerprint = Fingerprint.structured(parts.toArray(String[]::new));
        var existing = jointDao.findDecisionByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointDao.JointDecisionRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toDecisionResponse(row, true);
        }
        JointDao.JointRow joint = jointDao.lockJointById(jointId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + jointId));
        // 锁内重查：并发同 requestId 在锁释放后到达时按幂等重放处理
        var racedDecision = jointDao.findDecisionByRequestId(request.requestId());
        if (racedDecision.isPresent()) {
            JointDao.JointDecisionRow row = racedDecision.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toDecisionResponse(row, true);
        }
        if (JointStatus.DECIDED.name().equals(joint.status())) {
            throw ApiException.unprocessable("联检单已综合判定，不得重复判定");
        }
        if (!JointStatus.ALLOCATED.name().equals(joint.status())) {
            throw ApiException.unprocessable("联检单未分样，不得综合判定: " + joint.status());
        }
        if (joint.version() != request.expectedVersion()) {
            throw ApiException.conflict("联检单版本失配，判定零写入: 当前 " + joint.version()
                    + ", 期望 " + request.expectedVersion());
        }
        List<JointDao.JointItemRow> jointItems = jointDao.findItems(jointId);
        Map<String, JointDao.JointResultRow> currentByItem = jointDao.findCurrentResults(jointId)
                .stream().collect(Collectors.toMap(JointDao.JointResultRow::item, Function.identity()));
        for (JointDao.JointItemRow item : jointItems) {
            if (!currentByItem.containsKey(item.item())) {
                throw ApiException.unprocessable("联检项目 " + item.item() + " 尚缺结果，只能待定，不得判定");
            }
        }
        Map<String, Integer> requestedVersions = sorted.stream()
                .collect(Collectors.toMap(JointItemVersionEntry::item, JointItemVersionEntry::version));
        if (!requestedVersions.keySet().equals(currentByItem.keySet())) {
            throw ApiException.conflict("项版本集合与联检项目不一致，判定零写入");
        }
        for (Map.Entry<String, JointDao.JointResultRow> entry : currentByItem.entrySet()) {
            if (requestedVersions.get(entry.getKey()) != entry.getValue().version()) {
                throw ApiException.conflict("项目 " + entry.getKey() + " 结果版本失配，判定零写入: 当前 "
                        + entry.getValue().version() + ", 携带 " + requestedVersions.get(entry.getKey()));
            }
        }
        Map<String, JointDao.JointItemRow> itemMap = jointItems.stream()
                .collect(Collectors.toMap(JointDao.JointItemRow::item, Function.identity()));
        boolean qualified = currentByItem.values().stream().allMatch(r ->
                r.concentration().compareTo(itemMap.get(r.item()).limitValue()) <= 0);
        String verdict = qualified ? "QUALIFIED" : "UNQUALIFIED";
        StringBuilder evidence = new StringBuilder();
        for (JointDao.JointResultRow row : currentByItem.values().stream()
                .sorted((a, b) -> a.item().compareTo(b.item())).toList()) {
            if (evidence.length() > 0) {
                evidence.append(';');
            }
            evidence.append(row.item()).append("=v").append(row.version());
        }
        BatchDao.BatchRow batch = batchDao.lockById(joint.batchId()).orElseThrow();
        if (!BatchStatus.FROZEN.name().equals(batch.status())) {
            throw ApiException.unprocessable("批次状态异常，判定回滚: " + batch.status());
        }
        LocalDateTime now = nowUtc();
        jointDao.insertDecision(jointId, request.requestId(), fingerprint, verdict,
                evidence.toString(), now);
        jointDao.updateJointState(jointId, JointStatus.DECIDED.name(), joint.version() + 1, now);
        String batchStatus;
        Instant retentionUntil = null;
        if (qualified) {
            ledgerDao.insertAdmission(batch.id(), now);
            batchDao.updateStatus(batch.id(), BatchStatus.RELEASED.name(), now);
            batchStatus = BatchStatus.RELEASED.name();
        } else {
            ledgerDao.insertDisposal(batch.id(), "联检综合判定不合格，转监督处置", now);
            LocalDateTime until = now.plusHours(RETENTION_HOURS);
            ledgerDao.insertRetention(batch.id(), RETENTION_HOURS, until, now);
            batchDao.updateStatus(batch.id(), BatchStatus.DISPOSED.name(), now);
            batchStatus = BatchStatus.DISPOSED.name();
            retentionUntil = until.toInstant(ZoneOffset.UTC);
        }
        return new JointDecisionResponse(jointId, verdict, joint.version() + 1, batchStatus,
                retentionUntil, evidence.toString(), now.toInstant(ZoneOffset.UTC), false);
    }

    /**
     * 联检单详情：含项目快照、全部结果版本与综合判定。
     */
    @Transactional(readOnly = true)
    public JointDetailResponse detail(long jointId) {
        JointDao.JointRow joint = jointDao.findJointById(jointId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + jointId));
        List<JointItemView> items = jointDao.findItems(jointId).stream()
                .map(this::toItemView).toList();
        List<JointResultView> results = jointDao.findResults(jointId).stream()
                .map(r -> new JointResultView(r.item(), r.sampleNo(), r.concentration(), r.version(),
                        r.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        JointDecisionView decision = jointDao.findDecisionByJoint(jointId)
                .map(d -> new JointDecisionView(d.verdict(), d.evidence(),
                        d.createdAt().toInstant(ZoneOffset.UTC)))
                .orElse(null);
        return new JointDetailResponse(joint.id(), joint.batchId(), joint.testId(),
                joint.agencyCode(), joint.status(), joint.version(), items, results, decision,
                joint.createdAt().toInstant(ZoneOffset.UTC));
    }

    private JointResponse toJointResponse(JointDao.JointRow joint, boolean replayed) {
        List<JointItemView> items = jointDao.findItems(joint.id()).stream()
                .map(this::toItemView).toList();
        return new JointResponse(joint.id(), joint.requestId(), joint.batchId(), joint.testId(),
                joint.agencyCode(), joint.status(), joint.version(), items,
                joint.createdAt().toInstant(ZoneOffset.UTC), replayed);
    }

    private JointItemView toItemView(JointDao.JointItemRow row) {
        return new JointItemView(row.item(), row.limitValue(), row.capabilityVersion(), row.sampleNo());
    }

    private JointAllocateResponse toAllocateResponse(JointDao.JointRow joint,
                                                     LocalDateTime allocatedAt, boolean replayed) {
        List<JointAllocEntry> allocations = jointDao.findItems(joint.id()).stream()
                .map(i -> new JointAllocEntry(i.item(), i.sampleNo())).toList();
        return new JointAllocateResponse(joint.id(), joint.status(), joint.version(), allocations,
                allocatedAt.toInstant(ZoneOffset.UTC), replayed);
    }

    private JointResultResponse toResultResponse(JointDao.JointResultRow row, JointDao.JointRow joint,
                                                 boolean supplemental, boolean replayed) {
        return new JointResultResponse(row.id(), row.requestId(), row.jointId(), row.item(),
                row.sampleNo(), row.concentration(), row.version(), joint.version(), joint.status(),
                row.createdAt().toInstant(ZoneOffset.UTC), replayed, supplemental);
    }

    private JointDecisionResponse toDecisionResponse(JointDao.JointDecisionRow row, boolean replayed) {
        JointDao.JointRow joint = jointDao.findJointById(row.jointId()).orElseThrow();
        BatchDao.BatchRow batch = batchDao.findById(joint.batchId()).orElseThrow();
        Instant retentionUntil = ledgerDao.findRetention(joint.batchId())
                .map(r -> r.retainUntil().toInstant(ZoneOffset.UTC))
                .orElse(null);
        return new JointDecisionResponse(joint.id(), row.verdict(), joint.version(), batch.status(),
                retentionUntil, row.evidence(), row.createdAt().toInstant(ZoneOffset.UTC), replayed);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
