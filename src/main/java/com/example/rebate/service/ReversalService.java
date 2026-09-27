package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.ClawbackReceipt;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.ReversalLedger;
import com.example.rebate.domain.ReversalOrder;
import com.example.rebate.domain.ReversalStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.ClawbackDetailRepository;
import com.example.rebate.repo.ClawbackReceiptRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.ReversalLedgerRepository;
import com.example.rebate.repo.ReversalOrderRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import com.example.rebate.web.dto.ReversalBalanceView;
import com.example.rebate.web.dto.ReversalReviewRequest;
import com.example.rebate.web.dto.ReversalSubmitRequest;
import com.example.rebate.web.dto.ReversalView;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已结案批次分批冲回流程：冲回提交 → 他人复核（批准/驳回）→ 追缴多笔到账 → 完成。
 *
 * <p>全部写操作与 requestId 幂等记录在<b>同一事务</b>提交：业务失败整体回滚并释放幂等键；
 * 额度占用与到账均以带边界条件的 UPDATE 落库，审批与冲回并发也不可能超冲、超收。
 * 冲回只引用结案批次内报关单的冻结退税额，不修改原文库版本、核销流水与结案总额。</p>
 */
@Service
public class ReversalService {

    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final ReversalLedgerRepository ledgerRepo;
    private final ReversalOrderRepository orderRepo;
    private final ClawbackDetailRepository detailClawbackRepo;
    private final ClawbackReceiptRepository receiptRepo;
    private final ReversalIdempotencyService idempotency;
    private final TimeService timeService;

