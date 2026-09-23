package com.example.starter.spectrum.api;

import com.example.starter.spectrum.service.LeaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 频谱租约协调 HTTP 接口。
 */
@RestController
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 创建租约。 */
    @PostMapping("/api/leases")
    public LeaseResponse create(@Valid @RequestBody LeaseUpsertRequest request) {
        return leaseService.create(request);
    }

    /** 修改租约，产生新版本。 */
    @PostMapping("/api/leases/{leaseId}/modifications")
    public LeaseResponse modify(@PathVariable long leaseId,
                                @Valid @RequestBody LeaseUpsertRequest request) {
        return leaseService.modify(leaseId, request);
    }

    /** 取消租约（终态、幂等）。 */
    @PostMapping("/api/leases/{leaseId}/cancellations")
    public LeaseResponse cancel(@PathVariable long leaseId,
                                @Valid @RequestBody LeaseCancelRequest request) {
        return leaseService.cancel(leaseId, request);
    }

    /** 读取租约当前状态。 */
    @GetMapping("/api/leases/{leaseId}")
    public LeaseResponse getLease(@PathVariable long leaseId) {
        return leaseService.getLease(leaseId);
    }

    /** 指定时刻活动快照：GET /api/leases/active?at=epochMillis（缺省为当前时刻）。 */
    @GetMapping("/api/leases/active")
    public List<LeaseResponse> activeSnapshot(@RequestParam(required = false) Long at) {
        return leaseService.activeSnapshot(at);
    }

    /** 候选规格冲突解释，不写入。 */
    @PostMapping("/api/conflicts/explain")
    public ConflictResponse explainCandidate(@Valid @RequestBody LeaseUpsertRequest request) {
        return leaseService.explainCandidate(request);
    }

    /** 已存在租约当前版本的冲突解释。 */
    @GetMapping("/api/leases/{leaseId}/conflicts")
    public ConflictResponse explainLease(@PathVariable long leaseId) {
        return leaseService.explainLease(leaseId);
    }

    /**
     * 区域频段占用查询：
     * GET /api/occupancy?cells=A,B&amp;freqLow=...&amp;freqHigh=...&amp;start=...&amp;end=...
     */
    @GetMapping("/api/occupancy")
    public List<OccupancyItem> occupancy(@RequestParam(required = false) List<String> cells,
                                         @RequestParam(required = false) Double freqLow,
                                         @RequestParam(required = false) Double freqHigh,
                                         @RequestParam(required = false) Long start,
                                         @RequestParam(required = false) Long end) {
        return leaseService.occupancy(cells, freqLow, freqHigh, start, end);
    }
}
