package com.example.starter.damage.api;

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
import com.example.starter.damage.service.SealedInquiryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 修箱批复前密封询价与比价 API。
 * 流转：登记供应商 → 发布询价版本 → 供应商投标/修订 → 截止后开标比价
 * → 双人不同复核 → 中标报价转原案修理批复（沿用既有放行链）。
 */
@RestController
public class SealedInquiryController {

    private final SealedInquiryService service;

    public SealedInquiryController(SealedInquiryService service) {
        this.service = service;
    }

    /** 登记合成供应商（含适用工法与首个资格版本） */
    @PostMapping("/api/suppliers")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public SupplierView createSupplier(@Valid @RequestBody SupplierCreateRequest request) {
        return service.createSupplier(request);
    }

    /** 供应商查询 */
    @GetMapping("/api/suppliers/{supplierCode}")
    public SupplierView supplier(@PathVariable String supplierCode) {
        return service.supplierDetail(supplierCode);
    }

    /** 资格续期：expectedVersion 成功后加 1 */
    @PostMapping("/api/suppliers/{supplierCode}/qualifications/renew")
    public SupplierView renewQualification(@PathVariable String supplierCode,
                                           @Valid @RequestBody QualificationRenewRequest request) {
        return service.renewQualification(supplierCode, request);
    }

    /** 撤销资格：开标竞争口径一致；批复后撤销只形成风险事件 */
    @PostMapping("/api/suppliers/{supplierCode}/qualifications/revoke")
    public SupplierView revokeQualification(@PathVariable String supplierCode,
                                            @Valid @RequestBody QualificationRevokeRequest request) {
        return service.revokeQualification(supplierCode, request);
    }

    /** 待批复残损案发布询价版本（固定残损项集合、每项允许工法与 UTC 截止时刻） */
    @PostMapping("/api/cases/{caseId}/inquiries")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public InquiryView publishInquiry(@PathVariable long caseId,
                                      @Valid @RequestBody InquiryPublishRequest request) {
        return service.publishInquiry(caseId, request);
    }

    /** 案件中标转批复凭证查询 */
    @GetMapping("/api/cases/{caseId}/award")
    public AwardView caseAward(@PathVariable long caseId) {
        return service.awardDetail(caseId);
    }

    /** 询价版本明细（固定项与允许工法） */
    @GetMapping("/api/inquiries/{inquiryId}")
    public InquiryView inquiry(@PathVariable long inquiryId) {
        return service.inquiryDetail(inquiryId);
    }

    /** 供应商提交/修订整版报价 */
    @PostMapping("/api/inquiries/{inquiryId}/bids")
    public BidView submitBid(@PathVariable long inquiryId, @Valid @RequestBody BidSubmitRequest request) {
        return service.submitBid(inquiryId, request);
    }

    /** 某询价版本全部报价（含修订历史与冻结/排除状态） */
    @GetMapping("/api/inquiries/{inquiryId}/bids")
    public List<BidView> listBids(@PathVariable long inquiryId) {
        return service.listBids(inquiryId);
    }

    /** 截止后开标，冻结全部当前报价并比价；重复开标返回首次清单 */
    @PostMapping("/api/inquiries/{inquiryId}/openings")
    public OpeningView openBids(@PathVariable long inquiryId, @Valid @RequestBody BidOpenRequest request) {
        return service.openBids(inquiryId, request);
    }

    /** 开标清单查询 */
    @GetMapping("/api/inquiries/{inquiryId}/openings")
    public OpeningView opening(@PathVariable long inquiryId) {
        return service.openingDetail(inquiryId);
    }

    /** 双人不同复核，中标报价转原案修理批复并冻结询价、报价与资格版本 */
    @PostMapping("/api/inquiries/{inquiryId}/award-confirmations")
    public AwardView confirmAward(@PathVariable long inquiryId, @Valid @RequestBody AwardConfirmRequest request) {
        return service.confirmAward(inquiryId, request);
    }
}
