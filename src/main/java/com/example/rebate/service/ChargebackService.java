package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.ChargebackBalance;
import com.example.rebate.domain.ChargebackOrder;
import com.example.rebate.domain.ChargebackStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.ClawbackAllocation;
import com.example.rebate.domain.ClawbackDetail;
import com.example.rebate.domain.ClawbackMasterReceipt;
import com.example.rebate.domain.ClawbackReceipt;
import com.example.rebate.domain.ClawbackStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.repo.BankSerialRepository;
import com.example.rebate.repo.ChargebackBalanceRepository;
import com.example.rebate.repo.ChargebackOrderRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.ClawbackAllocationRepository;
import com.example.rebate.repo.ClawbackDetailRepository;
import com.example.rebate.repo.ClawbackMasterReceiptRepository;
import com.example.rebate.repo.ClawbackReceiptRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.BadRequestException;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.ChargebackBalanceView;
import com.example.rebate.web.dto.ChargebackSubmitRequest;
import com.example.rebate.web.dto.ChargebackReviewRequest;
import com.example.rebate.web.dto.ChargebackView;
import com.example.rebate.web.dto.ClawbackAllocationRequest;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import com.example.rebate.web.dto.ClawbackView;
import com.example.rebate.web.dto.MasterAllocationView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结案后分批冲回与追缴。
 *
 * <p>只引用已结案批次内一张报关单及其冻结退税额，不修改原文库版本、核销流水或结案总额；
 * 原批次始终保持 CLOSED。批准在同一事务内以条件更新占用剩余可冲额并产生独立追缴明细，
 * 驳回不占额；追缴回执支持多笔部分到账、同流水幂等、异金额拒绝。</p>
 */
@Service
public class ChargebackService {

    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final ChargebackBalanceRepository balanceRepo;
    private final ChargebackOrderRepository orderRepo;
    private final ClawbackDetailRepository clawbackRepo;
    private final ClawbackReceiptRepository receiptRepo;
    private final BankSerialRepository bankSerialRepo;
    private final ClawbackMasterReceiptRepository masterRepo;
    private final ClawbackAllocationRepository allocationRepo;
    private final FingerprintService fingerprints;
    private final ObjectMapper objectMapper;
    private final TimeService timeService;

