package com.example.starter.flood.web;

import com.example.starter.flood.service.RollingRoutingService;
import com.example.starter.flood.web.dto.RollingRoutingRequest;
import com.example.starter.flood.web.dto.RollingRoutingView;
import com.example.starter.flood.web.dto.RoutingCompareView;
import com.example.starter.flood.web.dto.RoutingVersionView;
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
 * 滚动演算入口：按入库洪水过程线滚动重算（恰满周期触发），
 * 演算版本查询与版本对比。
 */
@RestController
@RequestMapping("/api/routings")
public class RollingRoutingController {

    private final RollingRoutingService rollingRoutingService;

    public RollingRoutingController(RollingRoutingService rollingRoutingService) {
        this.rollingRoutingService = rollingRoutingService;
    }

    @PostMapping("/rolling")
    @ResponseStatus(HttpStatus.CREATED)
    public RollingRoutingView roll(@Valid @RequestBody RollingRoutingRequest request) {
        return rollingRoutingService.roll(request);
    }

    /**
     * 处置链全部演算版本（旧版本保留可查）。
     */
    @GetMapping(params = "warningNo")
    public List<RoutingVersionView> listVersions(@RequestParam String warningNo) {
        return rollingRoutingService.listVersions(warningNo);
    }

    /**
     * 演算版本对比（同一处置链内两个版本）。
     */
    @GetMapping("/compare")
    public RoutingCompareView compare(@RequestParam String from, @RequestParam String to) {
        return rollingRoutingService.compare(from, to);
    }

    /**
     * 按编号查询任一演算版本（含输入快照，可单独复算）。
     */
    @GetMapping("/{routingNo}")
    public RoutingVersionView getVersion(@PathVariable String routingNo) {
        return rollingRoutingService.getVersion(routingNo);
    }
}
