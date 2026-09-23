package com.example.starter.lot.service;

import com.example.starter.lot.api.ConversionRequest;
import com.example.starter.lot.api.ConversionResponse;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.GenealogyResponse;
import com.example.starter.lot.api.ImpactResponse;
import com.example.starter.lot.api.LotResponse;
import com.example.starter.lot.api.QuarantineRequest;
import com.example.starter.lot.api.QuarantineResponse;
import com.example.starter.lot.domain.GenealogyEdge;
import com.example.starter.lot.domain.Lot;
import com.example.starter.lot.domain.LotStatus;
import com.example.starter.lot.repository.ConversionRepository;
import com.example.starter.lot.repository.LotRepository;
import com.example.starter.lot.repository.QuarantineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次谱系与隔离传播领域服务。
 *
 * <p>数量一律规范化为三位小数（{@link #SCALE}），超过三位小数的入参直接拒绝，
 * 不做静默舍入；所有写库操作在事务内执行。转换与隔离两类写操作在各自事务内
 * 首先获取全局写顺序锁（{@code service_lock} 单行锁），使两类操作以数据库互斥
 * 形成单一串行提交点：先提交的隔离必然被随后提交的转换纳入传播，反之亦然。</p>
 */
@Service
public class LotGenealogyService {

    /** 数量固定小数位数。 */
    public static final int SCALE = 3;

    private final LotRepository lotRepository;
    private final QuarantineRepository quarantineRepository;
    private final ConversionRepository conversionRepository;
    private final ObjectMapper objectMapper;

    public LotGenealogyService(LotRepository lotRepository,
                               QuarantineRepository quarantineRepository,
                               ConversionRepository conversionRepository,
                               ObjectMapper objectMapper) {
        this.lotRepository = lotRepository;
        this.quarantineRepository = quarantineRepository;
        this.conversionRepository = conversionRepository;
        this.objectMapper = objectMapper;
    }

    /** 数量规范化：必须可精确表示为三位小数（不静默舍入），结果统一三位小数。 */
    static BigDecimal normalizeQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessRuleException("QTY_INVALID", "数量必须为正数");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new BusinessRuleException("QTY_PRECISION_EXCEEDED",
                    "数量最多保留 " + SCALE + " 位小数，收到: " + value.toPlainString());
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 非负数量规范化（允许损耗可为零）。 */
    static BigDecimal normalizeNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new BusinessRuleException("QTY_INVALID", field + "不得为负");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new BusinessRuleException("QTY_PRECISION_EXCEEDED",
                    field + "最多保留 " + SCALE + " 位小数，收到: " + value.toPlainString());
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    @Transactional
    public LotResponse createLot(CreateLotRequest request) {
        BigDecimal quantity = normalizeQuantity(request.quantity());
        Lot lot = new Lot(request.lotId(), request.unit(), quantity, BigDecimal.ZERO.setScale(SCALE),
                LotStatus.AVAILABLE, 0, null, request.remark());
        try {
            lotRepository.insert(lot);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("LOT_ID_CONFLICT", "批次 ID 已存在: " + request.lotId());
        }
        return toLotResponse(lotRepository.findById(request.lotId()));
    }

    @Transactional(readOnly = true)
    public LotResponse getLot(String lotId) {
        Lot lot = lotRepository.findById(lotId);
        if (lot == null) {
            throw new NotFoundException("LOT_NOT_FOUND", "批次不存在: " + lotId);
        }
        return toLotResponse(lot);
    }

    /**
     * 执行一次拆并批转换（原子消费多父批次、产生多子批次）。
     *
     * <p>同 requestId 同完整请求：返回首次结果；同 requestId 异参（数量、损耗或版本变化）：409。
     * 任一规则不满足（ID 冲突、单位不一致、数量越界、损耗越界、谱系环、版本冲突、超用）
     * 均整笔回滚。</p>
     */
    @Transactional
    public ConversionResponse convert(ConversionRequest request) {
        // 第一步：数量规范化并据此计算完整请求指纹（在锁外完成，快速幂等路径无需加锁）。
        NormalizedConversion normalized = normalize(request);

        var existing = conversionRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, normalized);
        }

        // 第二步：全局写顺序锁 + 锁内复查幂等键，杜绝并发同键双写。
        lotRepository.acquireGlobalLock();
        var existingUnderLock = conversionRepository.findByRequestId(request.requestId());
        if (existingUnderLock != null) {
            return replayOrConflict(existingUnderLock, normalized);
        }

        // 第三步：输入行加锁并校验存在性、单位、版本、重复项、余额。
        List<String> inputIds = normalized.inputs.stream().map(ConversionRequest.InputItem::lotId).toList();
        if (new HashSet<>(inputIds).size() != inputIds.size()) {
            throw new BusinessRuleException("DUPLICATE_INPUT", "同一转换中父批次不得重复出现");
        }
        Map<String, Lot> parents = lotRepository.findByIds(inputIds);
        for (String inputId : inputIds) {
            Lot parent = parents.get(inputId);
            if (parent == null) {
                throw new NotFoundException("LOT_NOT_FOUND", "父批次不存在: " + inputId);
            }
            if (!parent.unit().equals(request.unit())) {
                throw new BusinessRuleException("UNIT_MISMATCH",
                        "父批次 " + inputId + " 单位为 " + parent.unit()
                                + "，与转换单位 " + request.unit() + " 不一致");
            }
        }
        // 对每个父批次取行写锁（FOR UPDATE 必须在事务内，顺序按请求给出的顺序）。
        Map<String, Lot> lockedParents = new LinkedHashMap<>();
        for (String inputId : inputIds) {
            Lot locked = lotRepository.lockById(inputId);
            if (locked == null) {
                throw new NotFoundException("LOT_NOT_FOUND", "父批次不存在: " + inputId);
            }
            lockedParents.put(inputId, locked);
        }
        for (ConversionRequest.InputItem input : normalized.inputs) {
            Lot parent = lockedParents.get(input.lotId());
            if (parent.version() != input.expectedVersion()) {
                throw new ConflictException("VERSION_CONFLICT",
                        "父批次 " + input.lotId() + " 期望版本 " + input.expectedVersion()
                                + "，当前版本 " + parent.version());
            }
            BigDecimal available = parent.initialQty().subtract(parent.consumedQty());
            if (input.quantity().compareTo(available) > 0) {
                throw new BusinessRuleException("INSUFFICIENT_BALANCE",
                        "父批次 " + input.lotId() + " 可用余额 " + available.toPlainString()
                                + " 小于本次消费 " + input.quantity().toPlainString());
            }
        }

        // 第四步：输出校验：内部重复、与既有批次冲突、谱系环。
        List<String> outputIds = normalized.outputs.stream().map(ConversionRequest.OutputItem::lotId).toList();
        if (new HashSet<>(outputIds).size() != outputIds.size()) {
            throw new BusinessRuleException("DUPLICATE_OUTPUT", "同一转换中子批次 ID 不得重复");
        }
        if (!lotRepository.findByIds(outputIds).isEmpty()) {
            throw new ConflictException("LOT_ID_CONFLICT", "子批次 ID 已存在: "
                    + lotRepository.findByIds(outputIds).keySet());
        }
        Set<String> ancestors = lotRepository.ancestorIds(inputIds);
        for (String outputId : outputIds) {
            if (ancestors.contains(outputId)) {
                throw new BusinessRuleException("GENEALOGY_CYCLE",
                        "子批次 " + outputId + " 已是父批次祖先，转换将形成谱系环");
            }
        }

        // 第五步：总量与损耗边界。
        BigDecimal totalInput = normalized.inputs.stream()
                .map(ConversionRequest.InputItem::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOutput = normalized.outputs.stream()
                .map(ConversionRequest.OutputItem::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalOutput.compareTo(totalInput) > 0) {
            throw new BusinessRuleException("OUTPUT_EXCEEDS_INPUT",
                    "总产出 " + totalOutput.toPlainString() + " 超过总消费 " + totalInput.toPlainString());
        }
        BigDecimal loss = totalInput.subtract(totalOutput);
        if (loss.compareTo(normalized.allowedLoss) > 0) {
            throw new BusinessRuleException("LOSS_EXCEEDED",
                    "实际损耗 " + loss.toPlainString() + " 超过本次允许损耗 "
                            + normalized.allowedLoss.toPlainString());
        }

        // 第六步：记账。乐观条件更新消费（版本与余额双条件），任一失败整笔回滚。
        for (ConversionRequest.InputItem input : normalized.inputs) {
            int updated = lotRepository.consume(input.lotId(), input.quantity(), input.expectedVersion());
            if (updated != 1) {
                throw new ConflictException("CONCURRENT_CONVERSION",
                        "父批次 " + input.lotId() + " 在处理中被其他转换改动，本次整笔回滚");
            }
        }

        long conversionId = conversionRepository.insert(
                request.requestId(), normalized.fingerprint, normalized.requestJson,
                totalInput, totalOutput, normalized.allowedLoss, request.unit());
        for (int i = 0; i < normalized.inputs.size(); i++) {
            ConversionRequest.InputItem input = normalized.inputs.get(i);
            conversionRepository.insertInput(conversionId, i, input.lotId(),
                    input.quantity(), input.expectedVersion());
        }
        for (int i = 0; i < normalized.outputs.size(); i++) {
            ConversionRequest.OutputItem output = normalized.outputs.get(i);
            conversionRepository.insertOutput(conversionId, i, output.lotId(), output.quantity());
        }

        // 第七步：子批次继承全部父批次提交时仍生效的隔离来源；并写入谱系边。
        Map<String, List<String>> parentSources = quarantineRepository.activeSources(inputIds);
        Set<String> inheritedSources = new LinkedHashSet<>();
        parentSources.values().forEach(inheritedSources::addAll);
        boolean childQuarantined = !inheritedSources.isEmpty();
        List<ConversionResponse.ChildStatus> children = new ArrayList<>();
        for (ConversionRequest.OutputItem output : normalized.outputs) {
            try {
                lotRepository.insertChild(output.lotId(), request.unit(), output.quantity(),
                        childQuarantined ? LotStatus.QUARANTINED : LotStatus.AVAILABLE,
                        "conversion " + conversionId);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("LOT_ID_CONFLICT",
                        "子批次 ID 已被并发创建占用: " + output.lotId());
            }
            for (String source : inheritedSources) {
                quarantineRepository.activate(output.lotId(), source);
            }
            children.add(new ConversionResponse.ChildStatus(
                    output.lotId(),
                    childQuarantined ? LotStatus.QUARANTINED : LotStatus.AVAILABLE,
                    List.copyOf(inheritedSources)));
            for (ConversionRequest.InputItem input : normalized.inputs) {
                lotRepository.insertEdge(new GenealogyEdge(
                        input.lotId(), output.lotId(), conversionId, input.quantity()));
            }
        }

        ConversionResponse response = new ConversionResponse(
                request.requestId(), conversionId, request.unit(),
                List.copyOf(normalized.inputs), List.copyOf(normalized.outputs),
                totalInput, totalOutput, loss, normalized.allowedLoss, List.copyOf(children));
        conversionRepository.updateResponse(conversionId, writeJson(response));
        return response;
    }

    /**
     * 对某批次设置或解除隔离。
     *
     * <p>QUARANTINE：来源自身与全部后代记录该生效来源并置为 QUARANTINED；
     * 后代保存“当前仍生效的来源集合”，多来源叠加。重复设置为幂等。</p>
     *
     * <p>RELEASE：仅解除该来源；每个批次只有在自身及所有祖先来源均解除后
     * （生效来源集合为空）才恢复 AVAILABLE，解除单一来源不会提前放行。</p>
     */
    @Transactional
    public QuarantineResponse quarantine(QuarantineRequest request) {
        lotRepository.acquireGlobalLock();

        Lot source = lotRepository.lockById(request.lotId());
        if (source == null) {
            throw new NotFoundException("LOT_NOT_FOUND", "批次不存在: " + request.lotId());
        }

        List<String> affectedLotIds;
        if (request.action() == QuarantineRequest.Action.QUARANTINE) {
            affectedLotIds = applyQuarantine(source.lotId(), request.reason());
        } else {
            affectedLotIds = applyRelease(source.lotId(), request.reason());
        }

        // 提交后统一按“生效来源集合是否为空”重算受影响批次状态。
        Map<String, List<String>> activeSources = quarantineRepository.activeSources(affectedLotIds);
        List<QuarantineResponse.AffectedLot> affected = new ArrayList<>();
        for (String lotId : affectedLotIds) {
            List<String> sources = activeSources.getOrDefault(lotId, List.of());
            LotStatus status = sources.isEmpty() ? LotStatus.AVAILABLE : LotStatus.QUARANTINED;
            lotRepository.updateStatus(lotId, status);
            affected.add(new QuarantineResponse.AffectedLot(lotId, status, sources));
        }
        return new QuarantineResponse(request.lotId(), request.action(), List.copyOf(affected));
    }

    /** 设置隔离：向来源自身及全部后代登记生效来源（已存在不重复）；重复设置为幂等。 */
    private List<String> applyQuarantine(String sourceLotId, String reason) {
        // 仅首次设置追加事件流水；重复设置不重复记账，只补齐可能后产生的后代传播。
        boolean firstTime = !quarantineRepository.activeSourceExists(sourceLotId, sourceLotId);
        if (firstTime) {
            quarantineRepository.insertEvent(sourceLotId, "QUARANTINE", reason);
        }

        List<String> targets = new ArrayList<>();
        targets.add(sourceLotId);
        targets.addAll(lotRepository.descendantIds(Set.of(sourceLotId)));

        for (String lotId : targets) {
            if (!quarantineRepository.activeSourceExists(lotId, sourceLotId)) {
                quarantineRepository.activate(lotId, sourceLotId);
            }
        }
        return targets;
    }

    /** 解除隔离：仅解除该来源自身这一条来源，其他祖先来源保持不变。 */
    private List<String> applyRelease(String sourceLotId, String reason) {
        if (!quarantineRepository.activeSourceExists(sourceLotId, sourceLotId)) {
            throw new BusinessRuleException("NOT_QUARANTINED",
                    "批次 " + sourceLotId + " 当前没有自身生效的隔离来源，无法解除");
        }
        quarantineRepository.insertEvent(sourceLotId, "RELEASE", reason);

        List<String> candidates = new ArrayList<>();
        candidates.add(sourceLotId);
        candidates.addAll(lotRepository.descendantIds(Set.of(sourceLotId)));
        List<String> released = quarantineRepository.deactivate(sourceLotId, candidates);
        if (!released.contains(sourceLotId)) {
            // 正常不会发生：来源自身的登记在上面已确认存在。
            throw new IllegalStateException("解除隔离后来源批次记录缺失: " + sourceLotId);
        }
        return released;
    }

    /** 谱系上下游查询：直接父/子边及去重后的全部祖先/后代。 */
    @Transactional(readOnly = true)
    public GenealogyResponse genealogy(String lotId) {
        Lot lot = lotRepository.findById(lotId);
        if (lot == null) {
            throw new NotFoundException("LOT_NOT_FOUND", "批次不存在: " + lotId);
        }
        List<GenealogyEdge> parentEdges = lotRepository.parentsOf(lotId);
        List<GenealogyEdge> childEdges = lotRepository.childrenOf(lotId);
        List<GenealogyResponse.Relative> parents = parentEdges.stream()
                .map(e -> new GenealogyResponse.Relative(e.parentLotId(), e.conversionId(), e.qty()))
                .toList();
        List<GenealogyResponse.Relative> children = childEdges.stream()
                .map(e -> new GenealogyResponse.Relative(e.childLotId(), e.conversionId(), e.qty()))
                .toList();
        return new GenealogyResponse(
                lotId,
                parents,
                children,
                List.copyOf(lotRepository.ancestorIds(Set.of(lotId))),
                List.copyOf(lotRepository.descendantIds(Set.of(lotId))));
    }

    /** 隔离影响面查询：某来源当前仍影响的全部批次及其生效来源集合。 */
    @Transactional(readOnly = true)
    public ImpactResponse impact(String sourceLotId) {
        Lot lot = lotRepository.findById(sourceLotId);
        if (lot == null) {
            throw new NotFoundException("LOT_NOT_FOUND", "批次不存在: " + sourceLotId);
        }
        List<String> impactedIds = quarantineRepository.lotsImpactedBy(sourceLotId);
        Map<String, List<String>> activeSources = quarantineRepository.activeSources(impactedIds);
        Map<String, Lot> lots = lotRepository.findByIds(impactedIds);
        List<ImpactResponse.ImpactedLot> impacted = new ArrayList<>();
        for (String id : impactedIds) {
            Lot current = lots.get(id);
            LotStatus status = current == null ? LotStatus.QUARANTINED : current.status();
            impacted.add(new ImpactResponse.ImpactedLot(
                    id, status, activeSources.getOrDefault(id, List.of())));
        }
        return new ImpactResponse(sourceLotId, List.copyOf(impacted));
    }

    // ===== 转换辅助 =====

    /** 规范化后的转换请求及其指纹/快照。 */
    private static final class NormalizedConversion {
        private final ConversionRequest request;
        private final List<ConversionRequest.InputItem> inputs;
        private final List<ConversionRequest.OutputItem> outputs;
        private final BigDecimal allowedLoss;
        private final String fingerprint;
        private final String requestJson;

        private NormalizedConversion(ConversionRequest request,
                                    List<ConversionRequest.InputItem> inputs,
                                    List<ConversionRequest.OutputItem> outputs,
                                    BigDecimal allowedLoss,
                                    String fingerprint,
                                    String requestJson) {
            this.request = request;
            this.inputs = inputs;
            this.outputs = outputs;
            this.allowedLoss = allowedLoss;
            this.fingerprint = fingerprint;
            this.requestJson = requestJson;
        }
    }

    /** 规范化数量并生成确定性完整请求指纹（数量、损耗、版本与列表顺序全部纳入）。 */
    private NormalizedConversion normalize(ConversionRequest request) {
        List<ConversionRequest.InputItem> inputs = new ArrayList<>();
        for (ConversionRequest.InputItem input : request.inputs()) {
            inputs.add(new ConversionRequest.InputItem(
                    input.lotId(), normalizeQuantity(input.quantity()), input.expectedVersion()));
        }
        List<ConversionRequest.OutputItem> outputs = new ArrayList<>();
        for (ConversionRequest.OutputItem output : request.outputs()) {
            outputs.add(new ConversionRequest.OutputItem(
                    output.lotId(), normalizeQuantity(output.quantity())));
        }
        BigDecimal allowedLoss = normalizeNonNegative(request.allowedLoss(), "允许损耗");
        ConversionRequest canonical = new ConversionRequest(
                request.requestId(), request.unit(), allowedLoss,
                List.copyOf(inputs), List.copyOf(outputs));
        String json = writeJson(canonical);
        String fingerprint = sha256(json);
        return new NormalizedConversion(canonical, List.copyOf(inputs), List.copyOf(outputs),
                allowedLoss, fingerprint, json);
    }

    /** 同键命中：指纹一致重放首次响应；异参（数量、损耗或版本变化）返回 409。 */
    private ConversionResponse replayOrConflict(com.example.starter.lot.domain.ConversionRecord existing,
                                                NormalizedConversion normalized) {
        if (!existing.fingerprint().equals(normalized.fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "requestId " + existing.requestId() + " 已用于参数不同的转换（数量、损耗或版本不一致）");
        }
        try {
            return objectMapper.readValue(existing.responseJson(), ConversionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首次响应快照无法反序列化", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 组装批次视图，附当前生效隔离来源集合。 */
    private LotResponse toLotResponse(Lot lot) {
        List<String> sources = quarantineRepository
                .activeSources(List.of(lot.lotId()))
                .getOrDefault(lot.lotId(), List.of());
        return new LotResponse(
                lot.lotId(),
                lot.unit(),
                lot.initialQty(),
                lot.consumedQty(),
                lot.initialQty().subtract(lot.consumedQty()),
                lot.status(),
                lot.version(),
                sources);
    }
}
