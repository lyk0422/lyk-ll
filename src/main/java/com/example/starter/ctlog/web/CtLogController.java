package com.example.starter.ctlog.web;

import com.example.starter.ctlog.domain.SplitAlert;
import com.example.starter.ctlog.dto.AddLeafRequest;
import com.example.starter.ctlog.dto.AddLeafResponse;
import com.example.starter.ctlog.dto.AlertView;
import com.example.starter.ctlog.dto.AlertsResponse;
import com.example.starter.ctlog.dto.ConsistencyProofResponse;
import com.example.starter.ctlog.dto.InclusionProofResponse;
import com.example.starter.ctlog.dto.ObserveRequest;
import com.example.starter.ctlog.dto.ObserveResponse;
import com.example.starter.ctlog.dto.TreeHeadResponse;
import com.example.starter.ctlog.dto.VerifyConsistencyRequest;
import com.example.starter.ctlog.dto.VerifyInclusionRequest;
import com.example.starter.ctlog.dto.VerifyResponse;
import com.example.starter.ctlog.service.CtLogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证书透明日志 HTTP API（骨架，端点随后接入服务实现）。
 */
@RestController
@RequestMapping("/ct/v1")
public class CtLogController {

    private final CtLogService service;

    public CtLogController(CtLogService service) {
        this.service = service;
    }

    @PostMapping("/add-leaf")
    public AddLeafResponse addLeaf(@Valid @RequestBody AddLeafRequest request) {
        return service.addLeaf(request);
    }

    @GetMapping("/tree-head")
    public TreeHeadResponse treeHead(@RequestParam(required = false) Long treeSize) {
        return service.getTreeHead(treeSize);
    }

    @GetMapping("/inclusion-proof")
    public InclusionProofResponse inclusionProof(@RequestParam long leafIndex,
                                                 @RequestParam(required = false) Long treeSize) {
        return service.inclusionProof(leafIndex, treeSize);
    }

    @GetMapping("/consistency-proof")
    public ConsistencyProofResponse consistencyProof(@RequestParam long firstTreeSize,
                                                     @RequestParam(required = false) Long secondTreeSize) {
        return service.consistencyProof(firstTreeSize, secondTreeSize);
    }

    @PostMapping("/verify/inclusion")
    public VerifyResponse verifyInclusion(@RequestBody VerifyInclusionRequest request) {
        return new VerifyResponse(service.verifyInclusion(request));
    }

    @PostMapping("/verify/consistency")
    public VerifyResponse verifyConsistency(@RequestBody VerifyConsistencyRequest request) {
        return new VerifyResponse(service.verifyConsistency(request));
    }

    @PostMapping("/observe")
    public ObserveResponse observe(@Valid @RequestBody ObserveRequest request) {
        return service.observe(request);
    }

    @GetMapping("/alerts")
    public AlertsResponse alerts() {
        return new AlertsResponse(service.findAlerts().stream().map(CtLogController::toView).toList());
    }

    private static AlertView toView(SplitAlert alert) {
        return new AlertView(alert.alertId(), alert.alertType(), alert.observerId(), alert.treeSize(),
                alert.logRootHash(), alert.observerRootHash(), alert.evidence(),
                alert.createdAt() == null ? null : alert.createdAt().toString());
    }
}
