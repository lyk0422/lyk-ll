package com.example.starter.epidemic.api;

import com.example.starter.epidemic.service.ClusterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聚集性疫情分析接口：事件查询、协查单生成/办结、事件关闭。
 */
@RestController
@RequestMapping("/api/clusters")
public class ClusterEventController {

    private final ClusterService service;

    public ClusterEventController(ClusterService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClusterEventSummary> list() {
        return service.listEvents();
    }

    @GetMapping("/{eventId}")
    public ClusterEventResponse get(@PathVariable long eventId) {
        return service.getEvent(eventId);
    }

    @PostMapping("/{eventId}/investigations")
    public InvestigationResponse createInvestigation(@PathVariable long eventId,
                                                     @Valid @RequestBody CreateInvestigationRequest request) {
        return service.createInvestigation(eventId, request);
    }

    @PostMapping("/investigations/{investigationId}/conclude")
    public InvestigationResponse conclude(@PathVariable long investigationId,
                                          @Valid @RequestBody ConcludeInvestigationRequest request) {
        return service.concludeInvestigation(investigationId, request);
    }

    @GetMapping("/{eventId}/investigations")
    public List<InvestigationResponse> investigations(@PathVariable long eventId) {
        return service.getInvestigations(eventId);
    }

    @PostMapping("/{eventId}/close")
    public ClusterCloseResponse close(@PathVariable long eventId) {
        return service.closeEvent(eventId);
    }
}
