package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.ChargebackOrder;
import com.example.rebate.domain.ChargebackStatus;
import com.example.rebate.domain.ClawbackAllocation;
import com.example.rebate.domain.ClawbackBatchReceipt;
import com.example.rebate.domain.ClawbackDetail;
import com.example.rebate.domain.ClawbackStatus;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.repo.BankSerialRegistryRepository;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.ChargebackOrderRepository;
import com.example.rebate.repo.ClawbackAllocationRepository;
import com.example.rebate.repo.ClawbackBatchReceiptRepository;
import com.example.rebate.repo.ClawbackDetailRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.ClawbackBatchReceiptRequest;
import com.example.rebate.web.dto.ClawbackBatchReceiptView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 汇总到账分配：一笔真实银行流水（母凭证）分给同一结案批次内 1 至 10 张不同的已批准冲回单，
 * 不拆成多笔伪银行流水。
 *
 * <p>母凭证、全部分配行与各目标追缴明细累加在同一事务内提交，不提前增加某单已收额；
 * 母流水与旧单笔回执流水共享全局互斥登记，同一真实流水只能用于其中一种入口。
 * 同流水同内容（批次、总额、时刻、分配对象与金额）重放首份分配快照，异内容 409；
 * 每条分配行携带目标追缴明细 expectedVersion，成功加 1，旧版 409。</p>
 */
@Service
public class ClawbackAllocationService {

    /** 互斥登记来源：汇总母凭证。 */
    static final String SOURCE_AGGREGATE = "AGGREGATE";
    /** 互斥登记来源：旧单笔回执。 */
    static final String SOURCE_SINGLE = "SINGLE";

    private final BatchRepository batchRepo;
    private final ChargebackOrderRepository orderRepo;
    private final ClawbackDetailRepository clawbackRepo;
    private final ClawbackBatchReceiptRepository receiptRepo;
    private final ClawbackAllocationRepository allocationRepo;
    private final BankSerialRegistryRepository registryRepo;
    private final FingerprintService fingerprints;
    private final ObjectMapper objectMapper;
    private final TimeService timeService;

    public ClawbackAllocationService(BatchRepository batchRepo,
                                     ChargebackOrderRepository orderRepo,
                                     ClawbackDetailRepository clawbackRepo,
                                     ClawbackBatchReceiptRepository receiptRepo,
                                     ClawbackAllocationRepository allocationRepo,
                                     BankSerialRegistryRepository registryRepo,
                                     FingerprintService fingerprints,
                                     ObjectMapper objectMapper,
                                     TimeService timeService) {
        this.batchRepo = batchRepo;
        this.orderRepo = orderRepo;
        this.clawbackRepo = clawbackRepo;
        this.receiptRepo = receiptRepo;
        this.allocationRepo = allocationRepo;
        this.registryRepo = registryRepo;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
        this.timeService = timeService;
    }

    /**
     * 汇总分配入账。校验（同批、已批准、合计相等、各自不超未收回额、版本匹配）任一失败全笔拒绝；
     * 成功后母凭证只记一次，各目标单已收额同事务累加，恰收齐自动转完成。
     */
    @Transactional
    public ServiceResult allocate(String batchNo, ClawbackBatchReceiptRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        List<ClawbackBatchReceiptRequest.AllocationLine> lines = req.allocations();

        Set<String> seen = new HashSet<>();
        for (ClawbackBatchReceiptRequest.AllocationLine line : lines) {
            if (!seen.add(line.correctionNo())) {
                throw new UnprocessableException("分配对象重复: " + line.correctionNo());
            }
        }
        long sum = lines.stream().mapToLong(ClawbackBatchReceiptRequest.AllocationLine::amountCents).sum();
        if (sum != req.totalCents()) {
            throw new UnprocessableException(String.format(
                    "分配合计 %,d 分与到账总额 %,d 分不一致，全笔拒绝", sum, req.totalCents()));
        }
        String contentFp = contentFingerprint(batchNo, req);

        // 自然去重预检：同流水已被任一入口占用
        Optional<String> occupied = registryRepo.findSource(req.bankSerial());
        if (occupied.isPresent()) {
            return replayOrConflict(req.bankSerial(), occupied.get(), contentFp);
        }

        // 逐行校验：属于该结案批次、已批准、不超未收回额、版本匹配
        record Resolved(ClawbackBatchReceiptRequest.AllocationLine line,
                        ChargebackOrder order, ClawbackDetail detail) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ClawbackBatchReceiptRequest.AllocationLine line : lines) {
            ChargebackOrder order = orderRepo
                    .findByBatchIdAndCorrectionNo(batch.id(), line.correctionNo())
                    .orElseThrow(() -> new NotFoundException(
                            "冲回单 " + line.correctionNo() + " 不属于结案批次 " + batchNo));
            if (order.status() != ChargebackStatus.APPROVED) {
                throw new UnprocessableException("仅已批准冲回单可参与汇总分配，"
                        + line.correctionNo() + " 当前状态 " + order.status());
            }
            ClawbackDetail detail = clawbackRepo.findByOrderId(order.id())
                    .orElseThrow(() -> new UnprocessableException("追缴明细不存在: " + line.correctionNo()));
            long remaining = detail.approvedCents() - detail.collectedCents();
            if (line.amountCents() > remaining) {
                throw new UnprocessableException(String.format(
                        "冲回单 %s 分配 %,d 分超过未收回金额 %,d 分，全笔拒绝",
                        line.correctionNo(), line.amountCents(), remaining));
            }
            if (detail.version() != line.expectedVersion()) {
                throw new ConflictException("冲回单 " + line.correctionNo()
                        + " 追缴明细 expectedVersion=" + line.expectedVersion()
                        + " 与当前版本 " + detail.version() + " 不匹配");
            }
            resolved.add(new Resolved(line, order, detail));
        }
        // 统一加锁顺序（流水登记 → 按追缴明细主键排序累加），避免并发分配间死锁
        resolved.sort(Comparator.comparingLong(r -> r.detail().id()));

