package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.RiskLevel;
import com.example.starter.error.ApiException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.BatchRepository.BatchRow;
import com.example.starter.repo.BatchRepository.HeadRow;
import com.example.starter.repo.RiskRepository;
import com.example.starter.repo.RiskRepository.ChangeEventRow;
import com.example.starter.repo.RiskRepository.ChecklistRow;
import com.example.starter.repo.RiskRepository.RiskBindingRow;
import com.example.starter.repo.RiskRepository.SampleRow;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskChecklistResponse;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskResultResponse;
import com.example.starter.web.dto.RiskSummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 风险抽检服务：宰前检疫合格后绑定合成风险等级并冻结抽检清单，逐只结果联动专项隔离与出厂门禁。
 *
 * <p>并发控制：每个写事务先 {@code SELECT ... FOR UPDATE} 锁定批次主行，再锁风险绑定行，
 * 与 {@link BatchService} 出厂核销同序加锁，保证风险变更、最后结果、阳性与出厂核销两两互斥，
 * 不会出现已放行但漏检的批次。业务失败整体回滚，幂等登记同事务因此不占键。
 */
@org.springframework.stereotype.Service
public class RiskService {

    private final BatchRepository batchRepo;
    private final RiskRepository riskRepo;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final IdempotentExecutor idempotency;

    public RiskService(BatchRepository batchRepo, RiskRepository riskRepo, Clock clock,
                       ObjectMapper objectMapper, IdempotentExecutor idempotency) {
        this.batchRepo = batchRepo;
        this.riskRepo = riskRepo;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
    }

    // ---------- 风险绑定 ----------

    /** 绑定合成风险等级并冻结首份抽检清单（幂等）。 */
    @Transactional
    public RiskChecklistResponse bind(String batchNo, RiskBindRequest req) {
        return idempotency.run("risk-bind", req.requestId(), req, RiskChecklistResponse.class,
                () -> doBind(batchNo, req));
    }

    private RiskChecklistResponse doBind(String batchNo, RiskBindRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        RiskLevel level = parseLevel(req.level());
        List<String> declared = normalizeDeclared(req.declaredHeadNos());
        BatchRow batch = lockAndReload(batchNo);
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            return lateChecklist(batch, "LATE_RISK_BIND",
                    "核销后迟到风险绑定：等级 " + level.name() + "，规则版本 " + req.ruleVersion(),
                    req.ruleVersion(), level.name(), 0, List.of(), declared);
        }
        if (BatchStatus.DECLARED.name().equals(batch.status())) {
            throw ApiException.unprocessable("批次宰前检疫尚未合格，不允许绑定风险等级");
        }
        if (riskRepo.findBinding(batch.id()).isPresent()) {
            throw ApiException.conflict("批次已绑定风险等级，同一批次只允许一个当前风险绑定");
        }
        if (req.expectedVersion() != 0) {
            throw ApiException.conflict("首次风险绑定 expectedVersion 须为 0");
        }
        verifyDeclaredMatchesLedger(batch.id(), declared);

