package com.example.starter.wb.web;

import com.example.starter.wb.dto.AssemblyPlanView;
import com.example.starter.wb.dto.AssemblyRequest;
import com.example.starter.wb.service.AssemblyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集装器装配图接口：提交（expectedVersion 乐观并发 + requestId 幂等）、当前图与历史版本查询。
 * 装配图接入既有载重平衡流程，关闭放行在既有 /close 内原子释放实物占用。
 */
@RestController
@RequestMapping("/api/flights/{flightId}/assembly")
public class AssemblyController {

    private final AssemblyService service;

    public AssemblyController(AssemblyService service) {
        this.service = service;
    }

    @PostMapping
    public AssemblyPlanView submit(@PathVariable Long flightId,
                                   @Valid @RequestBody AssemblyRequest request) {
        return service.submitAssembly(flightId, request);
    }

    @GetMapping
    public AssemblyPlanView active(@PathVariable Long flightId) {
        return service.getActivePlan(flightId);
    }

    @GetMapping("/versions")
    public List<AssemblyPlanView> versions(@PathVariable Long flightId) {
        return service.listPlans(flightId);
    }
}
