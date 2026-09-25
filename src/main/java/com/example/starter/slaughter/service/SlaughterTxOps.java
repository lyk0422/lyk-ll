package com.example.starter.slaughter.service;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.domain.ChainStatus;
import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.domain.InventoryScope;
import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.domain.PreQuarantineResult;
import com.example.starter.slaughter.dto.CheckoutRequest;
import com.example.starter.slaughter.dto.CheckoutResponse;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionItem;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.dto.InspectionResponse;
import com.example.starter.slaughter.dto.JudgmentItem;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.JudgmentResponse;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.dto.PreQuarantineResponse;
import com.example.starter.slaughter.dto.ReceiptRequest;
import com.example.starter.slaughter.dto.ReceiptResponse;
import com.example.starter.slaughter.dto.ReconciliationResponse;
import com.example.starter.slaughter.persistence.BatchRow;
import com.example.starter.slaughter.persistence.ChainRow;
import com.example.starter.slaughter.persistence.HeadRow;
import com.example.starter.slaughter.persistence.IdempotentRecord;
import com.example.starter.slaughter.persistence.ReceiptRow;
import com.example.starter.slaughter.persistence.SlaughterRepository;
import com.example.starter.slaughter.support.FingerprintCalculator;
import com.example.starter.slaughter.web.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 屠宰检疫业务事务操作：幂等占位、业务写入与响应回填在同一事务内完成。
 */
@Component
public class SlaughterTxOps {

    private static final Logger log = LoggerFactory.getLogger(SlaughterTxOps.class);

    private final SlaughterRepository repository;
    private final FingerprintCalculator fingerprints;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SlaughterTxOps(SlaughterRepository repository,
                          FingerprintCalculator fingerprints,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.repository = repository;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---- 宰前申报 / 重新申报 ----

    @Transactional
    public ServiceOutcome<DeclareResponse> declare(DeclareRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batchNo", request.batchNo());
        fields.put("headNos", request.headNos());
        Idempotency idem = begin(request.requestId(), IdempotentOperation.DECLARE, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, DeclareResponse.class), true, idem.httpStatus);
        }

        Instant now = Instant.now(clock);
        Instant validUntil = now.plusSeconds(SlaughterService.VALID_WINDOW_SECONDS);
        List<String> headNos = normalizedHeads(request.headNos());