        List<String> samples = pickSamples(declared, level);
        String now = occurredAt;
        riskRepo.insertChecklist(batch.id(), 1L, "INITIAL", level.name(), req.ruleVersion(), "OPEN",
                toJson(declared), toJson(samples), occurredAt);
        riskRepo.insertBinding(batch.id(), 1, level.name(), req.ruleVersion(), 1L, now);
        riskRepo.insertChangeEvent(batch.id(), "BIND", null, level.name(), null, req.ruleVersion(),
                1L, 0, 1, occurredAt);
        return new RiskChecklistResponse(batchNo, 1, 1L, "INITIAL", level.name(), req.ruleVersion(),
                "OPEN", samples, declared, false, false, false);
    }

    // ---------- 风险等级变更 ----------

    /** 风险等级变更：未完成清单提高等级原子扩充；已完成清单另开补充清单；降低/同级拒绝（幂等）。 */
    @Transactional
    public RiskChecklistResponse changeLevel(String batchNo, RiskChangeRequest req) {
        return idempotency.run("risk-change", req.requestId(), req, RiskChecklistResponse.class,
                () -> doChangeLevel(batchNo, req));
    }

    private RiskChecklistResponse doChangeLevel(String batchNo, RiskChangeRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        RiskLevel level = parseLevel(req.level());
        List<String> declared = normalizeDeclared(req.declaredHeadNos());
        BatchRow batch = lockAndReload(batchNo);
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            RiskBindingRow current = riskRepo.findBinding(batch.id()).orElse(null);
            return lateChecklist(batch, "LATE_RISK_CHANGE",
                    "核销后迟到风险变更：等级 " + level.name() + "，规则版本 " + req.ruleVersion(),
                    req.ruleVersion(), level.name(),
                    current == null ? 0L : current.currentSequenceNo(), List.of(), declared);
        }
        RiskBindingRow binding = mustFindBinding(batch);
        if (req.expectedVersion() != binding.version()) {
            throw ApiException.conflict("风险版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + binding.version());
        }
        verifyDeclaredMatchesLedger(batch.id(), declared);
        RiskLevel currentLevel = parseLevel(binding.level());
        if (level.rank() <= currentLevel.rank()) {
            throw ApiException.unprocessable("只允许提高风险等级；等级降低不缩减冻结清单，同级无变化拒绝受理");
        }
        ChecklistRow current = mustFindChecklist(batch.id(), binding.currentSequenceNo());
        if ("OPEN".equals(current.status())) {
            // 原子扩充：沿用原清单序号，既有逐只结果原样保留，仅扩大冻结抽检集合与风险规则。
            List<String> expanded = pickSamples(declared, level);
            int updated = riskRepo.expandChecklist(batch.id(), current.sequenceNo(), level.name(),
                    req.ruleVersion(), toJson(expanded), occurredAt);
            if (updated == 0) {
                throw ApiException.conflict("并发冲突：当前清单状态已变化，原子扩充失败");
            }
            int newVersion = binding.version() + 1;
            updateBindingOrFail(batch.id(), binding, newVersion, level.name(), req.ruleVersion(),
                    current.sequenceNo(), occurredAt);
            riskRepo.insertChangeEvent(batch.id(), "EXPAND", binding.level(), level.name(),
                    binding.ruleVersion(), req.ruleVersion(), current.sequenceNo(),
                    binding.version(), newVersion, occurredAt);
            GateState gate = gateState(batch.id());
            return new RiskChecklistResponse(batchNo, newVersion, current.sequenceNo(), "EXPANDED",
                    level.name(), req.ruleVersion(), "OPEN", expanded, declared,
                    gate.completed(), gate.quarantine(), false);
        }
        // 当前清单已完成：不可改，另开补充清单并再次锁出厂。
        long newSequenceNo = current.sequenceNo() + 1;
        List<String> samples = pickSamples(declared, level);
        riskRepo.insertChecklist(batch.id(), newSequenceNo, "SUPPLEMENTARY", level.name(),
                req.ruleVersion(), "OPEN", toJson(declared), toJson(samples), occurredAt);
        int newVersion = binding.version() + 1;
        updateBindingOrFail(batch.id(), binding, newVersion, level.name(), req.ruleVersion(),
                newSequenceNo, occurredAt);
        riskRepo.insertChangeEvent(batch.id(), "SUPPLEMENT", binding.level(), level.name(),
                binding.ruleVersion(), req.ruleVersion(), newSequenceNo,
                binding.version(), newVersion, occurredAt);
        return new RiskChecklistResponse(batchNo, newVersion, newSequenceNo, "SUPPLEMENTARY",
                level.name(), req.ruleVersion(), "OPEN", samples, declared, false,
                gateState(batch.id()).quarantine(), false);
    }

    // ---------- 逐只抽检结果 ----------

    /** 逐只提交抽检结果：清单外拒绝、同结果重复无新记录、异结果冲突；阳性联动专项隔离（幂等）。 */
    @Transactional
    public RiskResultResponse submitResult(String batchNo, RiskResultRequest req) {
        return idempotency.run("risk-result", req.requestId(), req, RiskResultResponse.class,
                () -> doSubmitResult(batchNo, req));
    }

    private RiskResultResponse doSubmitResult(String batchNo, RiskResultRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        String result = parseResult(req.result());
        BatchRow batch = lockAndReload(batchNo);
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            batchRepo.insertLateEvent(batch.id(), "LATE_RISK_RESULT",
                    "核销后迟到抽检结果：头只 " + req.headNo() + "，结果 " + result, now());
            RiskBindingRow binding = riskRepo.findBinding(batch.id()).orElse(null);
            GateState gate = binding == null ? GateState.none() : gateState(batch.id());
            return new RiskResultResponse(batchNo, binding == null ? 0 : binding.version(),
                    binding == null ? 0L : binding.currentSequenceNo(), req.headNo(), result, false,
                    gate.completed(), gate.completed(), gate.allNegative(), gate.quarantine(), true);
        }
        RiskBindingRow binding = mustFindBinding(batch);
        if (req.expectedVersion() != binding.version()) {
            throw ApiException.conflict("风险版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + binding.version());
        }
        long sequenceNo = binding.currentSequenceNo();
        ChecklistRow checklist = mustFindChecklist(batch.id(), sequenceNo);
        List<String> frozen = fromJson(checklist.frozenSamples());
        if (!frozen.contains(req.headNo())) {
            throw ApiException.unprocessable("头只不在当前抽检清单（序号 " + sequenceNo
                    + "）冻结范围内，清单外结果拒绝：" + req.headNo());
        }
        Optional<SampleRow> existing = riskRepo.findSamples(batch.id(), sequenceNo).stream()
                .filter(s -> s.headNo().equals(req.headNo())).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().result().equals(result)) {
                throw ApiException.conflict("头只 " + req.headNo() + " 已登记结果 "
                        + existing.get().result() + "，异结果 " + result + " 冲突");
            }
            // 同结果重复：无新记录、版本不增加，返回当前门禁状态。
            GateState gate = gateState(batch.id());
            return new RiskResultResponse(batchNo, binding.version(), sequenceNo, req.headNo(), result,
                    true, gate.isChecklistCompleted(sequenceNo), gate.completed(), gate.allNegative(),
                    gate.quarantine(), false);
        }
        try {
            riskRepo.insertSample(batch.id(), sequenceNo, req.headNo(), result, occurredAt);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同头只并发插入：唯一约束兜底，重读既有结果区分同结果重复与异结果冲突。
            SampleRow concurrent = riskRepo.findSamples(batch.id(), sequenceNo).stream()
                    .filter(s -> s.headNo().equals(req.headNo())).findFirst()
                    .orElseThrow(() -> e);
            if (!concurrent.result().equals(result)) {
                throw ApiException.conflict("头只 " + req.headNo() + " 已登记结果 "
                        + concurrent.result() + "，异结果 " + result + " 并发冲突");
            }
            GateState gate = gateState(batch.id());
            return new RiskResultResponse(batchNo, binding.version(), sequenceNo, req.headNo(), result,
                    true, gate.isChecklistCompleted(sequenceNo), gate.completed(), gate.allNegative(),
                    gate.quarantine(), false);
        }
        if ("POSITIVE".equals(result)) {
            // 条件流转：任一阳性整批专项隔离；RELEASED 终态不受影响（迟到分支已先行处理）。
            riskRepo.markSpecialQuarantine(batch.id());
        }
        GateState afterInsert = gateState(batch.id());
        if (afterInsert.isChecklistCompleted(sequenceNo) && "OPEN".equals(checklist.status())) {
            riskRepo.completeChecklist(batch.id(), sequenceNo, occurredAt);
        }
        GateState gate = gateState(batch.id());
        int newVersion = binding.version() + 1;
        updateBindingOrFail(batch.id(), binding, newVersion, binding.level(), binding.ruleVersion(),
                sequenceNo, occurredAt);
        return new RiskResultResponse(batchNo, newVersion, sequenceNo, req.headNo(), result, false,
                gate.isChecklistCompleted(sequenceNo), gate.completed(), gate.allNegative(),
                gate.quarantine(), false);
    }

    // ---------- 清单及隔离原因查询 ----------

    /** 清单及隔离原因查询：未绑定风险的批次返回 controlled=false 的空清单视图。 */
    @Transactional(readOnly = true)
    public RiskSummaryView getSummary(String batchNo) {
        BatchRow batch = batchRepo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
        Optional<RiskBindingRow> bindingOpt = riskRepo.findBinding(batch.id());
        List<ChecklistRow> checklists = riskRepo.findChecklists(batch.id());
        List<SampleRow> allSamples = riskRepo.findAllSamples(batch.id());
        Map<Long, List<SampleRow>> samplesBySeq = new LinkedHashMap<>();
        for (SampleRow sample : allSamples) {
            samplesBySeq.computeIfAbsent(sample.sequenceNo(), k -> new ArrayList<>()).add(sample);
        }
        List<RiskSummaryView.ChecklistView> checklistViews = new ArrayList<>();
        List<RiskSummaryView.QuarantineReasonView> reasons = new ArrayList<>();
        boolean anyPositive = false;
        boolean allFrozenHaveResults = true;
        for (ChecklistRow checklist : checklists) {
            List<String> frozen = fromJson(checklist.frozenSamples());
            Map<String, SampleRow> byHead = new LinkedHashMap<>();
            for (SampleRow sample : samplesBySeq.getOrDefault(checklist.sequenceNo(), List.of())) {
                byHead.put(sample.headNo(), sample);
            }
            List<RiskSummaryView.SampleView> sampleViews = new ArrayList<>();
            for (String headNo : frozen) {
                SampleRow sample = byHead.get(headNo);
                sampleViews.add(new RiskSummaryView.SampleView(headNo,
                        sample == null ? null : sample.result(),
                        sample == null ? null : sample.submittedAt()));
                if (sample == null) {
                    allFrozenHaveResults = false;
                } else if ("POSITIVE".equals(sample.result())) {
                    anyPositive = true;
                    reasons.add(new RiskSummaryView.QuarantineReasonView(checklist.sequenceNo(),
                            headNo, "POSITIVE", sample.submittedAt(), checklist.level(),
                            checklist.ruleVersion()));
                }
            }
            checklistViews.add(new RiskSummaryView.ChecklistView(checklist.sequenceNo(),
                    checklist.kind(), checklist.level(), checklist.ruleVersion(), checklist.status(),
                    fromJson(checklist.frozenDeclared()), sampleViews, checklist.createdAt(),
                    checklist.completedAt()));
        }
        if (bindingOpt.isEmpty()) {
            return new RiskSummaryView(batchNo, null, null, null, null, false, false, false, false,
                    checklistViews, reasons);
        }
        RiskBindingRow binding = bindingOpt.get();
        boolean quarantine = BatchStatus.SPECIAL_QUARANTINE.name().equals(batch.status()) || anyPositive;
        return new RiskSummaryView(batchNo, binding.version(), binding.currentSequenceNo(),
                binding.level(), binding.ruleVersion(), allFrozenHaveResults && !checklists.isEmpty(),
                allFrozenHaveResults && !checklists.isEmpty() && !anyPositive, quarantine, true,
                checklistViews, reasons);
    }

    // ---------- 出厂门禁（供 BatchService 复用） ----------

    /**
     * 受控批次出厂门禁：存在风险绑定时，全部清单完成且全部样本阴性才放行抽检门禁；
     * 任一阳性或存在未出结果样本即拒绝。调用方须已持有批次行锁。
     */
    public void assertReleaseGate(long batchId) {
        Optional<RiskBindingRow> binding = riskRepo.findBinding(batchId);
        if (binding.isEmpty()) {
            return;
        }
        GateState gate = gateState(batchId);
        if (gate.quarantine()) {
            throw ApiException.conflict("抽检发现阳性，整批专项隔离，受控出厂拒绝");
        }
        if (!gate.completed()) {
            throw ApiException.conflict("风险抽检尚未完成，受控出厂门禁未解除");
        }
    }

    // ---------- 内部：门禁状态复算 ----------

    /** 由冻结清单与逐只结果复算的门禁状态。 */
    private record GateState(boolean completed, boolean allNegative, boolean quarantine,
                             Map<Long, Boolean> checklistCompleted) {
        static GateState none() {
            return new GateState(false, true, false, Map.of());
        }

        /** 指定清单是否全部冻结样本均有结果。 */
        boolean isChecklistCompleted(long sequenceNo) {
            return checklistCompleted.getOrDefault(sequenceNo, false);
        }
    }

    private GateState gateState(long batchId) {
        List<ChecklistRow> checklists = riskRepo.findChecklists(batchId);
        List<SampleRow> samples = riskRepo.findAllSamples(batchId);
        Map<Long, Map<String, String>> bySeq = new LinkedHashMap<>();
        for (SampleRow sample : samples) {
            bySeq.computeIfAbsent(sample.sequenceNo(), k -> new LinkedHashMap<>())
                    .put(sample.headNo(), sample.result());
        }
        boolean allCompleted = !checklists.isEmpty();
        boolean allNegative = true;
        Map<Long, Boolean> perChecklist = new LinkedHashMap<>();
        for (ChecklistRow checklist : checklists) {
            List<String> frozen = fromJson(checklist.frozenSamples());
            Map<String, String> results = bySeq.getOrDefault(checklist.sequenceNo(), Map.of());
            boolean checklistDone = frozen.stream().allMatch(results::containsKey);
            perChecklist.put(checklist.sequenceNo(), checklistDone);
            allCompleted &= checklistDone;
            for (String headNo : frozen) {
                if ("POSITIVE".equals(results.get(headNo))) {
                    allNegative = false;
                }
            }
        }
        boolean quarantine = !allNegative;
        return new GateState(allCompleted, allNegative, quarantine, perChecklist);
    }

    // ---------- 内部：迟到告警 ----------

    private RiskChecklistResponse lateChecklist(BatchRow batch, String eventType, String detail,
                                                String ruleVersion, String level, long sequenceNo,
                                                List<String> sampled, List<String> declared) {
        String now = now();
        batchRepo.insertLateEvent(batch.id(), eventType, detail, now);
        Optional<RiskBindingRow> binding = riskRepo.findBinding(batch.id());
        GateState gate = binding.map(b -> gateState(batch.id())).orElse(GateState.none());
        int version = binding.map(RiskBindingRow::version).orElse(0);
        long seq = sequenceNo != 0L ? sequenceNo : binding.map(RiskBindingRow::currentSequenceNo).orElse(0L);
        return new RiskChecklistResponse(batch.batchNo(), version, seq, "LATE", level, ruleVersion,
                "LATE", sampled, declared, gate.completed(), gate.quarantine(), true);
    }

    // ---------- 内部：工具 ----------

    /** 锁定批次主行并重新读取最新状态，保证与出厂核销等竞争事务互斥后再判定。 */
    private BatchRow lockAndReload(String batchNo) {
        BatchRow batch = batchRepo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
        batchRepo.lockBatch(batch.id());
        return batchRepo.findBatchByNo(batchNo).orElseThrow();
    }

    private RiskBindingRow mustFindBinding(BatchRow batch) {
        return riskRepo.findBindingForUpdate(batch.id())
                .orElseThrow(() -> ApiException.notFound("批次尚未绑定风险等级：" + batch.batchNo()));
    }

    private ChecklistRow mustFindChecklist(long batchId, long sequenceNo) {
        return riskRepo.findChecklist(batchId, sequenceNo)
                .orElseThrow(() -> ApiException.notFound("抽检清单不存在：序号 " + sequenceNo));
    }

    private void updateBindingOrFail(long batchId, RiskBindingRow binding, int newVersion, String level,
                                     String ruleVersion, long currentSequenceNo, String now) {
        int updated = riskRepo.updateBinding(batchId, binding.version(), newVersion, level,
                ruleVersion, currentSequenceNo, now);
        if (updated == 0) {
            throw ApiException.conflict("并发冲突：风险版本已被其他请求变更");
        }
    }

    /** 申报集合校验：与头只台账集合恰等（不依赖客户端顺序），不一致业务拒绝。 */
    private void verifyDeclaredMatchesLedger(long batchId, List<String> declaredSorted) {
        Set<String> ledger = new LinkedHashSet<>();
        for (HeadRow head : batchRepo.findHeads(batchId)) {
            ledger.add(head.headNo());
        }
        if (!ledger.equals(new LinkedHashSet<>(declaredSorted))) {
            throw ApiException.unprocessable("申报头只集合与头只台账不一致，拒绝冻结清单");
        }
    }

    /** 按头只编号字典序取前 ceil(总数×比例) 只且至少 1 只。 */
    private List<String> pickSamples(List<String> declaredSorted, RiskLevel level) {
        int size = level.sampleSize(declaredSorted.size());
        return List.copyOf(declaredSorted.subList(0, Math.min(size, declaredSorted.size())));
    }

    private List<String> normalizeDeclared(List<String> headNos) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String headNo : headNos) {
            if (!distinct.add(headNo)) {
                throw ApiException.badRequest("申报头只编号重复：" + headNo);
            }
        }
        List<String> sorted = new ArrayList<>(distinct);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private RiskLevel parseLevel(String level) {
        try {
            return RiskLevel.valueOf(level);
        } catch (Exception e) {
            throw ApiException.badRequest("未知风险等级：" + level + "，支持 NORMAL/TIGHTENED");
        }
    }

    private String parseResult(String result) {
        if ("NEGATIVE".equals(result) || "POSITIVE".equals(result)) {
            return result;
        }
        throw ApiException.badRequest("未知抽检结果：" + result + "，支持 NEGATIVE/POSITIVE");
    }

    private String parseOccurredAt(String occurredAt) {
        try {
            return Instant.parse(occurredAt).toString();
        } catch (Exception e) {
            throw ApiException.badRequest("occurredAt 须为 UTC ISO-8601 时刻：" + occurredAt);
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("冻结集合序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("冻结集合反序列化失败", e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
