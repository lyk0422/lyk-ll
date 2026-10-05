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
 * 聚集性分析接口：聚集事件查询/关闭、协查单生成/结案、时间线与协查轨迹。
 */
@RestController
@RequestMapping("/api/clusters")
public class ClusterController {

    private final ClusterService service;

    public ClusterController(ClusterService service) {
        this.service = service;
    }

    @PostMapping("/{eventNo}/investigations")
    public InvestigationResponse createInvestigation(@PathVariable String eventNo,
                                                     @Valid @RequestBody CreateInvestigationRequest request) {
        return service.createInvestigation(eventNo, request);
    }

    @PostMapping("/investigations/{id}/conclude")
    public InvestigationResponse concludeInvestigation(@PathVariable Long id,
                                                       @Valid @RequestBody ConcludeInvestigationRequest request) {
        return service.concludeInvestigation(id, request);
    }

    @PostMapping("/{eventNo}/close")
    public ClusterEventResponse close(@PathVariable String eventNo,
                                      @Valid @RequestBody CloseClusterRequest request) {
        return service.closeEvent(eventNo, request);
    }

    @GetMapping("/{eventNo}")
    public ClusterEventResponse get(@PathVariable String eventNo) {
        return service.getEvent(eventNo);
    }

    @GetMapping("/{eventNo}/timeline")
    public List<TimelineEntryResponse> timeline(@PathVariable String eventNo) {
        return service.getTimeline(eventNo);
    }

    @GetMapping("/{eventNo}/investigations")
    public List<InvestigationResponse> eventInvestigations(@PathVariable String eventNo) {
        return service.getEventInvestigations(eventNo);
    }

    @GetMapping("/investigations/{id}")
    public InvestigationResponse getInvestigation(@PathVariable Long id) {
        return service.getInvestigation(id);
    }
}
