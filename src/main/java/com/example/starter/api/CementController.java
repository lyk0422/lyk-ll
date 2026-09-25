package com.example.starter.api;

import java.time.LocalDate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.CommandActionRequest;
import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.DailySummaryResponse;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.api.dto.ReadingAckResponse;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.SealRequest;
import com.example.starter.api.dto.SwitchPlanRequest;
import com.example.starter.service.CementService;

import jakarta.validation.Valid;

/**
 * 水泥窑替代燃料掺烧与排放联动 API。
 */
@RestController
@RequestMapping("/api")
public class CementController {

    private final CementService cementService;

    public CementController(CementService cementService) {
        this.cementService = cementService;
    }

    /** 方案申报（同步完成合成审核：替代率/氯硫/许可校验）。 */
    @PostMapping("/plans/submit")
    public PlanResultResponse submit(@Valid @RequestBody PlanSubmitRequest request) {
        return cementService.submit(request);
    }

    /** 查询方案状态。 */
    @GetMapping("/plans/{planNo}")
    public PlanStatusResponse plan(@PathVariable String planNo) {
        return cementService.status(planNo);
    }

    /** 启动已审核方案：占用窑执行槽。 */
    @PostMapping("/plans/execute")
    public PlanStatusResponse execute(@Valid @RequestBody ExecutePlanRequest request) {
        return cementService.execute(request);
    }

    /** 方案切换：同事务先停旧后启新。 */
    @PostMapping("/plans/switch")
    public PlanStatusResponse switchPlan(@Valid @RequestBody SwitchPlanRequest request) {
        return cementService.switchPlan(request);
    }

    /** 手动停用执行中方案并释放窑。 */
    @PostMapping("/plans/{planNo}/stop")
    public PlanStatusResponse stop(@PathVariable String planNo) {
        return cementService.stop(planNo);
    }

    /** 排放读数批量上报（测点＋采样时刻去重，乱序归位）。 */
    @PostMapping("/emissions/readings")
    public ReadingAckResponse readings(@Valid @RequestBody ReadingBatchRequest request) {
        return cementService.ingestReadings(request);
    }

    /** 降掺指令下发。 */
    @PostMapping("/commands/issue")
    public CommandResponse issue(@Valid @RequestBody CommandActionRequest request) {
        return cementService.issueCommand(request);
    }

    /** 降掺指令执行确认。 */
    @PostMapping("/commands/confirm")
    public CommandResponse confirm(@Valid @RequestBody CommandActionRequest request) {
        return cementService.confirmCommand(request);
    }

    /** 日结查询（未封存时按当前数据实时复算）。 */
    @GetMapping("/daily-summaries/{planNo}")
    public DailySummaryResponse summary(@PathVariable String planNo,
                                        @RequestParam("date") LocalDate date) {
        return cementService.getDailySummary(planNo, date);
    }

    /** 日结封存（终态；重复封存幂等成功）。 */
    @PostMapping("/daily-summaries/seal")
    public DailySummaryResponse seal(@Valid @RequestBody SealRequest request) {
        return cementService.seal(request);
    }

    /** 手动触发降掺指令超时升级扫描（定时任务亦会自动执行）。 */
    @PostMapping("/admin/command-timeout-scan")
    public int timeoutScan() {
        return cementService.scanCommandTimeouts();
    }
}
