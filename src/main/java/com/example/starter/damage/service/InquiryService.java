package com.example.starter.damage.service;

import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QuotationReviseRequest;
import com.example.starter.damage.dto.Requests.QuotationSubmitRequest;
import com.example.starter.damage.dto.Requests.ReviewRequest;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.InquiryDetailView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.QuotationView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 密封询价门面服务：写操作以 requestId 幂等，
 * 指纹覆盖 requestId 与全部业务字段（含 expectedVersion、固定项集合与精确 UTC 截止时刻）。
 */
@Service
public class InquiryService {

    private final InquiryTxService txService;
    private final IdempotencyExecutor idempotency;

    public InquiryService(InquiryTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 发布询价版本（幂等） */
    public InquiryDetailView publish(long caseId, InquiryPublishRequest request) {
        return idempotency.execute("INQUIRY_PUBLISH#" + caseId, request.requestId(), request,
                InquiryDetailView.class, () -> txService.publish(caseId, request));
    }

    /** 投标（幂等；同键异参 409 且不产生报价版本） */
    public QuotationView submit(long inquiryId, QuotationSubmitRequest request) {
        return idempotency.execute("QUOTATION_SUBMIT#" + inquiryId, request.requestId(), request,
                QuotationView.class, () -> txService.submit(inquiryId, request));
    }

    /** 修订报价（幂等） */
    public QuotationView revise(long inquiryId, long supplierId, QuotationReviseRequest request) {
        return idempotency.execute("QUOTATION_REVISE#" + inquiryId + "#" + supplierId, request.requestId(),
                request, QuotationView.class, () -> txService.revise(inquiryId, supplierId, request));
    }

    /** 开标（幂等；重复开标返回首次清单） */
    public BidOpeningView open(long inquiryId, BidOpenRequest request) {
        return idempotency.execute("BID_OPEN#" + inquiryId, request.requestId(), request,
                BidOpeningView.class, () -> txService.open(inquiryId, request));
    }

    /** 定标复核（幂等） */
    public InquiryDetailView review(long inquiryId, ReviewRequest request) {
        return idempotency.execute("AWARD_REVIEW#" + inquiryId, request.requestId(), request,
                InquiryDetailView.class, () -> txService.review(inquiryId, request));
    }

    /** 询价全量明细 */
    public InquiryDetailView detail(long inquiryId) {
        return txService.detail(inquiryId);
    }

    /** 案件全部询价版本 */
    public List<InquiryView> listByCase(long caseId) {
        return txService.listByCase(caseId);
    }
}