        Instant now = timeService.now();
        // 全局互斥占坑：并发同流水在此串行，后到者按自然去重语义处理
        if (!registryRepo.tryRegister(req.bankSerial(), SOURCE_AGGREGATE, now)) {
            String source = registryRepo.findSource(req.bankSerial()).orElse(SOURCE_SINGLE);
            return replayOrConflict(req.bankSerial(), source, contentFp);
        }

        // 母凭证只记一次
        long receiptId = receiptRepo.insert(new ClawbackBatchReceipt(null, batch.id(),
                req.bankSerial(), req.totalCents(), req.receivedAt(), contentFp, 1, now));

        // 分配行 + 逐单条件累加（版本与超额由数据库条件更新兜底）
        List<ClawbackBatchReceiptView.AllocationView> lineViews = new ArrayList<>();
        int lineNo = 0;
        for (Resolved r : resolved) {
            lineNo++;
            int updated = clawbackRepo.tryAddReceived(r.detail().id(), r.line().amountCents(),
                    r.line().expectedVersion(), now);
            if (updated == 0) {
                ClawbackDetail fresh = clawbackRepo.findById(r.detail().id()).orElseThrow();
                if (fresh.version() != r.line().expectedVersion()) {
                    throw new ConflictException("冲回单 " + r.order().correctionNo()
                            + " 追缴明细已被并发到账更新，expectedVersion 不匹配");
                }
                if (fresh.status() == ClawbackStatus.COMPLETED) {
                    throw new UnprocessableException(
                            "冲回单 " + r.order().correctionNo() + " 追缴已完成，不能继续分配");
                }
                throw new UnprocessableException(
                        "冲回单 " + r.order().correctionNo() + " 累计到账不得超过批准金额");
            }
            long allocationId = allocationRepo.insert(new ClawbackAllocation(null, receiptId,
                    r.detail().id(), r.order().id(), lineNo, r.line().amountCents(), now));
            ClawbackDetail after = clawbackRepo.findById(r.detail().id()).orElseThrow();
            lineViews.add(new ClawbackBatchReceiptView.AllocationView(allocationId, lineNo,
                    r.order().correctionNo(), after.clawbackNo(), r.order().id(),
                    r.line().amountCents(), after.collectedCents(),
                    after.approvedCents() - after.collectedCents(),
                    after.status().name(), after.version()));
        }

        ClawbackBatchReceiptView view = new ClawbackBatchReceiptView(receiptId, batch.batchNo(),
                req.bankSerial(), req.totalCents(), req.receivedAt(), 1, now,
                List.copyOf(lineViews));
        receiptRepo.saveSnapshot(receiptId, fingerprints.toJson(view));
        return ServiceResult.ok(view);
    }

    /**
     * 查询汇总母凭证（含分配行），返回首份分配快照，与入账响应一致。
     */
    @Transactional(readOnly = true)
    public JsonNode getReceipt(String batchNo, String bankSerial) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        receiptRepo.findBySerial(bankSerial)
                .filter(r -> r.batchId().equals(batch.id()))
                .orElseThrow(() -> new NotFoundException(
                        "汇总母凭证不存在: 批次 " + batchNo + " 流水 " + bankSerial));
        String snapshot = receiptRepo.findSnapshot(bankSerial).orElseThrow();
        return readTree(snapshot);
    }

    /**
     * 同流水已被占用：汇总入口同内容重放首份分配快照，异内容 409；被旧单笔入口占用一律 409。
     */
    private ServiceResult replayOrConflict(String bankSerial, String source, String contentFp) {
        if (!SOURCE_AGGREGATE.equals(source)) {
            throw new ConflictException(
                    "银行流水 " + bankSerial + " 已被单笔回执入口占用，汇总分配不得借用");
        }
        ClawbackBatchReceipt existing = receiptRepo.findBySerial(bankSerial)
                .orElseThrow(() -> new ConflictException(
                        "银行流水 " + bankSerial + " 登记状态异常"));
        if (!existing.contentFingerprint().equals(contentFp)) {
            throw new ConflictException(
                    "银行流水 " + bankSerial + " 已登记不同内容的汇总分配（对象、金额或时刻不一致）");
        }
        String snapshot = receiptRepo.findSnapshot(bankSerial).orElseThrow();
        return ServiceResult.ok(readTree(snapshot));
    }

    /**
     * 分配内容指纹：批次、流水、总额、到账时刻与分配对象金额（按更正编号排序，与行顺序无关）。
     */
    private String contentFingerprint(String batchNo, ClawbackBatchReceiptRequest req) {
        List<Map<String, Object>> lines = req.allocations().stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("correctionNo", l.correctionNo());
                    m.put("amountCents", l.amountCents());
                    return m;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("correctionNo")))
                .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("batchNo", batchNo);
        content.put("bankSerial", req.bankSerial());
        content.put("totalCents", req.totalCents());
        content.put("receivedAt", req.receivedAt().toString());
        content.put("allocations", lines);
        return fingerprints.fingerprint("CLAWBACK_BATCH_ALLOC_CONTENT", req.bankSerial(), content);
    }

    private DeclarationBatch loadClosedBatch(String batchNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException(
                    "仅已结案批次允许汇总分配，当前状态 " + batch.status());
        }
        return batch;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("母凭证快照反序列化失败", e);
        }
    }
}
