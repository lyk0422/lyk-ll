package com.example.starter.web;

import com.example.starter.domain.CustodyEvent;
import com.example.starter.domain.Evidence;
import com.example.starter.service.EvidenceService;
import com.example.starter.web.dto.CustodyEventResponse;
import com.example.starter.web.dto.EvidenceResponse;
import com.example.starter.web.dto.RegisterEvidenceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证据登记、状态与保管链查询 API。
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping
    public ResponseEntity<EvidenceResponse> register(@Valid @RequestBody RegisterEvidenceRequest request) {
        EvidenceService.RegisterResult result = evidenceService.registerResult(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toResponse(result.evidence()));
    }

    @GetMapping("/{evidenceId}")
    public EvidenceResponse get(@PathVariable String evidenceId) {
        return toResponse(evidenceService.get(evidenceId));
    }

    @GetMapping("/{evidenceId}/custody")
    public List<CustodyEventResponse> custody(@PathVariable String evidenceId) {
        return evidenceService.custodyTrail(evidenceId).stream()
                .map(e -> new CustodyEventResponse(e.id(), e.evidenceId(),
                        e.eventType().name(), e.detail(), e.eventTime()))
                .toList();
    }

    private EvidenceResponse toResponse(Evidence e) {
        return new EvidenceResponse(e.evidenceId(), e.sha256(), e.collectedAt(),
                e.expiresAt(), e.status().name(), e.createdAt());
    }
}
