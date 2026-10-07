package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.RepairApplyRequest;
import com.example.starter.rolling.api.dto.RepairApplyView;
import com.example.starter.rolling.api.dto.RepairInspectionRequest;
import com.example.starter.rolling.api.dto.RepairInspectionView;
import com.example.starter.rolling.api.dto.RollGenerationChainView;
import com.example.starter.rolling.service.RollRepairService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 到期轧辊返修放行 HTTP 接口：申请、两名检验员确认、申请查询、代次链查询。
 */
@RestController
@RequestMapping("/api/rolling/rolls/repairs")
public class RollRepairController {

    private final RollRepairService service;

    public RollRepairController(RollRepairService service) {
        this.service = service;
    }

    /** 提交返修放行申请（磨后直径、新辊号、新周期上限、检验凭据与聚合 expectedVersion）。 */
    @PostMapping
    public RepairApplyView apply(@Valid @RequestBody RepairApplyRequest request) {
        return service.apply(request);
    }

    /** 检验员确认：两名不同检验员均合格才原子建立下一代；任一不合格即终止。 */
    @PostMapping("/{applicationId}/inspections")
    public RepairInspectionView inspect(@PathVariable long applicationId,
                                        @Valid @RequestBody RepairInspectionRequest request) {
        return service.inspect(applicationId, request);
    }

    @GetMapping("/{applicationId}")
    public RepairApplyView getApplication(@PathVariable long applicationId) {
        return service.getApplication(applicationId);
    }

    /** 按任意一代辊号查询代次链与各代独立台账。 */
    @GetMapping("/by-roll/{rollNo}/chain")
    public RollGenerationChainView getChain(@PathVariable String rollNo) {
        return service.getChainByRollNo(rollNo);
    }
}
