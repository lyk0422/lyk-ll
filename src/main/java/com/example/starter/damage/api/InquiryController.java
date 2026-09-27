package com.example.starter.damage.api;

import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.InquiryViews.BidView;
import com.example.starter.damage.dto.InquiryViews.InquiryView;
import com.example.starter.damage.dto.InquiryViews.OpeningView;
import com.example.starter.damage.dto.InquiryViews.ReviewResultView;
import com.example.starter.damage.service.InquiryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密封询价、报价、开标比价与双重复核 API。
 */
@RestController
public class InquiryController {

    private final InquiryService service;

    public InquiryController(InquiryService service) {
        this.service = service;
    }

    /** 对待批复残损案发布密封询价版本 */
    @PostMapping("/api/cases/{caseId}/inquiries")
    public InquiryView publish(@PathVariable long caseId,
                               @Valid @RequestBody PublishInquiryRequest request) {
        return service.publishInquiry(caseId, request);
    }

    /** 查询询价版本（含固定残损项与每项允许工法） */
    @GetMapping("/api/inquiries/{inquiryId}")
    public InquiryView detail(@PathVariable long inquiryId) {
        return service.getInquiry(inquiryId);
    }

    /** 供应商提交/修订整版报价（截止前，恰在截止时刻拒绝） */
    @PostMapping("/api/inquiries/{inquiryId}/bids/{supplierNo}")
    public BidView submitBid(@PathVariable long inquiryId,
                             @PathVariable String supplierNo,
                             @Valid @RequestBody SubmitBidRequest request) {
        return service.submitBid(inquiryId, supplierNo, request);
    }

    /** 查询某供应商当前报价与修订历史 */
    @GetMapping("/api/inquiries/{inquiryId}/bids/{supplierNo}")
    public BidView getBid(@PathVariable long inquiryId,
                          @PathVariable String supplierNo) {
        return service.getBid(inquiryId, supplierNo);
    }

    /** 截止后开标：冻结全部有效报价并比价，重复开标返回首次清单 */
    @PostMapping("/api/inquiries/{inquiryId}/open")
    public OpeningView open(@PathVariable long inquiryId,
                            @Valid @RequestBody OpenBidsRequest request) {
        return service.openBids(inquiryId, request);
    }

    /** 查询开标冻结清单 */
    @GetMapping("/api/inquiries/{inquiryId}/opening")
    public OpeningView opening(@PathVariable long inquiryId) {
        return service.getOpening(inquiryId);
    }

    /** 双重复核：两名不同复核人齐后将中标报价转为修理批复 */
    @PostMapping("/api/inquiries/{inquiryId}/reviews")
    public ReviewResultView review(@PathVariable long inquiryId,
                                   @Valid @RequestBody ReviewRequest request) {
        return service.review(inquiryId, request);
    }
}
