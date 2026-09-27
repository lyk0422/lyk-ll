package com.example.starter.damage.api;

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
import com.example.starter.damage.service.SealedBidService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 修箱密封报价比价与供应商资格 API。
 * 询价 → 密封投标 → 截止开标 → 双人不同复核 → 转原案修理批复；旧入口保持兼容。
 */
@RestController
@RequestMapping("/api")
public class SealedBidController {

    private final SealedBidService service;

    public SealedBidController(SealedBidService service) {
        this.service = service;
    }

    /** 发布询价版本：固定残损项集合、每项允许工法与 UTC 截止时刻 */
    @PostMapping("/cases/{caseId}/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryView publish(@PathVariable long caseId, @Valid @RequestBody PublishInquiryRequest request) {
        return service.publishInquiry(caseId, request);
    }

    /** 查询询价版本 */
    @GetMapping("/cases/{caseId}/inquiries/{version}")
    public InquiryView inquiry(@PathVariable long caseId, @PathVariable int version) {
        return service.inquiry(caseId, version);
    }

    /** 提交/修订密封报价（now&lt;截止方可投标） */
    @PostMapping("/cases/{caseId}/inquiries/{version}/bids")
    public BidView submitBid(@PathVariable long caseId, @PathVariable int version,
                             @Valid @RequestBody SubmitBidRequest request) {
        return service.submitBid(caseId, version, request);
    }

    /** 截止后开标冻结；重复开标返回首次清单 */
    @PostMapping("/cases/{caseId}/inquiries/{version}/open")
    public OpeningView open(@PathVariable long caseId, @PathVariable int version,
                            @Valid @RequestBody OpenBidsRequest request) {
        return service.openBids(caseId, version, request);
    }

    /** 查询开标清单 */
    @GetMapping("/cases/{caseId}/inquiries/{version}/opening")
    public OpeningView opening(@PathVariable long caseId, @PathVariable int version) {
        return service.opening(caseId, version);
    }

    /** 中标双人不同复核；第二人通过转原案修理批复 */
    @PostMapping("/cases/{caseId}/inquiries/{version}/reviews")
    public OpeningView review(@PathVariable long caseId, @PathVariable int version,
                              @Valid @RequestBody ReviewRequest request) {
        return service.review(caseId, version, request);
    }

    /** 供应商资格续期（版本只追加） */
    @PostMapping("/suppliers/{supplierNo}/qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView renew(@PathVariable String supplierNo,
                                   @Valid @RequestBody QualificationRenewRequest request) {
        return service.renewQualification(supplierNo, request);
    }

    /** 撤销供应商资格；批复后撤销仅形成风险事件 */
    @PostMapping("/suppliers/{supplierNo}/revocation")
    public RevokeResultView revoke(@PathVariable String supplierNo,
                                   @Valid @RequestBody QualificationRevokeRequest request) {
        return service.revokeQualification(supplierNo, request);
    }
}
