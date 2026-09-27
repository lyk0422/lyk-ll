package com.example.starter.damage.service;

import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.InquiryViews.BidView;
import com.example.starter.damage.dto.InquiryViews.InquiryView;
import com.example.starter.damage.dto.InquiryViews.OpeningView;
import com.example.starter.damage.dto.InquiryViews.ReviewResultView;
import com.example.starter.damage.dto.InquiryViews.SupplierView;
import org.springframework.stereotype.Service;

/**
 * 密封询价幂等门面：写操作均以 requestId 幂等，
 * 指纹结构化覆盖入口、版本、集合与精确时刻；同键同参重放首次冻结结果，异参 409、失败不占键。
 */
@Service
public class InquiryService {

    private final InquiryTxService txService;
    private final IdempotencyExecutor idempotency;

    public InquiryService(InquiryTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public SupplierView createSupplier(CreateSupplierRequest request) {
        return idempotency.execute("CREATE_SUPPLIER", request.requestId(), request, SupplierView.class,
                () -> txService.createSupplier(request));
    }

    public SupplierView grantQualification(String supplierNo, QualificationGrantRequest request) {
        return idempotency.execute("GRANT_QUAL#" + supplierNo, request.requestId(), request, SupplierView.class,
                () -> txService.grantQualification(supplierNo, request));
    }

    public SupplierView revokeQualification(String supplierNo, QualificationRevokeRequest request) {
        return idempotency.execute("REVOKE_QUAL#" + supplierNo, request.requestId(), request, SupplierView.class,
                () -> txService.revokeQualification(supplierNo, request));
    }

    public InquiryView publishInquiry(long caseId, PublishInquiryRequest request) {
        return idempotency.execute("PUBLISH_INQUIRY#" + caseId, request.requestId(), request, InquiryView.class,
                () -> txService.publishInquiry(caseId, request));
    }

    public BidView submitBid(long inquiryId, String supplierNo, SubmitBidRequest request) {
        return idempotency.execute("SUBMIT_BID#" + inquiryId + "#" + supplierNo, request.requestId(), request,
                BidView.class,
                () -> txService.submitBid(inquiryId, supplierNo, request));
    }

    public OpeningView openBids(long inquiryId, OpenBidsRequest request) {
        return idempotency.execute("OPEN_BIDS#" + inquiryId, request.requestId(), request, OpeningView.class,
                () -> txService.openBids(inquiryId, request));
    }

    public ReviewResultView review(long inquiryId, ReviewRequest request) {
        return idempotency.execute("REVIEW#" + inquiryId, request.requestId(), request, ReviewResultView.class,
                () -> txService.review(inquiryId, request));
    }

    public SupplierView getSupplier(String supplierNo) {
        return txService.getSupplier(supplierNo);
    }

    public InquiryView getInquiry(long inquiryId) {
        return txService.getInquiry(inquiryId);
    }

    public BidView getBid(long inquiryId, String supplierNo) {
        return txService.getBid(inquiryId, supplierNo);
    }

    public OpeningView getOpening(long inquiryId) {
        return txService.getOpening(inquiryId);
    }
}
