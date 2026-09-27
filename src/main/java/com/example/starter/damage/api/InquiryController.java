package com.example.starter.damage.api;

import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QuotationReviseRequest;
import com.example.starter.damage.dto.Requests.QuotationSubmitRequest;
import com.example.starter.damage.dto.Requests.ReviewRequest;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.InquiryDetailView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.QuotationView;
import com.example.starter.damage.service.InquiryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 修箱密封询价比价 API。
 * 流转：发布询价 → 投标/修订 → 截止后开标 → 双人复核定标转修理批复。
 */
@RestController
@RequestMapping("/api")
public class InquiryController {

    private final InquiryService service;

    public InquiryController(InquiryService service) {
        this.service = service;
    }

    /** 发布询价版本：固定残损项集合、每项允许工法与 UTC 报价截止时刻 */
    @PostMapping("/cases/{caseId}/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryDetailView publish(@PathVariable long caseId,
                                     @Valid @RequestBody InquiryPublishRequest request) {
        return service.publish(caseId, request);
    }

    /** 案件全部询价版本 */
    @GetMapping("/cases/{caseId}/inquiries")
    public List<InquiryView> listByCase(@PathVariable long caseId) {
        return service.listByCase(caseId);
    }

    /** 询价全量明细：固定项、报价、开标结果与复核记录 */
    @GetMapping("/inquiries/{inquiryId}")
    public InquiryDetailView detail(@PathVariable long inquiryId) {
        return service.detail(inquiryId);
    }

    /** 投标：now<截止方可投标，恰在截止拒绝 */
    @PostMapping("/inquiries/{inquiryId}/quotations")
    @ResponseStatus(HttpStatus.CREATED)
    public QuotationView submit(@PathVariable long inquiryId,
                                @Valid @RequestBody QuotationSubmitRequest request) {
        return service.submit(inquiryId, request);
    }

    /** 修订报价：保留历史版本 */
    @PutMapping("/inquiries/{inquiryId}/quotations/{supplierId}")
    public QuotationView revise(@PathVariable long inquiryId, @PathVariable long supplierId,
                                @Valid @RequestBody QuotationReviseRequest request) {
        return service.revise(inquiryId, supplierId, request);
    }

    /** 开标：截止后冻结全部有效报价并定标；重复开标返回首次清单 */
    @PostMapping("/inquiries/{inquiryId}/open")
    public BidOpeningView open(@PathVariable long inquiryId,
                               @Valid @RequestBody BidOpenRequest request) {
        return service.open(inquiryId, request);
    }

    /** 定标复核：双人不同复核后转原案修理批复 */
    @PostMapping("/inquiries/{inquiryId}/reviews")
    public InquiryDetailView review(@PathVariable long inquiryId,
                                    @Valid @RequestBody ReviewRequest request) {
        return service.review(inquiryId, request);
    }
}
