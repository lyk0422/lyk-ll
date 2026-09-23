package com.example.starter.signing;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.signing.Contracts.CancelPackageRequest;
import com.example.starter.signing.Contracts.CreatePackageRequest;
import com.example.starter.signing.Contracts.EvidencePackage;
import com.example.starter.signing.Contracts.PackageState;
import com.example.starter.signing.Contracts.RevisePackageRequest;
import com.example.starter.signing.Contracts.SignatureResult;
import com.example.starter.signing.Contracts.SubmitSignatureRequest;

/**
 * 多方签署流程 REST API。
 */
@RestController
@RequestMapping("/api/signing-packages")
public class SigningController {

    private final SigningService service;

    public SigningController(SigningService service) {
        this.service = service;
    }

    /** 建包：创建签署包与有序步骤，流程进入 ACTIVE。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackageState create(@Valid @RequestBody CreatePackageRequest request) {
        return service.createPackage(request);
    }

    /** 提交签名：携带幂等键 requestId 与乐观版本 expectedVersion。 */
    @PostMapping("/{packageId}/signatures")
    public SignatureResult submitSignature(@PathVariable String packageId,
                                           @Valid @RequestBody SubmitSignatureRequest request) {
        return service.submitSignature(packageId, request);
    }

    /** 修订文档：新修订号生效，旧修订签名全部失效并保留历史，流程重置到第一步。 */
    @PostMapping("/{packageId}/revisions")
    public PackageState revise(@PathVariable String packageId,
                               @Valid @RequestBody RevisePackageRequest request) {
        return service.revise(packageId, request);
    }

    /** 取消流程：终态，后续签名一律拒绝。 */
    @PostMapping("/{packageId}/cancel")
    public PackageState cancel(@PathVariable String packageId,
                               @Valid @RequestBody CancelPackageRequest request) {
        return service.cancel(packageId, request);
    }

    /** 查询流程状态。 */
    @GetMapping("/{packageId}")
    public PackageState state(@PathVariable String packageId) {
        return service.getState(packageId);
    }

    /** 查询证据包：仅最终完成冻结后可用，重复查询摘要稳定。 */
    @GetMapping("/{packageId}/evidence")
    public EvidencePackage evidence(@PathVariable String packageId) {
        return service.getEvidence(packageId);
    }
}
