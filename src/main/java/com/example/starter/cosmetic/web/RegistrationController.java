package com.example.starter.cosmetic.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.example.starter.cosmetic.api.CreateProductRequest;
import com.example.starter.cosmetic.api.ObjectionRequest;
import com.example.starter.cosmetic.api.ObjectionResponse;
import com.example.starter.cosmetic.api.ProductResponse;
import com.example.starter.cosmetic.api.RegistrationDetailResponse;
import com.example.starter.cosmetic.api.RegistrationResponse;
import com.example.starter.cosmetic.api.RegistrationSummaryResponse;
import com.example.starter.cosmetic.api.ResolveObjectionRequest;
import com.example.starter.cosmetic.api.ReviewRecordDto;
import com.example.starter.cosmetic.api.SubmitChangeRequest;
import com.example.starter.cosmetic.api.SubmitRegistrationRequest;
import com.example.starter.cosmetic.service.RegistrationService;
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
 * 化妆品备案与功效宣称依据审查 API。
 */
@RestController
@RequestMapping("/api")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final Clock clock;

    public RegistrationController(RegistrationService registrationService, Clock clock) {
        this.registrationService = registrationService;
        this.clock = clock;
    }

    /**
     * 新建产品（合成数据）。
     */
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody CreateProductRequest request) {
        return registrationService.createProduct(request.name(), request.applicant(), Instant.now(clock));
    }

    /**
     * 首次备案提交（requestId 幂等）。
     */
    @PostMapping("/registrations")
    public RegistrationResponse submit(@Valid @RequestBody SubmitRegistrationRequest request) {
        return registrationService.submitInitial(request, Instant.now(clock));
    }

    /**
     * 备案变更（SUBSTANTIAL 实质变更 / SIMPLE 简易变更，requestId 幂等）。
     */
    @PostMapping("/registrations/{id}/changes")
    public RegistrationResponse change(@PathVariable long id, @Valid @RequestBody SubmitChangeRequest request) {
        return registrationService.submitChange(id, request, Instant.now(clock));
    }

    /**
     * 公示期内提出异议。
     */
    @PostMapping("/registrations/{id}/objections")
    public ObjectionResponse object(@PathVariable long id, @Valid @RequestBody ObjectionRequest request) {
        return registrationService.submitObjection(id, request.content(), Instant.now(clock));
    }

    /**
     * 异议审查结论（DISMISSED/UPHELD）。
     */
    @PostMapping("/registrations/{id}/objection-resolutions")
    public RegistrationResponse resolveObjection(@PathVariable long id,
                                                 @Valid @RequestBody ResolveObjectionRequest request) {
        return registrationService.resolveObjection(id, request.outcome(), Instant.now(clock));
    }

    /**
     * 注销生效备案（终态，重复注销幂等成功）。
     */
    @PostMapping("/registrations/{id}/cancellation")
    public RegistrationResponse cancel(@PathVariable long id) {
        return registrationService.cancel(id, Instant.now(clock));
    }

    /**
     * 触发全部到期公示备案生效（与定时任务同入口，便于本地手动触发）。
     */
    @PostMapping("/registrations/finalize-due")
    public FinalizeResponse finalizeDue() {
        int count = registrationService.finalizeDue(Instant.now(clock));
        return new FinalizeResponse(count);
    }

    /**
     * 备案详情（历史备案可查）。
     */
    @GetMapping("/registrations/{id}")
    public RegistrationDetailResponse detail(@PathVariable long id) {
        return registrationService.getDetail(id);
    }

    /**
     * 审查明细（可复算）。
     */
    @GetMapping("/registrations/{id}/review-records")
    public List<ReviewRecordDto> reviewRecords(@PathVariable long id) {
        return registrationService.getReviewRecords(id);
    }

    /**
     * 产品变更链（全部版本）。
     */
    @GetMapping("/products/{id}/registrations")
    public List<RegistrationSummaryResponse> changeChain(@PathVariable long id) {
        return registrationService.getChangeChain(id);
    }

    /**
     * 期满处理响应。
     */
    public record FinalizeResponse(int finalizedCount) {
    }
}
