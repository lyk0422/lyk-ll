package com.example.starter.lc.service;

import com.example.starter.lc.LcBusinessException;
import com.example.starter.lc.LcErrorCode;
import com.example.starter.lc.dto.CreateLcRequest;
import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import com.example.starter.lc.dto.ErrorResponse;
import com.example.starter.lc.dto.LcResponse;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.PayRequest;
import com.example.starter.lc.dto.PresentDocumentsRequest;
import com.example.starter.lc.dto.RefuseRequest;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.dto.WaiveDiscrepanciesRequest;
import com.example.starter.lc.repo.DiscrepancyRow;
import com.example.starter.lc.repo.LcCreditRow;
import com.example.starter.lc.repo.LcRepository;
import com.example.starter.lc.repo.SubmissionRepository;
import com.example.starter.lc.repo.SubmissionRow;
import com.example.starter.lc.support.BankCalendar;
import com.example.starter.lc.support.ServiceClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 信用证单证业务事务边界：每个方法是一个独立数据库事务，行锁与条件更新保证并发唯一终态。
 * 校验失败抛 {@link LcBusinessException} 触发回滚；伴随状态推进的业务失败（迟发拒付）正常返回以提交状态。
 */
@Component
public class LcTxOperations {

    private final LcRepository lcRepository;

    private final SubmissionRepository submissionRepository;

    private final ServiceClock clock;

    public LcTxOperations(LcRepository lcRepository, SubmissionRepository submissionRepository,
                          ServiceClock clock) {
        this.lcRepository = lcRepository;
        this.submissionRepository = submissionRepository;
        this.clock = clock;
    }

    /** 开立信用证。 */
    @Transactional
    public OperationResult.Fresh createLc(CreateLcRequest request) {
        Instant now = clock.now();
        LcCreditRow row = new LcCreditRow(
                request.lcNo(),
                request.applicant(),
                request.beneficiary(),
                request.currency() == null || request.currency().isBlank() ? "USD" : request.currency(),
                request.totalAmount(),
                request.totalAmount(),
                request.expiryDate(),
                "ACTIVE",
                now);
        try {
            lcRepository.insert(row);
        } catch (DuplicateKeyException e) {
            throw new LcBusinessException(LcErrorCode.LC_ALREADY_EXISTS, HttpStatus.CONFLICT,
                    "信用证编号已存在: " + request.lcNo());
        }
        return new OperationResult.Fresh(HttpStatus.CREATED.value(), toLcResponse(row));
    }

    /** 受益人交单；同一信用证同时刻只允许一个审单中批次，逾期未拒付的批次自动转为视为接受。 */
    @Transactional
    public OperationResult.Fresh present(PresentDocumentsRequest request) {
        Instant presentedAt = request.presentedAt() != null ? request.presentedAt() : clock.now();
        LcCreditRow lc = lcRepository.findForUpdate(request.lcNo());
        LocalDate presentedDate = LocalDate.ofInstant(presentedAt, ZoneOffset.UTC);
        if (presentedDate.isAfter(lc.expiryDate())) {
            throw new LcBusinessException(LcErrorCode.PRESENTATION_EXPIRED, HttpStatus.UNPROCESSABLE_ENTITY,
                    "交单日期 " + presentedDate + " 晚于信用证有效期 " + lc.expiryDate());
        }
        SubmissionRow reviewing = submissionRepository.findUnderReviewForUpdate(lc.lcNo());
        if (reviewing != null) {
            if (presentedAt.isBefore(reviewing.deadlineAt())) {
                throw new LcBusinessException(LcErrorCode.ALREADY_UNDER_REVIEW, HttpStatus.CONFLICT,
                        "信用证 " + lc.lcNo() + " 已有审单中的交单批次 " + reviewing.batchNo());
            }
            // 审单期已过仍未拒付：自动视为接受，原批次释放审单占用后受理新交单。
            submissionRepository.markAccepted(
                    reviewing.batchNo(), "DEEMED_ACCEPTED", reviewing.deadlineAt());
        }
        int sequence = submissionRepository.countByLc(lc.lcNo()) + 1;
        String batchNo = lc.lcNo() + "-" + sequence;
        Instant deadline = BankCalendar.refusalDeadline(presentedAt);
        SubmissionRow row = new SubmissionRow(
                null, batchNo, lc.lcNo(), lc.beneficiary(), request.drawAmount(),
                "UNDER_REVIEW", presentedAt, deadline, null, null, null, clock.now());
        try {
            submissionRepository.insertBatch(row, request.documents());
        } catch (DuplicateKeyException e) {
            // 过滤唯一索引兜底：并发下已有审单中批次。
            throw new LcBusinessException(LcErrorCode.ALREADY_UNDER_REVIEW, HttpStatus.CONFLICT,
                    "信用证 " + lc.lcNo() + " 已有审单中的交单批次");
        }
        return new OperationResult.Fresh(HttpStatus.CREATED.value(), toSubmissionResponse(row));
    }

