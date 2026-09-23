package com.example.starter.spectrum.api;

import com.example.starter.spectrum.LeaseConflictException;
import com.example.starter.spectrum.ConflictDetail;
import com.example.starter.spectrum.service.LeaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 频谱租约协调 REST API。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 创建租约（携带 requestId 幂等键）。 */
    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreateLeaseRequest request) {
        LeaseService.ServiceResult result = leaseService.create(request);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /** 冲突预检：返回候选与当前活动集合的冲突解释，只读不落库。 */
    @PostMapping("/explain")
    public Map<String, Object> explain(@Valid @RequestBody ExplainRequest request) {
        List<ConflictDetail> details = leaseService.explain(
                request.regions(), request.start(), request.end(),
                request.freqLowMHz(), request.freqHighMHz(),
                request.mode(), request.eirpMw());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("compatible", details.isEmpty());
        body.put("details", details);
        return body;
    }

    /** 修改租约：携带 expectedVersion，成功后追加新版本。 */
    @PostMapping("/{leaseId}/modify")
    public ResponseEntity<Object> modify(@PathVariable String leaseId,
                                         @Valid @RequestBody ModifyLeaseRequest request) {
        if (!leaseId.equals(request.leaseId())) {
            throw new LeaseConflictException("LEASE_ID_MISMATCH",
                    "路径租约标识与请求体不一致", List.of());
        }
        LeaseService.ServiceResult result = leaseService.modify(request);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /** 取消租约：只改变未来有效性，保留历史版本。 */
    @PostMapping("/{leaseId}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String leaseId,
                                         @Valid @RequestBody CancelLeaseRequest request) {
        if (!leaseId.equals(request.leaseId())) {
            throw new LeaseConflictException("LEASE_ID_MISMATCH",
                    "路径租约标识与请求体不一致", List.of());
        }
        LeaseService.ServiceResult result = leaseService.cancel(request);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /** 查询单个租约（当前版本与事件流）。 */
    @GetMapping("/{leaseId}")
    public LeaseResponse get(@PathVariable String leaseId) {
        return LeaseViews.toResponse(leaseService.requireLease(leaseId));
    }

    /** 指定 UTC 时刻的活动租约快照。 */
    @GetMapping("/active")
    public List<LeaseResponse> activeAt(@RequestParam("at") Instant at) {
        return leaseService.activeSnapshot(at);
    }

    /** 指定区域与频段在指定时刻的占用查询。 */
    @GetMapping("/occupancy")
    public List<LeaseResponse> occupancy(
            @RequestParam("at") Instant at,
            @RequestParam("region") List<String> regions,
            @RequestParam("freqLowMHz") BigDecimal freqLowMHz,
            @RequestParam("freqHighMHz") BigDecimal freqHighMHz) {
        return leaseService.occupancy(at, regions, freqLowMHz, freqHighMHz);
    }
}