        BatchRow existing = repository.findBatch(request.batchNo()).orElse(null);
        if (existing != null && existing.inspectionChainId() != null) {
            throw conflict("SLAUGHTER_ALREADY_STARTED",
                    "批次已开宰（检验链已存在），不能重新申报：" + request.batchNo());
        }
        DeclareResponse response;
        if (existing == null) {
            try {
                repository.insertBatch(request.batchNo(), headNos.size(), now, validUntil);
                repository.insertHeads(request.batchNo(), headNos, now);
            } catch (DuplicateKeyException ex) {
                // 并发申报同一批次：唯一受理，后者冲突。
                throw conflict("BATCH_ALREADY_DECLARED", "批次已受理申报：" + request.batchNo());
            }
            response = new DeclareResponse(request.batchNo(), BatchStatus.DECLARED.name(),
                    headNos.size(), toOffset(now), toOffset(validUntil), 0);
        } else {
            response = redeclareIfExpired(existing, headNos, now);
        }
        finish(idem, HttpStatus.CREATED.value(), response);
        return outcome(response, false, HttpStatus.CREATED.value());
    }

    /** 恰满 6 小时未屠宰：同批次重新申报；窗口内重复申报按冲突处理。 */
    private DeclareResponse redeclareIfExpired(BatchRow batch, List<String> requestedHeads, Instant now) {
        Instant validUntil = batch.validUntilUtc();
        boolean windowOpen = validUntil != null && now.isBefore(validUntil);
        if (windowOpen) {
            throw conflict("BATCH_ALREADY_DECLARED",
                    "批次已在 6 小时申报有效期内，无需重新申报：" + batch.batchNo());
        }
        List<HeadRow> currentHeads = repository.listHeads(batch.batchNo());
        Set<String> currentSet = currentHeads.stream().map(HeadRow::headNo).collect(Collectors.toSet());
        if (!currentSet.equals(new HashSet<>(requestedHeads)) || currentHeads.size() != requestedHeads.size()) {
            throw unprocessable("HEAD_LIST_MISMATCH",
                    "重新申报头只编号须与原申报一致：" + batch.batchNo());
        }
        int updated = repository.updateOnRedeclare(batch.batchNo(), now,
                now.plusSeconds(SlaughterService.VALID_WINDOW_SECONDS), batch.version());
        if (updated == 0) {
            throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batch.batchNo());
        }
        return new DeclareResponse(batch.batchNo(), BatchStatus.DECLARED.name(),
                requestedHeads.size(), toOffset(now),
                toOffset(now.plusSeconds(SlaughterService.VALID_WINDOW_SECONDS)),
                batch.redeclareNo() + 1);
    }

    // ---- 宰前检疫 ----

    @Transactional
    public ServiceOutcome<PreQuarantineResponse> preQuarantine(String batchNo, PreQuarantineRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batchNo", batchNo);
        fields.put("result", request.result().name());
        Idempotency idem = begin(request.requestId(), IdempotentOperation.PRE_QUARANTINE, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, PreQuarantineResponse.class), true, idem.httpStatus);
        }

        Instant now = Instant.now(clock);
        BatchRow batch = requireBatch(batchNo);
        if (batch.status() != BatchStatus.DECLARED) {
            throw conflict("INVALID_BATCH_STATE", "当前状态不允许宰前检疫：" + batch.status());
        }
        ensureWindowOpen(batch, now, "宰前检疫");

        Instant validUntil = request.result() == PreQuarantineResult.PASS ? batch.validUntilUtc() : null;
        int updated = repository.updatePreQuarantine(batchNo, request.result(), now, validUntil, batch.version());
        if (updated == 0) {
            throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batchNo);
        }
        BatchStatus newStatus = request.result() == PreQuarantineResult.PASS
                ? BatchStatus.PRE_QUARANTINED
                : BatchStatus.QUARANTINE_REJECTED;
        PreQuarantineResponse response = new PreQuarantineResponse(
                batchNo, newStatus.name(), request.result().name(),
                toOffset(now), validUntil == null ? null : toOffset(validUntil));
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    // ---- 同步检验 ----

    @Transactional
    public ServiceOutcome<InspectionResponse> registerInspection(String batchNo,
                                                                 InspectionRegisterRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batchNo", batchNo);
        fields.put("items", request.items().stream()
                .map(i -> Map.of("headNo", i.headNo(), "result", i.result().name())).toList());
        Idempotency idem = begin(request.requestId(), IdempotentOperation.INSPECTION_REGISTER, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, InspectionResponse.class), true, idem.httpStatus);
        }

        Instant now = Instant.now(clock);
        BatchRow batch = requireBatch(batchNo);
        List<HeadRow> heads = repository.listHeads(batchNo);
        Map<String, HeadRow> headIndex = heads.stream()
                .collect(Collectors.toMap(HeadRow::headNo, h -> h));

        ChainRow chain;
        if (batch.inspectionChainId() == null) {
            // 未开宰：先校验宰前检疫合格状态，再校验 6 小时窗口（恰满须重新申报）。
            if (batch.status() != BatchStatus.PRE_QUARANTINED
                    || batch.preQuarantineResult() != PreQuarantineResult.PASS) {
                throw conflict("INVALID_BATCH_STATE", "宰前检疫未合格，不得屠宰：" + batch.status());
            }
            ensureWindowOpen(batch, now, "屠宰同步检验");
            try {
                long chainId = repository.insertChain(batchNo, heads.size(), now);
                int bound = repository.updateChainId(batchNo, chainId, BatchStatus.INSPECTING, now,
                        batch.version());
                if (bound == 0) {
                    throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batchNo);
                }
                chain = repository.findChain(batchNo).orElseThrow();
            } catch (DuplicateKeyException ex) {
                // 并发首次登记：唯一检验链已由其他事务开启，复用该 OPEN 链继续追加本批头只。
                BatchRow latest = repository.findBatch(batchNo).orElse(null);
                ChainRow existingChain = repository.findChain(batchNo).orElse(null);
                if (latest == null || existingChain == null
                        || (latest.status() != BatchStatus.INSPECTING && latest.status() != BatchStatus.SUSPENDED)
                        || existingChain.status() != ChainStatus.OPEN) {
                    throw conflict("BATCH_STATE_CHANGED",
                            "检验链正由并发请求开启，请稍后重试登记：" + batchNo);
                }
                chain = existingChain;
            }
        } else {
            if (batch.status() != BatchStatus.INSPECTING && batch.status() != BatchStatus.SUSPENDED) {
                throw conflict("INVALID_BATCH_STATE",
                        "当前状态不允许登记同步检验：" + batch.status());
            }
            chain = repository.findChain(batchNo).orElseThrow();
            if (chain.status() != ChainStatus.OPEN) {
                throw conflict("INSPECTION_CHAIN_CLOSED",
                        "检验链已收口，不能再登记头只：" + batchNo);
            }
        }

        Set<String> seen = new HashSet<>();
        for (InspectionItem item : request.items()) {
            if (!seen.add(item.headNo())) {
                throw unprocessable("DUPLICATE_HEAD_IN_REQUEST",
                        "本次登记存在重复头只编号：" + item.headNo());
            }
            HeadRow head = headIndex.get(item.headNo());
            if (head == null) {
                throw unprocessable("UNKNOWN_HEAD", "头只编号不属于该批次：" + item.headNo());
            }
            if (head.inspectionResult() != null) {
                throw conflict("HEAD_ALREADY_INSPECTED",
                        "头只已登记同步检验，不可重复登记：" + item.headNo());
            }
        }
        for (InspectionItem item : request.items()) {
            int rows = repository.updateInspection(batchNo, item.headNo(), item.result(), now);
            if (rows == 0) {
                throw conflict("HEAD_ALREADY_INSPECTED",
                        "头只已登记同步检验，不可重复登记：" + item.headNo());
            }
        }
        repository.incrementInspectedCount(batchNo, request.items().size());

        InspectionResponse response = currentInspectionView(batchNo);
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    @Transactional
    public ServiceOutcome<InspectionResponse> completeInspection(String batchNo,
                                                                 InspectionCompleteRequest request) {
        Map<String, Object> fields = Map.of("batchNo", batchNo);
        Idempotency idem = begin(request.requestId(), IdempotentOperation.INSPECTION_COMPLETE, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, InspectionResponse.class), true, idem.httpStatus);
        }

        BatchRow batch = requireBatch(batchNo);
        if (batch.status() != BatchStatus.INSPECTING && batch.status() != BatchStatus.SUSPENDED) {
            throw conflict("INVALID_BATCH_STATE", "当前状态不允许检验链收口：" + batch.status());
        }
        ChainRow chain = repository.findChain(batchNo).orElseThrow();
        if (chain.status() == ChainStatus.COMPLETED) {
            InspectionResponse response = currentInspectionView(batchNo);
            finish(idem, HttpStatus.OK.value(), response);
            return outcome(response, false, HttpStatus.OK.value());
        }

        List<HeadRow> heads = repository.listHeads(batchNo);
        int inspected = (int) heads.stream().filter(h -> h.inspectionResult() != null).count();
        int missing = heads.size() - inspected;
        Instant now = Instant.now(clock);
        if (missing > 0) {
            // 漏检头只：整批暂停流转，检验链保持开启，补检后可再次收口。
            repository.updateBatchStatus(batchNo, BatchStatus.SUSPENDED, now, batch.version());
            repository.updateChainStatus(batchNo, ChainStatus.OPEN, inspected, null);
            InspectionResponse response = new InspectionResponse(
                    batchNo, BatchStatus.SUSPENDED.name(), inspected, heads.size(), missing, false);
            finish(idem, HttpStatus.OK.value(), response);
            return outcome(response, false, HttpStatus.OK.value());
        }

        repository.updateChainStatus(batchNo, ChainStatus.COMPLETED, inspected, now);
        int updated = repository.updateBatchStatus(batchNo, BatchStatus.INSPECTED, now, batch.version());
        if (updated == 0) {
            throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batchNo);
        }
        InspectionResponse response = new InspectionResponse(
                batchNo, BatchStatus.INSPECTED.name(), inspected, heads.size(), 0, true);
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    private InspectionResponse currentInspectionView(String batchNo) {
        List<HeadRow> heads = repository.listHeads(batchNo);
        int inspected = (int) heads.stream().filter(h -> h.inspectionResult() != null).count();
        BatchRow fresh = repository.findBatch(batchNo).orElseThrow();
        boolean complete = fresh.status() == BatchStatus.INSPECTED;
        return new InspectionResponse(batchNo, fresh.status().name(), inspected, heads.size(),
                heads.size() - inspected, complete);
    }

    // ---- 判定分流 ----

    @Transactional
    public ServiceOutcome<JudgmentResponse> judge(String batchNo, JudgmentRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batchNo", batchNo);
        fields.put("items", request.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("headNo", i.headNo());
            m.put("category", i.category().name());
            m.put("limitedRegion", i.limitedRegion());
            return m;
        }).toList());
        Idempotency idem = begin(request.requestId(), IdempotentOperation.JUDGMENT, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, JudgmentResponse.class), true, idem.httpStatus);
        }

        Instant now = Instant.now(clock);
        BatchRow batch = requireBatch(batchNo);
        if (batch.status() != BatchStatus.INSPECTED) {
            throw conflict("INVALID_BATCH_STATE",
                    "同步检验未全覆盖收口，不得判定分流：" + batch.status());
        }
        List<HeadRow> heads = repository.listHeads(batchNo);
        Map<String, HeadRow> headIndex = heads.stream()
                .collect(Collectors.toMap(HeadRow::headNo, h -> h));
        if (request.items().size() != heads.size()) {
            throw unprocessable("JUDGMENT_COUNT_MISMATCH",
                    "判定头只数与检验头只数不一致：expected=" + heads.size()
                            + ", actual=" + request.items().size());
        }
        Set<String> seen = new HashSet<>();
        for (JudgmentItem item : request.items()) {
            if (!seen.add(item.headNo())) {
                throw unprocessable("DUPLICATE_HEAD_IN_REQUEST",
                        "判定明细存在重复头只编号：" + item.headNo());
            }
            HeadRow head = headIndex.get(item.headNo());
            if (head == null) {
                throw unprocessable("UNKNOWN_HEAD", "头只编号不属于该批次：" + item.headNo());
            }
            if (item.category() == JudgmentCategory.CONDITIONAL) {
                if (item.limitedRegion() == null || item.limitedRegion().isBlank()) {
                    throw unprocessable("LIMITED_REGION_REQUIRED",
                            "有条件食用须标注限定流通区域：" + item.headNo());
                }
            } else if (item.limitedRegion() != null && !item.limitedRegion().isBlank()) {
                throw unprocessable("LIMITED_REGION_NOT_ALLOWED",
                        "非有条件食用类别不得标注限定流通区域：" + item.headNo());
            }
        }

        // 判定明细与库存标识在同一事务内逐头落库，不得判定合格而库存未标识。
        Map<String, Integer> counts = new HashMap<>();
        try {
            for (JudgmentItem item : request.items()) {
                InventoryScope scope = switch (item.category()) {
                    case QUALIFIED -> InventoryScope.GENERAL;
                    case CONDITIONAL -> InventoryScope.LIMITED_REGION;
                    case RENDERED, DESTROYED -> InventoryScope.NONE;
                };
                int rows = repository.updateJudgment(batchNo, item.headNo(), item.category(),
                        scope, item.category() == JudgmentCategory.CONDITIONAL ? item.limitedRegion() : null, now);
                if (rows == 0) {
                    throw conflict("BATCH_STATE_CHANGED", "头只状态已变化，请重试：" + item.headNo());
                }
                if (scope != InventoryScope.NONE) {
                    repository.insertInventoryMark(batchNo, item.headNo(), item.category(),
                            scope, item.category() == JudgmentCategory.CONDITIONAL ? item.limitedRegion() : null,
                            item.category() == JudgmentCategory.CONDITIONAL, now);
                }
                counts.merge(item.category().name(), 1, Integer::sum);
            }
        } catch (DuplicateKeyException ex) {
            // 并发重复判定时库存标识唯一约束冲突：整笔回滚，已判定受理的那笔生效。
            throw conflict("BATCH_ALREADY_JUDGED", "批次已判定分流，请勿重复判定：" + batchNo);
        }
        int expectedMarks = counts.getOrDefault(JudgmentCategory.QUALIFIED.name(), 0)
                + counts.getOrDefault(JudgmentCategory.CONDITIONAL.name(), 0);
        if (repository.countInventoryMarks(batchNo) != expectedMarks) {
            throw new IllegalStateException("判定与库存标识勾稽失败，事务回滚：" + batchNo);
        }
        int updated = repository.updateJudged(batchNo, now, batch.version());
        if (updated == 0) {
            throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batchNo);
        }

        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        for (JudgmentCategory category : JudgmentCategory.values()) {
            orderedCounts.put(category.name(), counts.getOrDefault(category.name(), 0));
        }
        JudgmentResponse response = new JudgmentResponse(
                batchNo, BatchStatus.JUDGED.name(), orderedCounts);
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    // ---- 无害化处理回执 ----

    @Transactional
    public ServiceOutcome<ReceiptResponse> registerReceipt(String batchNo, ReceiptRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("batchNo", batchNo);
        fields.put("receiptNo", request.receiptNo());
        fields.put("headNos", request.headNos());
        Idempotency idem = begin(request.requestId(), IdempotentOperation.RECEIPT, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, ReceiptResponse.class), true, idem.httpStatus);
        }

        Instant now = Instant.now(clock);
        BatchRow batch = requireBatch(batchNo);
        List<String> receiptHeads = normalizedHeads(request.headNos());
        List<HeadRow> heads = repository.listHeads(batchNo);
        Map<String, HeadRow> headIndex = heads.stream()
                .collect(Collectors.toMap(HeadRow::headNo, h -> h));

        JudgmentCategory category = null;
        for (String headNo : receiptHeads) {
            HeadRow head = headIndex.get(headNo);
            if (head == null) {
                throw unprocessable("UNKNOWN_HEAD", "回执头只不属于该批次：" + headNo);
            }
            if (head.judgmentCategory() != JudgmentCategory.RENDERED
                    && head.judgmentCategory() != JudgmentCategory.DESTROYED) {
                throw unprocessable("RECEIPT_HEAD_NOT_DISPOSAL",
                        "回执头只判定类别不是化制/销毁：" + headNo);
            }
            if (category == null) {
                category = head.judgmentCategory();
            } else if (category != head.judgmentCategory()) {
                throw unprocessable("RECEIPT_CATEGORY_MIXED",
                        "同一回执不得混合化制与销毁头只：" + request.receiptNo());
            }
        }
        if (category == null) {
            throw unprocessable("EMPTY_RECEIPT", "回执头只清单不得为空");
        }

        // 核销终态后迟到：只记迟到事件并告警，不改变批次状态、不闭环头只。
        if (batch.status() == BatchStatus.CHECKED_OUT) {
            repository.insertLateEvent(batchNo, request.receiptNo(), category, receiptHeads.size(), now);
            log.warn("出厂核销后收到迟到无害化处理回执：batch={}, receipt={}, category={}, heads={}",
                    batchNo, request.receiptNo(), category, receiptHeads.size());
            ReceiptResponse response = new ReceiptResponse(
                    request.receiptNo(), batchNo, category.name(), receiptHeads.size(),
                    false, true, true, toOffset(now));
            finish(idem, HttpStatus.OK.value(), response);
            return outcome(response, false, HttpStatus.OK.value());
        }

        if (batch.status() != BatchStatus.JUDGED) {
            throw conflict("INVALID_BATCH_STATE",
                    "判定分流未完成，不能登记无害化处理回执：" + batch.status());
        }

        // 勾稽：回执头只集合须与同类别判定头只集合恰等，不等则留痕拒绝出厂。
        final JudgmentCategory receiptCategory = category;
        Set<String> judgedSet = heads.stream()
                .filter(h -> h.judgmentCategory() == receiptCategory)
                .map(HeadRow::headNo)
                .collect(Collectors.toSet());
        boolean accepted = judgedSet.equals(new HashSet<>(receiptHeads));

        long receiptId;
        try {
            receiptId = repository.insertReceipt(request.receiptNo(), batchNo, category,
                    receiptHeads.size(), accepted, false, false, now);
        } catch (DuplicateKeyException ex) {
            throw conflict("RECEIPT_NO_EXISTS", "回执编号已登记：" + request.receiptNo());
        }
        repository.insertReceiptHeads(receiptId, batchNo, receiptHeads, now);
        if (accepted) {
            for (String headNo : receiptHeads) {
                repository.updateHeadReceipt(batchNo, headNo, receiptId);
            }
        } else {
            log.warn("无害化处理回执勾稽不一致，拒绝出厂：batch={}, receipt={}, judged={}, receipt={}",
                    batchNo, request.receiptNo(), judgedSet, receiptHeads);
        }

        ReceiptResponse response = new ReceiptResponse(
                request.receiptNo(), batchNo, category.name(), receiptHeads.size(),
                accepted, false, false, toOffset(now));
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    // ---- 出厂核销 ----

    @Transactional
    public ServiceOutcome<CheckoutResponse> checkout(String batchNo, CheckoutRequest request) {
        Map<String, Object> fields = Map.of("batchNo", batchNo);
        Idempotency idem = begin(request.requestId(), IdempotentOperation.CHECKOUT, fields);
        if (idem.replayed) {
            return outcome(deserialize(idem.body, CheckoutResponse.class), true, idem.httpStatus);
        }

        BatchRow batch = requireBatch(batchNo);
        Instant now = Instant.now(clock);

        // 终态：重复核销幂等成功（即使 requestId 不同）。
        if (batch.status() == BatchStatus.CHECKED_OUT) {
            CheckoutResponse response = new CheckoutResponse(
                    batchNo, BatchStatus.CHECKED_OUT.name(),
                    toOffset(batch.checkedOutAtUtc() == null ? now : batch.checkedOutAtUtc()));
            finish(idem, HttpStatus.OK.value(), response);
            return outcome(response, true, HttpStatus.OK.value());
        }
        if (batch.status() != BatchStatus.JUDGED) {
            throw conflict("INVALID_BATCH_STATE", "判定分流未完成，不得出厂核销：" + batch.status());
        }

        // 化制/销毁头只须全部取得恰等受理回执闭环，否则拒绝出厂。
        List<HeadRow> heads = repository.listHeads(batchNo);
        List<String> unreceipted = heads.stream()
                .filter(h -> (h.judgmentCategory() == JudgmentCategory.RENDERED
                        || h.judgmentCategory() == JudgmentCategory.DESTROYED))
                .filter(h -> h.receiptId() == null)
                .map(HeadRow::headNo)
                .toList();
        if (!unreceipted.isEmpty()) {
            throw unprocessable("DISPOSAL_NOT_CLOSED",
                    "存在化制/销毁头只无害化处理回执未勾稽闭环，拒绝出厂：" + unreceipted);
        }

        int updated = repository.updateCheckedOut(batchNo, now, batch.version());
        if (updated == 0) {
            throw conflict("BATCH_STATE_CHANGED", "批次状态已变化，请重试：" + batchNo);
        }
        CheckoutResponse response = new CheckoutResponse(
                batchNo, BatchStatus.CHECKED_OUT.name(), toOffset(now));
        finish(idem, HttpStatus.OK.value(), response);
        return outcome(response, false, HttpStatus.OK.value());
    }

    // ---- 台账复算（只读） ----

    /** 由头只台账、判定明细、回执与库存标识实时复算勾稽关系，不依赖派生状态字段。 */
    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(String batchNo) {
        BatchRow batch = requireBatch(batchNo);
        List<HeadRow> heads = repository.listHeads(batchNo);
        List<ReceiptRow> receipts = repository.listReceipts(batchNo);

        int inspected = (int) heads.stream().filter(h -> h.inspectionResult() != null).count();
        List<String> missingHeads = heads.stream()
                .filter(h -> h.inspectionResult() == null).map(HeadRow::headNo).toList();

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (JudgmentCategory category : JudgmentCategory.values()) {
            categoryCounts.put(category.name(), 0);
        }
        Map<JudgmentCategory, Set<String>> judgedByCategory = new HashMap<>();
        heads.stream().filter(h -> h.judgmentCategory() != null).forEach(h -> {
            categoryCounts.merge(h.judgmentCategory().name(), 1, Integer::sum);
            judgedByCategory.computeIfAbsent(h.judgmentCategory(), k -> new HashSet<>()).add(h.headNo());
        });

        int markCount = repository.countInventoryMarks(batchNo);
        int expectedMarks = categoryCounts.get(JudgmentCategory.QUALIFIED.name())
                + categoryCounts.get(JudgmentCategory.CONDITIONAL.name());

        List<ReconciliationResponse.ReceiptView> views = new ArrayList<>();
        boolean receiptConsistent = true;
        for (ReceiptRow receipt : receipts) {
            List<String> receiptHeadList = repository.listReceiptHeads(receipt.id());
            Set<String> receiptHeadSet = new HashSet<>(receiptHeadList);
            Set<String> judgedSet = judgedByCategory.getOrDefault(receipt.category(), Set.of());
            boolean recomputedAccepted = !receipt.late() && judgedSet.equals(receiptHeadSet);
            // 回执头只数列与头只明细行数一致；受理标记与集合勾稽复算一致。
            if (receipt.headCount() != receiptHeadList.size()
                    || receipt.headCount() != receiptHeadSet.size()
                    || receipt.accepted() != recomputedAccepted) {
                receiptConsistent = false;
            }
            views.add(new ReconciliationResponse.ReceiptView(
                    receipt.receiptNo(), receipt.category().name(), receipt.headCount(),
                    receipt.accepted(), receipt.late(), receipt.alerted()));
        }

        List<String> unclosed = heads.stream()
                .filter(h -> (h.judgmentCategory() == JudgmentCategory.RENDERED
                        || h.judgmentCategory() == JudgmentCategory.DESTROYED))
                .filter(h -> h.receiptId() == null)
                .map(HeadRow::headNo)
                .toList();

        int judgedTotal = categoryCounts.values().stream().mapToInt(Integer::intValue).sum();
        boolean countsConsistent = inspected + missingHeads.size() == heads.size()
                && (judgedTotal == 0 || judgedTotal == inspected)
                && markCount == expectedMarks;
        // 勾稽一致：计数与库存标识可复算、受理标记与集合复算一致、化制/销毁头只全部闭环，
        // 且不存在非迟到但被判定不受理的回执（该类回执代表未闭合异常）。
        boolean hasRejectedReceipt = receipts.stream().anyMatch(r -> !r.late() && !r.accepted());
        boolean consistent = countsConsistent && receiptConsistent
                && unclosed.isEmpty() && !hasRejectedReceipt;

        return new ReconciliationResponse(
                batchNo, batch.status().name(), heads.size(), inspected, missingHeads,
                categoryCounts, markCount, views, unclosed,
                repository.countLateEvents(batchNo), consistent);
    }

    // ---- 幂等支撑（事务内） ----

    private record Idempotency(String requestId, IdempotentOperation operation,
                               String fingerprint, boolean replayed, String body, int httpStatus) {
    }

    /**
     * 事务内幂等仲裁：已存在同指纹记录则重放；异指纹 409；不存在则插入占位并执行业务。
     * 占位与业务写入同事务，失败一并回滚。
     */
    private Idempotency begin(String requestId, IdempotentOperation operation,
                              Map<String, Object> fields) {
        String fingerprint = fingerprints.calculate(operation.name(), requestId, fields);
        Optional<IdempotentRecord> existing = repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            IdempotentRecord record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENT_CONFLICT",
                        "requestId 已用于不同业务参数的请求：" + requestId);
            }
            return new Idempotency(requestId, operation, fingerprint, true,
                    record.responseBody(), record.httpStatus());
        }
        IdempotentRecord placeholder = new IdempotentRecord(requestId, operation.name(), fingerprint,
                HttpStatus.OK.value(), "", Instant.now(clock));
        try {
            repository.insertIdempotent(placeholder);
        } catch (DuplicateKeyException ex) {
            // 并发同 requestId：占位被抢先插入，交由无事务编排层等待胜者提交后仲裁。
            throw new ConcurrentClaimException(requestId, fingerprint);
        }
        return new Idempotency(requestId, operation, fingerprint, false, null, HttpStatus.OK.value());
    }

    private void finish(Idempotency idem, int httpStatus, Object response) {
        repository.updateIdempotentResponse(idem.requestId(), httpStatus, serialize(response));
    }

    // ---- 通用校验与工具 ----

    private BatchRow requireBatch(String batchNo) {
        return repository.findBatch(batchNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "屠宰批次不存在：" + batchNo));
    }

    /** 6 小时（UTC）窗口校验；恰满（now >= validUntil）即失效，须重新申报。 */
    private void ensureWindowOpen(BatchRow batch, Instant now, String action) {
        Instant validUntil = batch.validUntilUtc();
        if (validUntil == null || !now.isBefore(validUntil)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "QUARANTINE_WINDOW_EXPIRED",
                    action + "已超过申报起 6 小时有效期（恰满即失效），须重新申报：" + batch.batchNo());
        }
    }

    private List<String> normalizedHeads(List<String> raw) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String headNo : raw) {
            if (headNo == null || headNo.isBlank()) {
                throw unprocessable("INVALID_HEAD_NO", "头只编号不得为空");
            }
            if (!seen.add(headNo)) {
                throw unprocessable("DUPLICATE_HEAD_NO", "头只编号不得重复：" + headNo);
            }
            result.add(headNo);
        }
        return result;
    }

    private static <T> ServiceOutcome<T> outcome(T data, boolean replayed, int httpStatus) {
        return new ServiceOutcome<>(data, replayed, httpStatus);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
