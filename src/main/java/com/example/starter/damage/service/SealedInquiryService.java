package com.example.starter.damage.service;

import com.example.starter.damage.dto.Requests.AwardConfirmRequest;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.BidSubmitRequest;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRenewRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.AwardView;
import com.example.starter.damage.dto.Views.BidView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.OpeningView;
import com.example.starter.damage.dto.Views.SupplierView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 密封询价门面服务：所有写操作以 requestId 幂等，
 * 指纹覆盖 requestId、expectedVersion、残损项集合与 UTC 精确时刻等全部业务字段；
 * 同键同参重放首次冻结结果，同键异参 409，业务失败不占用 requestId。
 */
@Service
public class SealedInquiryService {

    private final SealedInquiryTxService txService;
    private final IdempotencyExecutor idempotency;

    public SealedInquiryService(SealedInquiryTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 登记合成供应商（幂等） */
    public SupplierView createSupplier(SupplierCreateRequest request) {
        return idempotency.execute("SUPPLIER_CREATE", request.requestId(), request, SupplierView.class,
                () -> txService.createSupplier(request));
    }

    /** 供应商资格续期（幂等） */
    public SupplierView renewQualification(String supplierCode, QualificationRenewRequest request) {
        return idempotency.execute("QUAL_RENEW#" + supplierCode, request.requestId(), request, SupplierView.class,
                () -> txService.renewQualification(supplierCode, request));
    }

    /** 撤销供应商资格（幂等；批复后撤销只形成风险事件） */
    public SupplierView revokeQualification(String supplierCode, QualificationRevokeRequest request) {
        return idempotency.execute("QUAL_REVOKE#" + supplierCode, request.requestId(), request, SupplierView.class,
                () -> txService.revokeQualification(supplierCode, request));
    }

    /** 发布询价版本（幂等） */
    public InquiryView publishInquiry(long caseId, InquiryPublishRequest request) {
        return idempotency.execute("INQUIRY_PUBLISH#" + caseId, request.requestId(), request, InquiryView.class,
                () -> txService.publishInquiry(caseId, request));
    }

    /** 提交或修订报价（幂等） */
    public BidView submitBid(long inquiryId, BidSubmitRequest request) {
        return idempotency.execute("BID_SUBMIT#" + inquiryId, request.requestId(), request, BidView.class,
                () -> txService.submitBid(inquiryId, request));
    }

    /** 开标（幂等；重复开标返回首次冻结清单） */
    public OpeningView openBids(long inquiryId, BidOpenRequest request) {
        return idempotency.execute("BID_OPEN#" + inquiryId, request.requestId(), request, OpeningView.class,
                () -> txService.openBids(inquiryId, request));
    }

    /** 双人不同复核转原案修理批复（幂等） */
    public AwardView confirmAward(long inquiryId, AwardConfirmRequest request) {
        return idempotency.execute("AWARD_CONFIRM#" + inquiryId, request.requestId(), request, AwardView.class,
                () -> txService.confirmAward(inquiryId, request));
    }

    /** 供应商查询 */
    public SupplierView supplierDetail(String supplierCode) {
        return txService.supplierDetail(supplierCode);
    }

    /** 询价版本查询 */
    public InquiryView inquiryDetail(long inquiryId) {
        return txService.inquiryDetail(inquiryId);
    }

    /** 报价（含历史修订）查询 */
    public List<BidView> listBids(long inquiryId) {
        return txService.listBids(inquiryId);
    }

    /** 开标清单查询 */
    public OpeningView openingDetail(long inquiryId) {
        return txService.openingDetail(inquiryId);
    }

    /** 中标转批复凭证查询 */
    public AwardView awardDetail(long caseId) {
        return txService.awardDetail(caseId);
    }
}