    public ChargebackService(BatchRepository batchRepo,
                             CheckDetailRepository detailRepo,
                             ChargebackBalanceRepository balanceRepo,
                             ChargebackOrderRepository orderRepo,
                             ClawbackDetailRepository clawbackRepo,
                             ClawbackReceiptRepository receiptRepo,
                             BankSerialRepository bankSerialRepo,
                             ClawbackMasterReceiptRepository masterRepo,
                             ClawbackAllocationRepository allocationRepo,
                             FingerprintService fingerprints,
                             ObjectMapper objectMapper,
                             TimeService timeService) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.balanceRepo = balanceRepo;
        this.orderRepo = orderRepo;
        this.clawbackRepo = clawbackRepo;
        this.receiptRepo = receiptRepo;
        this.bankSerialRepo = bankSerialRepo;
        this.masterRepo = masterRepo;
        this.allocationRepo = allocationRepo;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
        this.timeService = timeService;
    }

    /**
     * 提交冲回单（初版 PENDING，version=1）。同批次同更正编号最多一单；
     * 累计（已批准占用 + 本单）不得超过冻结退税额，恰归零合法。
     */
    @Transactional
    public ChargebackView submit(String batchNo, ChargebackSubmitRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        CheckDetail detail = findBatchDetail(batch.id(), req.declarationNo());
        Instant now = timeService.now();

        if (orderRepo.findByBatchIdAndCorrectionNo(batch.id(), req.correctionNo()).isPresent()) {
            throw new ConflictException("同批次更正编号已存在: " + req.correctionNo());
        }

        long frozenCents = frozenCents(detail);
        ChargebackBalance balance = balanceRepo.find(batch.id(), req.declarationNo())
                .orElseGet(() -> createBalance(batch.id(), req.declarationNo(), frozenCents, now));
        if (req.amountCents() > balance.remainingCents()) {
            throw new UnprocessableException(String.format(
                    "冲回金额 %,d 分超过剩余可冲额 %,d 分（冻结 %,d 分，已占用 %,d 分）",
                    req.amountCents(), balance.remainingCents(),
                    balance.frozenCents(), balance.occupiedCents()));
        }

        ChargebackOrder order = new ChargebackOrder(null, batch.id(), detail.id(),
                req.declarationNo(), req.correctionNo(), req.reason(), req.amountCents(),
                ChargebackStatus.PENDING, req.submitter(), null, 1, now, null, now, now);
        long orderId;
        try {
            orderId = orderRepo.insert(order);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同批次更正编号已存在: " + req.correctionNo());
        }
        return view(batch, orderRepo.findById(orderId).orElseThrow());
    }

    /**
     * 复核：APPROVE 批准（同事务占用余额并产生追缴明细）或 REJECT 驳回（不占额）。
     * 复核人必须不同于提交人；携带 expectedVersion 做乐观并发控制。
     */
    @Transactional
    public ChargebackView review(String batchNo, String correctionNo, ChargebackReviewRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        ChargebackOrder order = loadOrder(batch, correctionNo);
        boolean approve = switch (req.action()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new BadRequestException("非法复核动作: " + req.action()
                    + "（仅支持 APPROVE/REJECT）");
        };
        if (order.status() != ChargebackStatus.PENDING) {
            throw new ConflictException("冲回单当前状态 " + order.status() + "，不可重复复核");
        }
        if (order.version() != req.expectedVersion()) {
            throw new ConflictException("expectedVersion=" + req.expectedVersion()
                    + " 与当前版本 " + order.version() + " 不匹配");
        }
        if (order.submitter().equals(req.reviewer())) {
            throw new UnprocessableException("复核人必须不同于提交人: " + req.reviewer());
        }

        Instant now = timeService.now();
        ChargebackStatus target = approve ? ChargebackStatus.APPROVED : ChargebackStatus.REJECTED;
        int updated = orderRepo.casReview(order.id(), target, req.reviewer(),
                req.expectedVersion(), now);
        if (updated == 0) {
            throw new ConflictException("冲回单已被并发复核或版本已变化");
        }

        if (approve) {
            // 条件占用：累计不超冻结才生效；并发两笔批准由此串行且不得超冲。
            int occupied = balanceRepo.tryOccupy(
                    batch.id(), order.declarationNo(), order.amountCents(), now);
            if (occupied == 0) {
                throw new UnprocessableException("剩余可冲额不足，批准失败");
            }
            String clawbackNo = clawbackRepo.nextClawbackNo();
            ClawbackDetail detail = new ClawbackDetail(null, order.id(), batch.id(), clawbackNo,
                    order.amountCents(), 0L, ClawbackStatus.COLLECTING, 1, now, null);
            clawbackRepo.insert(detail);
        }
        return view(batch, orderRepo.findById(order.id()).orElseThrow());
    }

    /**
     * 登记追缴到账回执。仅已批准冲回单可登记；可多笔部分到账，累计不超批准金额，恰收齐转完成；
     * 同流水同金额幂等返回，同流水异金额拒绝；完成后只读；乱序到账不改变总额。
     */
    @Transactional
    public ChargebackView registerReceipt(String batchNo, String correctionNo,
                                          ClawbackReceiptRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        ChargebackOrder order = loadOrder(batch, correctionNo);
        if (order.status() != ChargebackStatus.APPROVED) {
            throw new UnprocessableException("未批准冲回单不能登记追缴回执，当前状态 "
                    + order.status());
        }
        ClawbackDetail detail = clawbackRepo.findByOrderId(order.id())
                .orElseThrow(() -> new UnprocessableException("追缴明细不存在"));

        // 流水号已存在：同金额视为重复回执（乱序/重放，不改总额），异金额拒绝。
        List<ClawbackReceipt> sameSerial = receiptRepo.findBySerial(req.receiptSerial());
        if (!sameSerial.isEmpty()) {
            ClawbackReceipt prior = sameSerial.get(0);
            if (prior.receivedCents() != req.receivedCents()) {
                throw new ConflictException(String.format(
                        "追缴流水号 %s 已登记金额 %,d 分，与本次 %,d 分不一致",
                        req.receiptSerial(), prior.receivedCents(), req.receivedCents()));
            }
            return view(batch, order);
        }

        // 全局互斥：已用作汇总分配母流水的真实流水，单笔入口不得借用。
        Optional<String> serialKind = bankSerialRepo.findKind(req.receiptSerial());
        if (serialKind.isPresent() && BankSerialRepository.KIND_MASTER.equals(serialKind.get())) {
            throw new ConflictException(
                    "追缴流水号 " + req.receiptSerial() + " 已用于汇总分配母流水，单笔入口不得借用");
        }

        if (detail.status() == ClawbackStatus.COMPLETED) {
            throw new UnprocessableException("追缴已完成，回执只读");
        }
        if (detail.version() != req.expectedVersion()) {
            throw new ConflictException("expectedVersion=" + req.expectedVersion()
                    + " 与追缴明细当前版本 " + detail.version() + " 不匹配");
        }
        if (req.receivedCents() > detail.approvedCents() - detail.collectedCents()) {
            throw new UnprocessableException(String.format(
                    "本笔 %,d 分将使累计到账超过批准金额 %,d 分（已收 %,d 分）",
                    req.receivedCents(), detail.approvedCents(), detail.collectedCents()));
        }

        // 登记真实流水用途（与汇总入口共享全局互斥，先登记再变动，失败随事务回滚不占号）；
        // 并发撞号时按已提交内容判定：母流水 409，同额单笔幂等返回，异额单笔 409。
        if (!bankSerialRepo.tryRegister(req.receiptSerial(), BankSerialRepository.KIND_SINGLE,
                timeService.now())) {
            Optional<String> raced = bankSerialRepo.findKind(req.receiptSerial());
            if (raced.isPresent() && BankSerialRepository.KIND_MASTER.equals(raced.get())) {
                throw new ConflictException(
                        "追缴流水号 " + req.receiptSerial() + " 已用于汇总分配母流水，单笔入口不得借用");
            }
            List<ClawbackReceipt> racedReceipt = receiptRepo.findBySerial(req.receiptSerial());
            if (!racedReceipt.isEmpty()
                    && racedReceipt.get(0).receivedCents() == req.receivedCents()) {
                return view(batch, order);
            }
            throw new ConflictException("追缴流水号 " + req.receiptSerial() + " 并发登记冲突");
        }

        int updated = clawbackRepo.tryAddReceived(detail.id(), req.receivedCents(),
                req.expectedVersion(), timeService.now());
        if (updated == 0) {
            // 并发下重读以给出准确语义（版本冲突/已完成/超收）。
            ClawbackDetail fresh = clawbackRepo.findById(detail.id()).orElseThrow();
            if (fresh.status() == ClawbackStatus.COMPLETED) {
                throw new UnprocessableException("追缴已完成，回执只读");
            }
            if (fresh.version() != req.expectedVersion()) {
                throw new ConflictException("追缴明细已被并发到账更新，expectedVersion 不匹配");
            }
            throw new UnprocessableException("累计到账不得超过批准金额");
        }

        ClawbackDetail updatedDetail = clawbackRepo.findById(detail.id()).orElseThrow();
        ClawbackReceipt receipt = new ClawbackReceipt(null, updatedDetail.id(), order.id(),
                req.receiptSerial(), req.receivedCents(), req.receivedAt(), timeService.now());
        receiptRepo.insert(receipt);
        return view(batch, order);
    }

    /**
     * 查询单笔冲回单（含余额与追缴明细、回执）。
     */
    @Transactional(readOnly = true)
    public ChargebackView getOrder(String batchNo, String correctionNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        return view(batch, loadOrder(batch, correctionNo));
    }

    /**
     * 逐单冲回余额查询：返回结案批次内每张报关单的冻结、已占用与剩余可冲额；
     * 尚无冲回单的报关单按已占用 0 派生。
     */
    @Transactional(readOnly = true)
    public List<ChargebackBalanceView> balances(String batchNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        Map<String, ChargebackBalance> occupied = new java.util.LinkedHashMap<>();
        for (ChargebackBalance b : balanceRepo.findByBatchId(batch.id())) {
            occupied.put(b.declarationNo(), b);
        }
        return detailRepo.findByBatchId(batch.id()).stream()
                .map(d -> {
                    ChargebackBalance b = occupied.get(d.declarationNo());
                    long frozen = frozenCents(d);
                    long used = b == null ? 0L : b.occupiedCents();
                    return new ChargebackBalanceView(batch.batchNo(), d.declarationNo(),
                            frozen, used, frozen - used);
                })
                .toList();
    }

    private DeclarationBatch loadClosedBatch(String batchNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException(
                    "仅已结案批次允许冲回，当前状态 " + batch.status());
        }
        return batch;
    }

    private ChargebackOrder loadOrder(DeclarationBatch batch, String correctionNo) {
        return orderRepo.findByBatchIdAndCorrectionNo(batch.id(), correctionNo)
                .orElseThrow(() -> new NotFoundException(
                        "冲回单不存在: 批次 " + batch.batchNo() + " 更正编号 " + correctionNo));
    }

    private CheckDetail findBatchDetail(long batchId, String declarationNo) {
        return detailRepo.findByBatchId(batchId).stream()
                .filter(d -> d.declarationNo().equals(declarationNo))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "报关单 " + declarationNo + " 不在该结案批次内"));
    }

    private ChargebackBalance createBalance(long batchId, String declarationNo,
                                            long frozenCents, Instant now) {
        balanceRepo.insert(batchId, declarationNo, frozenCents, now);
        return balanceRepo.find(batchId, declarationNo).orElseThrow();
    }

    /** 冻结退税额：结案明细计税依据 ×100，四舍五入到整数分（合成换算规则）。 */
    private static long frozenCents(CheckDetail detail) {
        BigDecimal basis = detail.taxBasis();
        if (basis == null) {
            return 0L;
        }
        return basis.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private ChargebackView view(DeclarationBatch batch, ChargebackOrder order) {
        ChargebackBalance balance =
                balanceRepo.find(batch.id(), order.declarationNo()).orElseThrow();
        ClawbackView clawbackView = null;
        ClawbackDetail clawback = clawbackRepo.findByOrderId(order.id()).orElse(null);
        if (clawback != null) {
            List<ClawbackView.ReceiptView> receipts =
                    receiptRepo.findByDetailId(clawback.id()).stream()
                            .map(r -> new ClawbackView.ReceiptView(r.id(), r.receiptSerial(),
                                    r.receivedCents(), r.receivedAt(), r.createdAt()))
                            .toList();
            clawbackView = new ClawbackView(clawback.id(), clawback.clawbackNo(),
                    clawback.approvedCents(), clawback.collectedCents(),
                    clawback.approvedCents() - clawback.collectedCents(),
                    clawback.status().name(), clawback.version(),
                    clawback.createdAt(), clawback.completedAt(), receipts);
        }
        return new ChargebackView(order.id(), batch.batchNo(), order.declarationNo(),
                order.checkDetailId(), order.correctionNo(), order.reason(), order.amountCents(),
                order.status().name(), order.submitter(), order.reviewer(), order.version(),
                order.submittedAt(), order.reviewedAt(),
                balance.frozenCents(), balance.occupiedCents(), balance.remainingCents(),
                clawbackView);
    }

    /**
     * 汇总分配：一笔真实到账分给同一结案批次内 1 至 10 张不同的已批准冲回单。
     *
     * <p>母凭证只记一次，各子分配与母流水在同一事务提交，不提前增加某单已收额；
     * 待复核、已驳回、跨批、版本不符或任一超额均整笔拒绝（事务回滚）。同流水同内容
     * 重放首份分配结果，异内容（分配对象、金额或时刻）409；与旧单笔回执共享全局
     * 流水互斥。每单恰收齐自动转完成，未收齐保留在途，原批次始终结案。</p>
     *
     * @return 首次执行为 {@link MasterAllocationView}；同流水同内容重放为首份结果快照
     */
    @Transactional
    public Object allocateMaster(String batchNo, ClawbackAllocationRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        List<ClawbackAllocationRequest.Item> items = req.allocations();

        // 结构校验：分配对象互不重复，正数合计精确等于到账总额。
        Set<String> seen = new HashSet<>();
        long sum = 0L;
        for (ClawbackAllocationRequest.Item item : items) {
            if (!seen.add(item.correctionNo())) {
                throw new UnprocessableException("分配对象重复: " + item.correctionNo());
            }
            sum += item.amountCents();
        }
        if (sum != req.totalCents()) {
            throw new UnprocessableException(String.format(
                    "分配合计 %,d 分与到账总额 %,d 分不一致", sum, req.totalCents()));
        }

        String contentFp = masterContentFingerprint(batchNo, req);

        // 全局互斥：同一真实流水只能用于汇总或单笔其中一种入口。
        Optional<String> kind = bankSerialRepo.findKind(req.masterSerial());
        if (kind.isPresent()) {
            if (BankSerialRepository.KIND_SINGLE.equals(kind.get())) {
                throw new ConflictException(
                        "银行流水 " + req.masterSerial() + " 已用于单笔追缴回执，汇总入口不得借用");
            }
            return replayMaster(req.masterSerial(), contentFp);
        }

        // 业务校验（全部通过才落库；任一失败整笔拒绝，不提前增加某单已收额）。
        // 目标按追缴明细主键排序，保证并发汇总/单笔以一致顺序加锁，避免死锁。
        record Target(ClawbackAllocationRequest.Item item, ChargebackOrder order,
                      ClawbackDetail detail) {
        }
        List<Target> targets = new ArrayList<>();
        for (ClawbackAllocationRequest.Item item : items) {
            ChargebackOrder order = loadOrder(batch, item.correctionNo());
            if (order.status() != ChargebackStatus.APPROVED) {
                throw new UnprocessableException("冲回单 " + item.correctionNo()
                        + " 当前状态 " + order.status() + "，仅已批准单可参与汇总分配");
            }
            ClawbackDetail detail = clawbackRepo.findByOrderId(order.id())
                    .orElseThrow(() -> new UnprocessableException(
                            "追缴明细不存在: " + item.correctionNo()));
            if (detail.version() != item.expectedVersion()) {
                throw new ConflictException("冲回单 " + item.correctionNo()
                        + " expectedVersion=" + item.expectedVersion()
                        + " 与追缴明细当前版本 " + detail.version() + " 不匹配");
            }
            long remaining = detail.approvedCents() - detail.collectedCents();
            if (item.amountCents() > remaining) {
                throw new UnprocessableException(String.format(
                        "冲回单 %s 分配 %,d 分超过未收回金额 %,d 分（批准 %,d 分，已收 %,d 分）",
                        item.correctionNo(), item.amountCents(), remaining,
                        detail.approvedCents(), detail.collectedCents()));
            }
            targets.add(new Target(item, order, detail));
        }
        targets.sort(Comparator.comparingLong(t -> t.detail().id()));

        Instant now = timeService.now();
        // 登记母流水用途（并发下唯一）；撞号说明被并发登记，按已提交内容判定。
        if (!bankSerialRepo.tryRegister(req.masterSerial(), BankSerialRepository.KIND_MASTER, now)) {
            Optional<String> raced = bankSerialRepo.findKind(req.masterSerial());
            if (raced.isPresent() && BankSerialRepository.KIND_SINGLE.equals(raced.get())) {
                throw new ConflictException(
                        "银行流水 " + req.masterSerial() + " 已用于单笔追缴回执，汇总入口不得借用");
            }
            return replayMaster(req.masterSerial(), contentFp);
        }

        // 母凭证与各子分配同一事务提交。
        long masterId = masterRepo.insert(new ClawbackMasterReceipt(null, batch.id(),
                req.masterSerial(), req.totalCents(), req.receivedAt(), contentFp, null, 1, now));

        List<MasterAllocationView.Item> viewItems = new ArrayList<>();
        for (Target target : targets) {
            ClawbackAllocationRequest.Item item = target.item();
            ClawbackDetail detail = target.detail();
            int updated = clawbackRepo.tryAddReceived(detail.id(), item.amountCents(),
                    item.expectedVersion(), now);
            if (updated == 0) {
                // 并发下重读以给出准确语义；整笔随事务回滚。
                ClawbackDetail fresh = clawbackRepo.findById(detail.id()).orElseThrow();
                if (fresh.version() != item.expectedVersion()) {
                    throw new ConflictException("冲回单 " + item.correctionNo()
                            + " 已被并发到账更新，expectedVersion 不匹配");
                }
                throw new UnprocessableException(
                        "冲回单 " + item.correctionNo() + " 累计到账不得超过批准金额");
            }
            String allocationNo = allocationRepo.nextAllocationNo();
            allocationRepo.insert(new ClawbackAllocation(null, masterId, detail.id(),
                    target.order().id(), allocationNo, item.amountCents(), now));
            ClawbackDetail after = clawbackRepo.findById(detail.id()).orElseThrow();
            viewItems.add(new MasterAllocationView.Item(allocationNo, item.correctionNo(),
                    after.clawbackNo(), item.amountCents(), after.collectedCents(),
                    after.approvedCents() - after.collectedCents(),
                    after.status().name(), after.version()));
        }

        MasterAllocationView view = new MasterAllocationView(masterId, batch.batchNo(),
                req.masterSerial(), req.totalCents(), req.receivedAt(), 1, now, viewItems);
        // 冻结首份分配结果快照，供同流水同内容重放。
        masterRepo.updateResponseBody(masterId, fingerprints.toJson(view));
        return view;
    }

    /**
     * 查询汇总分配母凭证（含各分配行与目标单当前追缴余额，供勾稽）。
     */
    @Transactional(readOnly = true)
    public MasterAllocationView getMasterAllocation(String batchNo, String masterSerial) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        ClawbackMasterReceipt master = masterRepo.findBySerial(masterSerial)
                .filter(m -> m.batchId().equals(batch.id()))
                .orElseThrow(() -> new NotFoundException(
                        "汇总母凭证不存在: 批次 " + batchNo + " 流水 " + masterSerial));
        List<MasterAllocationView.Item> items = new ArrayList<>();
        for (ClawbackAllocation a : allocationRepo.findByMasterId(master.id())) {
            ClawbackDetail detail = clawbackRepo.findById(a.detailId()).orElseThrow();
            ChargebackOrder order = orderRepo.findById(a.orderId()).orElseThrow();
            items.add(new MasterAllocationView.Item(a.allocationNo(), order.correctionNo(),
                    detail.clawbackNo(), a.amountCents(), detail.collectedCents(),
                    detail.approvedCents() - detail.collectedCents(),
                    detail.status().name(), detail.version()));
        }
        return new MasterAllocationView(master.id(), batch.batchNo(), master.masterSerial(),
                master.totalCents(), master.receivedAt(), master.version(), master.createdAt(),
                items);
    }

    /** 同流水重放：内容一致返回首份分配快照，异内容（对象、金额或时刻）409。 */
    private Object replayMaster(String masterSerial, String contentFp) {
        ClawbackMasterReceipt master = masterRepo.findBySerial(masterSerial)
                .orElseThrow(() -> new ConflictException(
                        "银行流水 " + masterSerial + " 登记状态异常"));
        if (!master.contentFingerprint().equals(contentFp)) {
            throw new ConflictException(
                    "银行流水 " + masterSerial + " 已用于不同的汇总分配（分配对象、金额或时刻不一致）");
        }
        try {
            return objectMapper.readValue(master.responseBody(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("母凭证结果快照解析失败", e);
        }
    }

    /** 分配内容指纹：覆盖批次、流水、总额、UTC 时刻与分配对象及金额（不含 requestId 与版本号）。 */
    private String masterContentFingerprint(String batchNo, ClawbackAllocationRequest req) {
        List<Map<String, Object>> lines = req.allocations().stream()
                .sorted(Comparator.comparing(ClawbackAllocationRequest.Item::correctionNo))
                .map(i -> {
                    Map<String, Object> line = new LinkedHashMap<String, Object>();
                    line.put("correctionNo", i.correctionNo());
                    line.put("amountCents", i.amountCents());
                    return line;
                })
                .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("batchNo", batchNo);
        content.put("masterSerial", req.masterSerial());
        content.put("totalCents", req.totalCents());
        content.put("receivedAt", req.receivedAt().toString());
        content.put("allocations", lines);
        return fingerprints.fingerprint("CLAWBACK_MASTER_CONTENT", req.masterSerial(), content);
    }
}