    public ReversalService(BatchRepository batchRepo,
                          CheckDetailRepository detailRepo,
                          ReversalLedgerRepository ledgerRepo,
                          ReversalOrderRepository orderRepo,
                          ClawbackDetailRepository detailClawbackRepo,
                          ClawbackReceiptRepository receiptRepo,
                          ReversalIdempotencyService idempotency,
                          TimeService timeService) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.ledgerRepo = ledgerRepo;
        this.orderRepo = orderRepo;
        this.detailClawbackRepo = detailClawbackRepo;
        this.receiptRepo = receiptRepo;
        this.idempotency = idempotency;
        this.timeService = timeService;
    }

    /**
     * 提交冲回单（初版 PENDING，版本 1）。
     */
    @Transactional
    public ReversalView submit(String batchNo, ReversalSubmitRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotency.run("REVERSAL_SUBMIT", req.requestId(), payload, ReversalView.class, () -> {
            DeclarationBatch batch = loadClosedBatch(batchNo);
            if (req.expectedVersion() != 1) {
                throw new UnprocessableException("提交冲回单必须携带初始版本 expectedVersion=1");
            }
            long frozenCents = frozenCents(batch.id(), req.declarationNo());
            ReversalLedger ledger = getOrCreateLedger(batch.id(), req.declarationNo(), frozenCents);
            if (ledger.frozenCents() != frozenCents) {
                throw new UnprocessableException("台账冻结退税额与结案明细不一致，禁止冲回");
            }
            // 预检：待复核与已占用额度合计不得超过冻结额，恰归零合法。批准时仍有条件 UPDATE 兜底并发。
            long pending = orderRepo.sumPendingAmount(batch.id(), req.declarationNo());
            if (pending + ledger.occupiedCents() + req.amountCents() > frozenCents) {
                throw new UnprocessableException(String.format(
                        "累计冲回超出冻结退税额：冻结=%d 分，已占用=%d 分，待复核=%d 分，本次申请=%d 分",
                        frozenCents, ledger.occupiedCents(), pending, req.amountCents()));
            }
            long id;
            try {
                id = orderRepo.insert(batch.id(), req.declarationNo(), req.correctionNo(),
                        req.reason(), req.amountCents(), req.submitter(), timeService.now());
            } catch (DuplicateKeyException e) {
                throw new UnprocessableException(
                        "同批次更正编号已存在: " + req.correctionNo());
            }
            return buildView(batch, orderRepo.findById(id).orElseThrow());
        });
    }

    /**
     * 复核冲回单：批准在同一事务占用剩余可冲额并产生独立追缴明细；驳回不占额。
     */
    @Transactional
    public ReversalView review(String batchNo, long reversalId, ReversalReviewRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "reversalId", reversalId);
        return idempotency.run("REVERSAL_REVIEW", req.requestId(), payload, ReversalView.class, () -> {
            DeclarationBatch batch = loadClosedBatch(batchNo);
            ReversalOrder order = loadOrderOfBatch(batch, reversalId);
            if (order.status() != ReversalStatus.PENDING) {
                throw new UnprocessableException(
                        "冲回单当前状态 " + order.status() + " 不允许复核");
            }
            if (order.versionNo() != req.expectedVersion()) {
                throw new ConflictException(
                        "版本冲突：expectedVersion=" + req.expectedVersion()
                                + "，当前版本=" + order.versionNo());
            }
            if (req.reviewer().equals(order.submitter())) {
                throw new UnprocessableException("复核人不得与提交人为同一人: " + req.reviewer());
            }

            int rows;
            if ("APPROVE".equals(req.action())) {
                ReversalLedger ledger =
                        ledgerRepo.find(batch.id(), order.declarationNo()).orElseThrow();
                // 硬边界：数据库条件更新保证并发审批累计占用绝不超过冻结额。
                rows = ledgerRepo.occupy(ledger.id(), order.amountCents(), timeService.now());
                if (rows == 0) {
                    throw new UnprocessableException("剩余可冲额不足，批准失败");
                }
                rows = orderRepo.markApproved(order.id(), req.reviewer(),
                        req.expectedVersion(), timeService.now());
                if (rows == 0) {
                    // 版本/状态已被并发改写：抛错回滚，刚占用的额度一并释放。
                    throw new ConflictException("冲回单状态或版本已变化，复核失败");
                }
                detailClawbackRepo.insert(order.id(), clawbackSerial(order.id()),
                        order.amountCents(), timeService.now());
            } else {
                rows = orderRepo.markRejected(order.id(), req.reviewer(),
                        req.expectedVersion(), timeService.now());
                if (rows == 0) {
                    throw new ConflictException("冲回单状态或版本已变化，复核失败");
                }
            }
            return buildView(batch, orderRepo.findById(order.id()).orElseThrow());
        });
    }

    /**
     * 登记追缴到账回执：允许多笔部分到账，累计不超批准金额，恰收齐转 COMPLETED。
     * 同流水异金额拒绝、同流水同金额去重；未批准单不能登记，完成后只读。
     */
    @Transactional
    public ReversalView registerReceipt(String batchNo, long reversalId, ClawbackReceiptRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "reversalId", reversalId);
        return idempotency.run("CLAWBACK_RECEIPT", req.requestId(), payload, ReversalView.class, () -> {
            DeclarationBatch batch = loadClosedBatch(batchNo);
            ReversalOrder order = orderRepo.lockById(reversalId)
                    .orElseThrow(() -> new NotFoundException("冲回单不存在: " + reversalId));
            if (order.batchId() != batch.id()) {
                throw new NotFoundException("冲回单不属于该批次: " + reversalId);
            }
            if (order.status() == ReversalStatus.PENDING
                    || order.status() == ReversalStatus.REJECTED) {
                throw new UnprocessableException("冲回单尚未批准，不能登记追缴回执");
            }
            if (order.status() == ReversalStatus.COMPLETED) {
                throw new UnprocessableException("冲回单追缴已完成，回执只读");
            }
            if (order.versionNo() != req.expectedVersion()) {
                throw new ConflictException(
                        "版本冲突：expectedVersion=" + req.expectedVersion()
                                + "，当前版本=" + order.versionNo());
            }

            // 流水号全局唯一：同流水异金额拒绝，同流水同金额去重（乱序/重复投递不改变总额）。
            var existingSerial = receiptRepo.findBySerial(req.receiptSerial());
            if (existingSerial.isPresent()) {
                ClawbackReceipt prior = existingSerial.get();
                if (prior.amountCents() != req.amountCents() || prior.reversalId() != reversalId) {
                    throw new UnprocessableException(
                            "追缴流水号 " + req.receiptSerial() + " 已登记且金额/归属不一致");
                }
                return buildView(batch, orderRepo.findById(reversalId).orElseThrow());
            }

            ReversalStatus nextStatus =
                    order.receivedCents() + req.amountCents() == order.amountCents()
                            ? ReversalStatus.COMPLETED : ReversalStatus.APPROVED;
            int orderRows = orderRepo.addReceived(reversalId, req.amountCents(), nextStatus,
                    req.expectedVersion(), timeService.now());
            if (orderRows == 0) {
                throw new UnprocessableException(String.format(
                        "到账累计超过批准金额：批准=%d 分，已到账=%d 分，本笔=%d 分",
                        order.amountCents(), order.receivedCents(), req.amountCents()));
            }
            ReversalLedger ledger =
                    ledgerRepo.find(batch.id(), order.declarationNo()).orElseThrow();
            int ledgerRows = ledgerRepo.addReceived(ledger.id(), req.amountCents(), timeService.now());
            if (ledgerRows == 0) {
                throw new UnprocessableException("台账到账累计超过已占用额，登记失败");
            }
            try {
                receiptRepo.insert(reversalId, req.receiptSerial(),
                        req.amountCents(), timeService.now());
            } catch (DuplicateKeyException e) {
                // 并发下同流水号撞唯一约束：重读按同流水规则判定，当前事务整体回滚。
                ClawbackReceipt prior = receiptRepo.findBySerial(req.receiptSerial()).orElseThrow();
                if (prior.amountCents() != req.amountCents() || prior.reversalId() != reversalId) {
                    throw new UnprocessableException(
                            "追缴流水号 " + req.receiptSerial() + " 已登记且金额/归属不一致");
                }
                throw new ConflictException("追缴流水号并发登记冲突，请重试: " + req.receiptSerial());
            }
            return buildView(batch, orderRepo.findById(reversalId).orElseThrow());
        });
    }

    /**
     * 查询冲回单（含追缴流水与逐笔回执），并快照当前逐单余额。
     */
    @Transactional(readOnly = true)
    public ReversalView getOrder(String batchNo, long reversalId) {
        DeclarationBatch batch = loadBatch(batchNo);
        return buildView(batch, loadOrderOfBatch(batch, reversalId));
    }

    /**
     * 查询冲回单全部到账回执。
     */
    @Transactional(readOnly = true)
    public List<ReversalView.ReceiptView> listReceipts(String batchNo, long reversalId) {
        DeclarationBatch batch = loadBatch(batchNo);
        ReversalOrder order = loadOrderOfBatch(batch, reversalId);
        return receiptRepo.findByReversalId(order.id()).stream()
                .map(r -> new ReversalView.ReceiptView(
                        r.id(), r.receiptSerial(), r.amountCents(), r.receivedAt()))
                .toList();
    }

    /**
     * 逐单可退余额查询；尚未发生冲回时返回冻结额全量可用、版本 1 的虚拟台账。
     */
    @Transactional(readOnly = true)
    public ReversalBalanceView getBalance(String batchNo, String declarationNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        long frozenCents = frozenCents(batch.id(), declarationNo);
        ReversalLedger ledger = ledgerRepo.find(batch.id(), declarationNo).orElse(null);
        if (ledger == null) {
            return new ReversalBalanceView(batch.batchNo(), declarationNo,
                    frozenCents, 0L, frozenCents, 0L, 1);
        }
        return new ReversalBalanceView(batch.batchNo(), declarationNo,
                ledger.frozenCents(), ledger.occupiedCents(),
                ledger.frozenCents() - ledger.occupiedCents(),
                ledger.receivedCents(), ledger.versionNo());
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private DeclarationBatch loadBatch(String batchNo) {
        return batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
    }

    private DeclarationBatch loadClosedBatch(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException(
                    "仅结案(CLOSED)批次允许冲回，当前状态 " + batch.status());
        }
        return batch;
    }

    private ReversalOrder loadOrderOfBatch(DeclarationBatch batch, long reversalId) {
        ReversalOrder order = orderRepo.findById(reversalId)
                .orElseThrow(() -> new NotFoundException("冲回单不存在: " + reversalId));
        if (order.batchId() != batch.id()) {
            throw new NotFoundException("冲回单不属于该批次: " + reversalId);
        }
        return order;
    }

    /**
     * 取报关单在结案明细中的冻结退税额并严格换算为整数分；存在分以下尾数时拒绝冲回。
     */
    private long frozenCents(long batchId, String declarationNo) {
        CheckDetail detail = detailRepo.findByBatchAndDeclaration(batchId, declarationNo)
                .orElseThrow(() -> new NotFoundException(
                        "批次内报关单不存在: " + declarationNo));
        BigDecimal taxBasis = detail.taxBasis();
        if (taxBasis == null || taxBasis.signum() <= 0) {
            throw new UnprocessableException("该报关单无有效冻结退税额，不能冲回");
        }
        BigDecimal cents = taxBasis.multiply(BigDecimal.valueOf(100));
        if (cents.stripTrailingZeros().scale() > 0) {
            throw new UnprocessableException(
                    "冻结退税额 " + taxBasis + " 元存在分以下尾数，不能按整数分冲回");
        }
        return cents.longValueExact();
    }

    /**
     * 取台账行，不存在则懒创建（版本从 1 开始）。并发首单时唯一键 INSERT 可能撞正在提交的
     * 另一事务：捕获冲突后有界等待其提交并重读，避免误报或残留失败事务。
     */
    private ReversalLedger getOrCreateLedger(long batchId, String declarationNo, long frozenCents) {
        var existing = ledgerRepo.find(batchId, declarationNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        ledgerRepo.insertIfAbsent(batchId, declarationNo, frozenCents, timeService.now());
        long deadline = System.currentTimeMillis() + 10_000L;
        while (true) {
            var again = ledgerRepo.find(batchId, declarationNo);
            if (again.isPresent()) {
                return again.get();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ConflictException("台账行创建结果读取超时: " + declarationNo);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("台账行创建等待被中断");
            }
        }
    }

    /**
     * 追缴明细独立流水号：CL + 16 位定长冲回单主键。
     */
    private String clawbackSerial(long reversalId) {
        return "CL" + String.format("%016d", reversalId);
    }

    private ReversalView buildView(DeclarationBatch batch, ReversalOrder order) {
        String clawbackSerial = detailClawbackRepo.findByReversalId(order.id())
                .map(com.example.rebate.domain.ClawbackDetail::clawbackSerial)
                .orElse(null);
        List<ReversalView.ReceiptView> receipts = receiptRepo.findByReversalId(order.id()).stream()
                .map(r -> new ReversalView.ReceiptView(
                        r.id(), r.receiptSerial(), r.amountCents(), r.receivedAt()))
                .toList();
        long remainingReceivable = Math.max(0L, order.amountCents() - order.receivedCents());
        ReversalLedger ledger = ledgerRepo.find(batch.id(), order.declarationNo()).orElse(null);
        Long remainingAvailable =
                ledger == null ? null : ledger.frozenCents() - ledger.occupiedCents();
        Integer ledgerVersion = ledger == null ? null : ledger.versionNo();
        return new ReversalView(
                order.id(),
                batch.batchNo(),
                order.declarationNo(),
                order.correctionNo(),
                order.reason(),
                order.amountCents(),
                order.status().name(),
                order.submitter(),
                order.reviewer(),
                order.receivedCents(),
                remainingReceivable,
                order.versionNo(),
                clawbackSerial,
                remainingAvailable,
                ledgerVersion,
                order.submittedAt(),
                order.reviewedAt(),
                receipts);
    }

    private Map<String, Object> payload(Object body, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("body", body);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
