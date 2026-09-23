package com.example.starter.signing.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.signing.service.SigningService;

import jakarta.validation.Valid;

/**
 * 多方签署流程 REST API：
 * <ul>
 *     <li>POST /api/signing/packages                建包</li>
 *     <li>POST /api/signing/packages/{id}/signatures 提交签名</li>
 *     <li>POST /api/signing/packages/{id}/revisions  修订文档</li>
 *     <li>POST /api/signing/packages/{id}/cancel     取消</li>
 *     <li>GET  /api/signing/packages/{id}/status     流程状态</li>
 *     <li>GET  /api/signing/packages/{id}/evidence   冻结证据包</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/signing/packages")
public class SigningController {

    private final SigningService service;

    public SigningController(SigningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActionResponse> create(@Valid @RequestBody CreatePackageRequest request) {
        ActionResponse response = service.createPackage(request);
        return ResponseEntity
                .created(URI.create("/api/signing/packages/" + response.packageId() + "/status"))
                .body(response);
    }

    @PostMapping("/{packageId}/signatures")
    public ResponseEntity<Object> sign(@PathVariable String packageId,
                                       @Valid @RequestBody SignatureSubmitRequest request) {
        SigningService.SubmitOutcome outcome = service.submitSignature(packageId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{packageId}/revisions")
    public ResponseEntity<ActionResponse> revise(@PathVariable String packageId,
                                                 @Valid @RequestBody RevisionRequest request) {
        return ResponseEntity.ok(service.revise(packageId, request));
    }

    @PostMapping("/{packageId}/cancel")
    public ResponseEntity<ActionResponse> cancel(@PathVariable String packageId,
                                                 @RequestBody(required = false) CancelRequest request) {
        return ResponseEntity.ok(service.cancel(packageId, request));
    }

    @GetMapping("/{packageId}/status")
    public PackageStatusResponse status(@PathVariable String packageId) {
        return service.getStatus(packageId);
    }

    @GetMapping("/{packageId}/evidence")
    public EvidenceResponse evidence(@PathVariable String packageId) {
        return service.getEvidence(packageId);
    }
}
