package com.example.starter.forestry.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.forestry.dto.AcceptanceRequest;
import com.example.starter.forestry.dto.ApplicationView;
import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.RenewalView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.service.ForestryService;

import jakarta.validation.Valid;

/**
 * 林木采伐监管 HTTP 接口：申请 → 审核 → 发证 → 采伐确认 → 更新验收（含归档）。
 */
@RestController
@RequestMapping("/api")
public class ForestryController {

    private final ForestryService service;

    public ForestryController(ForestryService service) {
        this.service = service;
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationView apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request);
    }

    @PostMapping("/applications/{id}/review")
    public ApplicationView review(@PathVariable("id") String applicationId,
                                  @Valid @RequestBody ReviewRequest request) {
        return service.review(applicationId, request);
    }

    @PostMapping("/licenses")
    @ResponseStatus(HttpStatus.CREATED)
    public LicenseView issue(@Valid @RequestBody IssueRequest request) {
        return service.issue(request);
    }

    @PostMapping("/licenses/confirm")
    public LicenseView confirm(@Valid @RequestBody ConfirmRequest request) {
        return service.confirm(request);
    }

    @PostMapping("/renewals/acceptance")
    public RenewalView acceptance(@Valid @RequestBody AcceptanceRequest request) {
        return service.acceptance(request);
    }

    @PostMapping("/applications/{id}/archive")
    public ApplicationView archive(@PathVariable("id") String applicationId) {
        return service.archive(applicationId);
    }

    /**
     * 手动触发逾期采伐证扫描（自动作废并当日释放限额）；生产由每日定时任务执行。
     */
    @PostMapping("/maintenance/expired-scan")
    public java.util.Map<String, Integer> scanExpired() {
        return java.util.Map.of("voided", service.processExpiredLicenses());
    }

    @GetMapping("/applications/{id}")
    public ApplicationView getApplication(@PathVariable("id") String applicationId) {
        return service.getApplication(applicationId);
    }

    @GetMapping("/licenses/{id}")
    public LicenseView getLicense(@PathVariable("id") String licenseId) {
        return service.getLicense(licenseId);
    }

    @GetMapping("/renewals/{applicationId}")
    public RenewalView getRenewal(@PathVariable("applicationId") String applicationId) {
        return service.getRenewal(applicationId);
    }
}