    /** 银行拒付：截止时刻（含）之后发出无效并自动转视为接受；拒付一次性列明全部不符点。 */
    @Transactional
    public OperationResult.Fresh refuse(RefuseRequest request) {
        Instant refusedAt = request.refusedAt() != null ? request.refusedAt() : clock.now();
        SubmissionRow batch = submissionRepository.findForUpdate(request.batchNo());
        if (batch.refusedAt() != null || "REFUSED".equals(batch.status())
                || "ACCEPTED".equals(batch.status()) || "DEEMED_ACCEPTED".equals(batch.status())
                || "PAID".equals(batch.status())) {
            if ("REFUSED".equals(batch.status())) {
                throw new LcBusinessException(LcErrorCode.DISCREPANCIES_LOCKED, HttpStatus.CONFLICT,
                        "批次已拒付，不符点已随通知一次性列明，不得补充或再次拒付");
            }
            throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次当前状态 " + batch.status() + " 不允许拒付");
        }
        if (refusedAt.isBefore(batch.presentedAt())) {
            throw new LcBusinessException(LcErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "拒付通知发出时间不得早于收到单据时间");
        }
        if (!BankCalendar.isRefusalTimely(refusedAt, batch.deadlineAt())) {
            // 恰在第 5 个工作日 17:00 前发出才有效；17:00 及之后无效，自动视为接受。
            submissionRepository.markAccepted(batch.batchNo(), "DEEMED_ACCEPTED", batch.deadlineAt());
            ErrorResponse error = new ErrorResponse(LcErrorCode.REFUSAL_TOO_LATE.name(),
                    "拒付通知晚于截止时刻 " + batch.deadlineAt() + "（UTC），拒付无效，单据视为接受");
            return new OperationResult.Fresh(HttpStatus.UNPROCESSABLE_ENTITY.value(), error);
        }
        submissionRepository.insertDiscrepancies(batch.batchNo(), request.discrepancies(), refusedAt);
        int updated = submissionRepository.markRefused(batch.batchNo(), refusedAt);
        if (updated == 0) {
            throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次状态已变化，拒付未被受理");
        }
        SubmissionRow refused = withRefused(batch, refusedAt);
        return new OperationResult.Fresh(HttpStatus.OK.value(), toSubmissionResponse(refused));
    }

