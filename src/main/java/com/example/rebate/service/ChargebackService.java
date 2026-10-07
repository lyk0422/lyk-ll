package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.ChargebackBalance;
import com.example.rebate.domain.ChargebackOrder;
import com.example.rebate.domain.ChargebackStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.ClawbackDetail;
import com.example.rebate.domain.ClawbackReceipt;
import com.example.rebate.domain.ClawbackStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.repo.BankSerialRegistryRepository;
import com.example.rebate.repo.ChargebackBalanceRepository;
import com.example.rebate.repo.ChargebackOrderRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.ClawbackDetailRepository;
import com.example.rebate.repo.ClawbackReceiptRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.BadRequestException;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.ChargebackBalanceView;
import com.example.rebate.web.dto.ChargebackSubmitRequest;
import com.example.rebate.web.dto.ChargebackReviewRequest;
import com.example.rebate.web.dto.ChargebackView;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import com.example.rebate.web.dto.ClawbackView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final BankSerialRegistryRepository registryRepo;
    private final TimeService timeService;

    public ChargebackService(BatchRepository batchRepo,
                             CheckDetailRepository detailRepo,
                             ChargebackBalanceRepository balanceRepo,
                             ChargebackOrderRepository orderRepo,
                             ClawbackDetailRepository clawbackRepo,
                             ClawbackReceiptRepository receiptRepo,
                             BankSerialRegistryRepository registryRepo,
                             TimeService timeService) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.balanceRepo = balanceRepo;
        this.orderRepo = orderRepo;
        this.clawbackRepo = clawbackRepo;
        this.receiptRepo = receiptRepo;
        this.registryRepo = registryRepo;
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

        // 全局互斥：同一真实流水只能用于一种入口，旧单笔入口不得借用已分配母流水。
        registryRepo.findSource(req.receiptSerial()).ifPresent(source -> {
            throw new ConflictException(
                    "银行流水 " + req.receiptSerial() + " 已被汇总分配入口占用，单笔回执不得借用");
        });

        // 先占全局流水登记（统一锁顺序：流水登记 → 追缴明细，与汇总入口一致避免死锁）；
        // 后续任一校验失败整事务回滚，流水登记随之释放（失败不占流水）。
        if (!registryRepo.tryRegister(req.receiptSerial(), "SINGLE", timeService.now())) {
            throw new ConflictException(
                    "银行流水 " + req.receiptSerial() + " 已被其他入口并发占用");
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
}
