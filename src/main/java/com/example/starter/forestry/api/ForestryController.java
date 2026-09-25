package com.example.starter.forestry.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.forestry.repo.ForestryRepository.ApplicationRow;
import com.example.starter.forestry.repo.ForestryRepository.BlacklistRow;
import com.example.starter.forestry.repo.ForestryRepository.LateEventRow;
import com.example.starter.forestry.repo.ForestryRepository.LicenseRow;
import com.example.starter.forestry.repo.ForestryRepository.QuotaRow;
import com.example.starter.forestry.repo.ForestryRepository.TaskRow;
import com.example.starter.forestry.service.ForestryService;
import com.example.starter.forestry.service.ForestryService.AcceptanceResult;
import com.example.starter.forestry.service.ForestryService.ApplicationResult;
import com.example.starter.forestry.service.ForestryService.ArchiveResult;
import com.example.starter.forestry.service.ForestryService.ConfirmResult;
import com.example.starter.forestry.service.ForestryService.LicenseResult;

/**
 * 林木采伐许可与迹地更新联动 API。
 */
@RestController
@RequestMapping("/api")
public class ForestryController {

    private final ForestryService service;

    public ForestryController(ForestryService service) {
        this.service = service;
    }

    /** 采伐申请请求。 */
    public record ApplicationRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 32) String plotCode,
            @NotBlank @Size(max = 64) String operatorId,
            @NotNull @Positive Integer applyYear,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal volume) {
    }

    /** 发证请求。 */
    public record LicenseRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull @Positive Long applicationId) {
    }

    /** 更新验收请求。 */
    public record AcceptanceRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal passRate) {
    }

    /** 提交采伐申请（同步审核）。拒绝为正常业务结果，HTTP 200 且 status=REJECTED。 */
    @PostMapping("/applications")
    public ApplicationResult submitApplication(@Valid @RequestBody ApplicationRequest request) {
        return service.submitApplication(request.requestId(), request.plotCode(),
                request.operatorId(), request.applyYear(), request.volume());
    }

    /** 发证：串行占用限额，恰达限额合法、超出整笔拒绝（409）。 */
    @PostMapping("/licenses")
    public LicenseResult issueLicense(@Valid @RequestBody LicenseRequest request) {
        return service.issueLicense(request.requestId(), request.applicationId());
    }

    /** 采伐确认：第 90 个自然日（含）内有效，逾期作废并释放限额（409）。 */
    @PostMapping("/licenses/{licenseId}/confirm")
    public ConfirmResult confirmHarvest(@PathVariable long licenseId) {
        return service.confirmHarvest(licenseId);
    }

    /** 归档：终态，重复归档幂等成功。 */
    @PostMapping("/licenses/{licenseId}/archive")
    public ArchiveResult archiveLicense(@PathVariable long licenseId) {
        return service.archiveLicense(licenseId);
    }

    /** 提交更新验收：合格率不低于 85%（恰达合格）。 */
    @PostMapping("/regeneration-tasks/{taskId}/acceptance")
    public AcceptanceResult submitAcceptance(@PathVariable long taskId,
                                             @Valid @RequestBody AcceptanceRequest request) {
        return service.submitAcceptance(taskId, request.requestId(), request.passRate());
    }

    @GetMapping("/applications/{applicationId}")
    public ApplicationRow getApplication(@PathVariable long applicationId) {
        return service.getApplication(applicationId);
    }

    @GetMapping("/licenses/{licenseId}")
    public LicenseRow getLicense(@PathVariable long licenseId) {
        return service.getLicense(licenseId);
    }

    @GetMapping("/regeneration-tasks/{taskId}")
    public TaskRow getTask(@PathVariable long taskId) {
        return service.getTask(taskId);
    }

    @GetMapping("/quotas")
    public QuotaRow getQuota(@RequestParam int year, @RequestParam String tier) {
        return service.getQuota(year, tier);
    }

    @GetMapping("/blacklist/{operatorId}")
    public BlacklistRow getBlacklist(@PathVariable String operatorId) {
        return service.getBlacklist(operatorId);
    }

    @GetMapping("/late-events")
    public List<LateEventRow> listLateEvents() {
        return service.listLateEvents();
    }
}