    /** 申请人放弃全部不符点：部分放弃无效，拒付与放弃由行锁串行化为唯一终态。 */
    @Transactional
    public OperationResult.Fresh waive(WaiveDiscrepanciesRequest request) {
        Instant waivedAt = request.waivedAt() != null ? request.waivedAt() : clock.now();
        SubmissionRow batch = submissionRepository.findForUpdate(request.batchNo());
        switch (batch.status()) {
            case "REFUSED" -> {
                if (waivedAt.isBefore(batch.refusedAt())) {
                    throw new LcBusinessException(LcErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                            "放弃声明时间不得早于拒付通知发出时间");
                }
                LcCreditRow lc = lcRepository.findForUpdate(batch.lcNo());
                if (!lc.applicant().equals(request.applicant())) {
                    throw new LcBusinessException(LcErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                            "声明人不是该信用证的开证申请人");
                }
                List<DiscrepancyRow> existing = submissionRepository.findDiscrepancies(batch.batchNo());
                if (!coversAll(existing, request.discrepancies())) {
                    throw new LcBusinessException(LcErrorCode.PARTIAL_WAIVER, HttpStatus.UNPROCESSABLE_ENTITY,
                            "放弃声明必须且仅覆盖拒付通知列明的全部不符点，部分放弃或夹带新不符点均无效");
                }
                int waived = submissionRepository.markAllDiscrepanciesWaived(batch.batchNo());
                if (waived != existing.size()) {
                    throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                            "不符点放弃数量与登记数量不一致");
                }
                int updated = submissionRepository.markAccepted(batch.batchNo(), "ACCEPTED", waivedAt);
                if (updated == 0) {
                    throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                            "批次状态已变化，放弃未被受理");
                }
                SubmissionRow accepted = withAccepted(batch, waivedAt);
                return new OperationResult.Fresh(HttpStatus.OK.value(), toSubmissionResponse(accepted));
            }
            case "PAID" -> throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次已付款，为终态，不能再放弃不符点");
            case "ACCEPTED" -> throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次不符点已全部放弃并已接受，无需重复声明");
            case "DEEMED_ACCEPTED" -> throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE,
                    HttpStatus.CONFLICT, "单据已因逾期而视为接受，不存在可放弃的不符点");
            default -> throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "拒付通知发出前不能放弃不符点，当前状态: " + batch.status());
        }
    }

    /** 银行付款：额度恰足可付、不足整笔拒绝；付款为终态，重复指令不重复扣减额度。 */
    @Transactional
    public OperationResult.Fresh pay(PayRequest request) {
        Instant paidAt = request.paidAt() != null ? request.paidAt() : clock.now();
        SubmissionRow batch = submissionRepository.findForUpdate(request.batchNo());
        if ("PAID".equals(batch.status())) {
            // 重复付款指令（含不同 requestId）：返回首次结果，不重复扣减额度。
            return new OperationResult.Fresh(HttpStatus.OK.value(), toSubmissionResponse(batch));
        }
        if (!"ACCEPTED".equals(batch.status()) && !"DEEMED_ACCEPTED".equals(batch.status())) {
            throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次当前状态 " + batch.status() + " 不允许付款，须先接受单据");
        }
        if (batch.acceptedAt() != null && paidAt.isBefore(batch.acceptedAt())) {
            throw new LcBusinessException(LcErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "付款时间不得早于单据接受时间");
        }
        LcCreditRow lc = lcRepository.findForUpdate(batch.lcNo());
        BigDecimal amount = batch.drawAmount();
        int deducted = lcRepository.deductAvailableAmount(lc.lcNo(), amount);
        if (deducted == 0) {
            throw new LcBusinessException(LcErrorCode.INSUFFICIENT_AVAILABLE_AMOUNT,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "可用额度 " + lc.availableAmount() + " 不足支款金额 " + amount + "，整笔拒绝付款");
        }
        int updated = submissionRepository.markPaid(batch.batchNo(), paidAt);
        if (updated == 0) {
            // 并发下批次被其他流程改动：额度扣减随事务回滚，整笔不留半成品。
            throw new LcBusinessException(LcErrorCode.ILLEGAL_STATE, HttpStatus.CONFLICT,
                    "批次状态已变化，付款未被受理");
        }
        return new OperationResult.Fresh(HttpStatus.OK.value(),
                toSubmissionResponse(withPaid(batch, paidAt)));
    }

    /** 查询并推进状态：审单期已过的批次在此提交“视为接受”。 */
    @Transactional
    public SubmissionRow getSubmissionRow(String batchNo, Instant now) {
        SubmissionRow batch = submissionRepository.findForUpdate(batchNo);
        if ("UNDER_REVIEW".equals(batch.status()) && !now.isBefore(batch.deadlineAt())) {
            submissionRepository.markAccepted(batch.batchNo(), "DEEMED_ACCEPTED", batch.deadlineAt());
            return withAccepted(batch, batch.deadlineAt(), "DEEMED_ACCEPTED");
        }
        return batch;
    }

    private boolean coversAll(List<DiscrepancyRow> existing, List<DiscrepancyItem> declared) {
        Set<String> existingKeys = new HashSet<>();
        for (DiscrepancyRow row : existing) {
            existingKeys.add(row.docType() + "\u0000" + row.description());
        }
        Set<String> declaredKeys = new HashSet<>();
        for (DiscrepancyItem item : declared) {
            declaredKeys.add(item.docType() + "\u0000" + item.description());
        }
        return existingKeys.equals(declaredKeys);
    }

    private SubmissionRow withRefused(SubmissionRow b, Instant refusedAt) {
        return new SubmissionRow(b.id(), b.batchNo(), b.lcNo(), b.beneficiary(), b.drawAmount(),
                "REFUSED", b.presentedAt(), b.deadlineAt(), refusedAt, b.acceptedAt(), b.paidAt(),
                b.createdAt());
    }

    private SubmissionRow withAccepted(SubmissionRow b, Instant acceptedAt) {
        return withAccepted(b, acceptedAt, "ACCEPTED");
    }

    private SubmissionRow withAccepted(SubmissionRow b, Instant acceptedAt, String status) {
        return new SubmissionRow(b.id(), b.batchNo(), b.lcNo(), b.beneficiary(), b.drawAmount(),
                status, b.presentedAt(), b.deadlineAt(), b.refusedAt(), acceptedAt, b.paidAt(),
                b.createdAt());
    }

    private SubmissionRow withPaid(SubmissionRow b, Instant paidAt) {
        return new SubmissionRow(b.id(), b.batchNo(), b.lcNo(), b.beneficiary(), b.drawAmount(),
                "PAID", b.presentedAt(), b.deadlineAt(), b.refusedAt(), b.acceptedAt(), paidAt,
                b.createdAt());
    }

    private LcResponse toLcResponse(LcCreditRow lc) {
        return new LcResponse(lc.lcNo(), lc.applicant(), lc.beneficiary(), lc.currency(),
                lc.totalAmount(), lc.availableAmount(), lc.expiryDate(), lc.status(), lc.createdAt());
    }

    private SubmissionResponse toSubmissionResponse(SubmissionRow batch) {
        List<DocumentItem> documents = submissionRepository.findDocuments(batch.batchNo());
        List<SubmissionResponse.DiscrepancyView> discrepancies = submissionRepository
                .findDiscrepancies(batch.batchNo()).stream()
                .map(d -> new SubmissionResponse.DiscrepancyView(d.docType(), d.description(), d.waived()))
                .toList();
        return new SubmissionResponse(
                batch.batchNo(), batch.lcNo(), batch.beneficiary(), batch.drawAmount(), batch.status(),
                batch.presentedAt(), batch.deadlineAt(), batch.refusedAt(), batch.acceptedAt(),
                batch.paidAt(), documents, discrepancies);
    }
}
