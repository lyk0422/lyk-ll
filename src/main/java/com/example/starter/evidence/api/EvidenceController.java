package com.example.starter.evidence.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.evidence.api.dto.CustodyEventView;
import com.example.starter.evidence.api.dto.EvidenceView;
import com.example.starter.evidence.api.dto.RegisterEvidenceRequest;
import com.example.starter.evidence.service.EvidenceService;

/**
 * 证据登记与状态查询 API。
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    /**
     * 登记证据；相同 ID 同摘要幂等重放返回 200，新登记返回 201。
     */
    @PostMapping
    public ResponseEntity<EvidenceView> register(@Valid @RequestBody RegisterEvidenceRequest request) {
        EvidenceView view = evidenceService.register(request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 查询证据状态。
     */
    @GetMapping("/{evidenceId}")
    public EvidenceView get(@PathVariable String evidenceId) {
        return evidenceService.get(evidenceId);
    }

    /**
     * 查询证据的不可变保管事件序列。
     */
    @GetMapping("/{evidenceId}/custody-events")
    public List<CustodyEventView> custodyEvents(@PathVariable String evidenceId) {
        return evidenceService.custodyEvents(evidenceId);
    }
}
