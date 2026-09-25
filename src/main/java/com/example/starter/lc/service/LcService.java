package com.example.starter.lc.service;

import com.example.starter.lc.LcBusinessException;
import com.example.starter.lc.LcErrorCode;
import com.example.starter.lc.OperationType;
import com.example.starter.lc.dto.CreateLcRequest;
import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
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
import com.example.starter.lc.support.Fingerprint;
import com.example.starter.lc.support.IdempotentTemplate;
import com.example.starter.lc.support.ServiceClock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 信用证审单不符点与拒付决策服务：开立、交单、拒付、放弃不符点、付款，全部 requestId 幂等。
 * 幂等记录落库在业务事务之外，业务动作运行于独立事务（见 {@link LcTxOperations}）。
 */
@Service
public class LcService {

    private final LcRepository lcRepository;

    private final SubmissionRepository submissionRepository;

    private final LcTxOperations txOperations;

    private final IdempotentTemplate idempotentTemplate;

    private final ServiceClock clock;

    public LcService(LcRepository lcRepository, SubmissionRepository submissionRepository,
                     LcTxOperations txOperations, IdempotentTemplate idempotentTemplate,
                     ServiceClock clock) {
        this.lcRepository = lcRepository;
        this.submissionRepository = submissionRepository;
        this.txOperations = txOperations;
        this.idempotentTemplate = idempotentTemplate;
        this.clock = clock;
    }

    /** 开立信用证。 */
    public OperationResult createLc(CreateLcRequest request) {
        String currency = request.currency() == null || request.currency().isBlank()
                ? "USD" : request.currency();
        String fingerprint = Fingerprint.forCreateLc(request.lcNo(), request.applicant(),
                request.beneficiary(), currency, request.totalAmount(), request.expiryDate());
        return idempotentTemplate.execute(request.requestId(), fingerprint,
                OperationType.CREATE_LC, () -> txOperations.createLc(request));
    }

    /** 查询信用证。 */
    public LcResponse getLc(String lcNo) {
        LcCreditRow lc = lcRepository.find(lcNo);
        if (lc == null) {
            throw new LcBusinessException(LcErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                    "信用证不存在: " + lcNo);
        }
        return new LcResponse(lc.lcNo(), lc.applicant(), lc.beneficiary(), lc.currency(),
                lc.totalAmount(), lc.availableAmount(), lc.expiryDate(), lc.status(), lc.createdAt());
    }

    /** 受益人交单（拒付后改单重交视为新批次，重新起算审单期）。 */
    public OperationResult present(PresentDocumentsRequest request) {
        String fingerprint = Fingerprint.forPresent(request.lcNo(), request.drawAmount(),
                request.presentedAt(), normalizeDocuments(request.documents()));
        return idempotentTemplate.execute(request.requestId(), fingerprint,
                OperationType.PRESENT, () -> txOperations.present(request));
    }

    /** 查询交单批次；超过截止时刻仍处审单中的批次会先自动转为“视为接受”。 */
    public SubmissionResponse getSubmission(String batchNo) {
        SubmissionRow batch = txOperations.getSubmissionRow(batchNo, clock.now());
        return toSubmissionResponse(batch);
    }

    /** 银行发出拒付通知（须在第 5 个工作日 17:00 前，一次性列明全部不符点）。 */
    public OperationResult refuse(RefuseRequest request) {
        String fingerprint = Fingerprint.forRefuse(request.batchNo(), request.refusedAt(),
                normalizeDiscrepancies(request.discrepancies()));
        return idempotentTemplate.execute(request.requestId(), fingerprint,
                OperationType.REFUSE, () -> txOperations.refuse(request));
    }

    /** 开证申请人放弃全部不符点（部分放弃无效）。 */
    public OperationResult waive(WaiveDiscrepanciesRequest request) {
        String fingerprint = Fingerprint.forWaive(request.batchNo(), request.applicant(),
                request.waivedAt(), normalizeDiscrepancies(request.discrepancies()));
        return idempotentTemplate.execute(request.requestId(), fingerprint,
                OperationType.WAIVE, () -> txOperations.waive(request));
    }

    /** 银行付款（额度不足整笔拒绝，付款为终态且重复指令返回首次结果）。 */
    public OperationResult pay(PayRequest request) {
        String fingerprint = Fingerprint.forPay(request.batchNo(), request.paidAt());
        return idempotentTemplate.execute(request.requestId(), fingerprint,
                OperationType.PAY, () -> txOperations.pay(request));
    }

    private List<DocumentItem> normalizeDocuments(List<DocumentItem> documents) {
        return documents.stream()
                .sorted((a, b) -> {
                    int cmp = a.docType().compareTo(b.docType());
                    return cmp != 0 ? cmp : a.docRef().compareTo(b.docRef());
                })
                .toList();
    }

    private List<DiscrepancyItem> normalizeDiscrepancies(List<DiscrepancyItem> discrepancies) {
        return discrepancies.stream()
                .sorted((a, b) -> {
                    int cmp = a.docType().compareTo(b.docType());
                    return cmp != 0 ? cmp : a.description().compareTo(b.description());
                })
                .toList();
    }

    private SubmissionResponse toSubmissionResponse(SubmissionRow batch) {
        List<DocumentItem> documents = submissionRepository.findDocuments(batch.batchNo());
        List<SubmissionResponse.DiscrepancyView> discrepancies = submissionRepository
                .findDiscrepancies(batch.batchNo()).stream()
                .map((DiscrepancyRow d) ->
                        new SubmissionResponse.DiscrepancyView(d.docType(), d.description(), d.waived()))
                .toList();
        return new SubmissionResponse(
                batch.batchNo(), batch.lcNo(), batch.beneficiary(), batch.drawAmount(), batch.status(),
                batch.presentedAt(), batch.deadlineAt(), batch.refusedAt(), batch.acceptedAt(),
                batch.paidAt(), documents, discrepancies);
    }
}
