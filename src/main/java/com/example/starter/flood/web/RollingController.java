package com.example.starter.flood.web;

import com.example.starter.flood.service.RollingService;
import com.example.starter.flood.web.dto.HydrographRequest;
import com.example.starter.flood.web.dto.HydrographView;
import com.example.starter.flood.web.dto.LevelTraceView;
import com.example.starter.flood.web.dto.RecomputeView;
import com.example.starter.flood.web.dto.RollingCompareView;
import com.example.starter.flood.web.dto.RollingRequest;
import com.example.starter.flood.web.dto.RollingVersionView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 滚动调洪演算入口：过程线录入、每 30 分钟滚动重算（恰满触发）、版本列表/对比/单版本复算、
 * 预警级别轨迹留痕。
 */
@RestController
@RequestMapping("/api")
public class RollingController {

    private final RollingService rollingService;

    public RollingController(RollingService rollingService) {
        this.rollingService = rollingService;
    }

    /**
     * 录入入库洪水过程线（requestId 幂等）。
     */
    @PostMapping("/warnings/{warningNo}/hydrograph")
    @ResponseStatus(HttpStatus.CREATED)
    public HydrographView submitHydrograph(@PathVariable String warningNo,
                                           @Valid @RequestBody HydrographRequest request) {
        return rollingService.submitHydrograph(warningNo, request);
    }

    /**
     * 滚动重算调洪结果：恰满 30 分钟触发新版本，旧版本保留可查。
     */
    @PostMapping("/warnings/{warningNo}/rolling-routings")
    @ResponseStatus(HttpStatus.CREATED)
    public RollingVersionView roll(@PathVariable String warningNo,
                                   @Valid @RequestBody RollingRequest request) {
        return rollingService.roll(warningNo, request);
    }

    /**
     * 滚动演算版本列表（旧版本保留可查）。
     */
    @GetMapping("/warnings/{warningNo}/rolling-routings")
    public List<RollingVersionView> listVersions(@PathVariable String warningNo) {
        return rollingService.listVersions(warningNo);
    }

    /**
     * 演算版本对比查询。
     */
    @GetMapping("/warnings/{warningNo}/rolling-routings/compare")
    public RollingCompareView compare(@PathVariable String warningNo,
                                      @RequestParam int fromVersion,
                                      @RequestParam int toVersion) {
        return rollingService.compare(warningNo, fromVersion, toVersion);
    }

    /**
     * 预警级别轨迹（自动提级留痕可查）。
     */
    @GetMapping("/warnings/{warningNo}/level-events")
    public LevelTraceView levelTrace(@PathVariable String warningNo) {
        return rollingService.levelTrace(warningNo);
    }

    /**
     * 任一版本单独复算：按留存的源输入快照重算并与存库结果比对。
     */
    @GetMapping("/rolling-routings/{rollingNo}/recompute")
    public RecomputeView recompute(@PathVariable String rollingNo) {
        return rollingService.recompute(rollingNo);
    }
}
