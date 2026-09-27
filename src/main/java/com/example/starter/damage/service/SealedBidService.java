package com.example.starter.damage.service;

import com.example.starter.damage.dto.SealedRequests.OpenBidsRequest;
import com.example.starter.damage.dto.SealedRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRenewRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.SealedRequests.ReviewRequest;
import com.example.starter.damage.dto.SealedRequests.SubmitBidRequest;
import com.example.starter.damage.dto.SealedViews.BidView;
import com.example.starter.damage.dto.SealedViews.InquiryView;
import com.example.starter.damage.dto.SealedViews.OpeningView;
import com.example.starter.damage.dto.SealedViews.QualificationView;
import com.example.starter.damage.dto.SealedViews.RevokeResultView;
import org.springframework.stereotype.Service;

/**
 * 密封报价门面服务：新增写操作均以 requestId 幂等，
 * 指纹为结构化 JSON（requestId、expectedVersion、集合与精确时刻均参与），
 * 同键同参重放首次冻结结果，同键异参 409，失败不占键。
 */
@Service
public class SealedBidService {

    private final SealedBidTxService txService;
    private final IdempotencyExecutor idempotency;

    public SealedBidService(SealedBidTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 发布询价版本（幂等） */
    public InquiryView publishInquiry(long caseId, PublishInquiryRequest request) {
        return idempotency.execute("PUBLISH_INQUIRY#" + caseId, request.requestId(), request, InquiryView.class,
                () -> txService.publishInquiry(caseId, request));
    }

    /** 提交/修订报价（幂等） */
    public BidView submitBid(long caseId, int inquiryVersion, SubmitBidRequest request) {
        return idempotency.execute(
                "SUBMIT_BID#" + caseId + "#" + inquiryVersion, request.requestId(), request, BidView.class,
                () -> txService.submitBid(caseId, inquiryVersion, request));
    }

    /** 开标（幂等；重复开标返回首次冻结清单） */
    public OpeningView openBids(long caseId, int inquiryVersion, OpenBidsRequest request) {
        return idempotency.execute(
                "OPEN_BIDS#" + caseId + "#" + inquiryVersion, request.requestId(), request, OpeningView.class,
                () -> txService.openBids(caseId, inquiryVersion, request));
    }

    /** 双人不同复核（幂等） */
    public OpeningView review(long caseId, int inquiryVersion, ReviewRequest request) {
        return idempotency.execute(
                "REVIEW#" + caseId + "#" + inquiryVersion, request.requestId(), request, OpeningView.class,
                () -> txService.review(caseId, inquiryVersion, request));
    }

    /** 资格续期（幂等） */
    public QualificationView renewQualification(String supplierNo, QualificationRenewRequest request) {
        return idempotency.execute(
                "QUAL_RENEW#" + supplierNo, request.requestId(), request, QualificationView.class,
                () -> txService.renewQualification(supplierNo, request));
    }

    /** 撤销资格（幂等） */
    public RevokeResultView revokeQualification(String supplierNo, QualificationRevokeRequest request) {
        return idempotency.execute(
                "QUAL_REVOKE#" + supplierNo, request.requestId(), request, RevokeResultView.class,
                () -> txService.revokeQualification(supplierNo, request));
    }

    /** 查询询价版本 */
    public InquiryView inquiry(long caseId, int inquiryVersion) {
        return txService.inquiry(caseId, inquiryVersion);
    }

    /** 查询开标清单 */
    public OpeningView opening(long caseId, int inquiryVersion) {
        return txService.opening(caseId, inquiryVersion);
    }
}
