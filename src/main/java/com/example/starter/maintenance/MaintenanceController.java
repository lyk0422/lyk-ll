package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 维保台账接口：登记维保记录（幂等）、按电梯查询台账。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 维保登记请求。
     *
     * @param requestId    幂等键
     * @param elevatorCode 电梯编号
     * @param staff1Cert   维保人员1 持证编号
     * @param staff2Cert   维保人员2 持证编号（可空）
     * @param caseId       关联救援单（可空）
     * @param completedAt  完成时间（UTC，可空默认当前）
     */
    public record RegisterRequest(@NotBlank String requestId, @NotBlank String elevatorCode,
                                  @NotBlank String staff1Cert, String staff2Cert,
                                  Long caseId, Instant completedAt) {
    }

    @PostMapping("/records")
    public MaintenanceRepository.MaintenanceRecord register(@RequestBody @Valid RegisterRequest req) {
        return maintenanceService.register(req.requestId(), req.elevatorCode(), req.staff1Cert(),
                req.staff2Cert(), req.caseId(), req.completedAt());
    }

    @GetMapping("/records/{elevatorCode}")
    public List<MaintenanceRepository.MaintenanceRecord> list(@PathVariable String elevatorCode) {
        return maintenanceService.listByElevator(elevatorCode);
    }
}
