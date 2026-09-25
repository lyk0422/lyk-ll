package com.example.starter.rescue;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;

/**
 * 救援案件接口：接报/派单/到场/施救/复检恢复/回访结案/超时升级扫描/时间线查询。
 */
@RestController
@RequestMapping("/api/rescue")
public class RescueController {

    private final RescueService rescueService;

    public RescueController(RescueService rescueService) {
        this.rescueService = rescueService;
    }

    /**
     * 接报请求。
     *
     * @param requestId    幂等键
     * @param elevatorCode 电梯编号
     * @param reportedAt   接报时间（UTC，可空默认当前）
     */
    public record ReportRequest(@NotBlank String requestId, @NotBlank String elevatorCode,
                                Instant reportedAt) {
    }

    /**
     * 派单请求。
     */
    public record DispatchRequest(@NotBlank String requestId, @NotBlank String dispatcher) {
    }

    /**
     * 施救请求：须两名不同持证人员。
     */
    public record RescueRequest(List<String> staffCertNos) {
    }

    /**
     * 复检请求：result 仅 PASS 放行。
     */
    public record RecheckRequest(@NotBlank String result) {
    }

    /**
     * 结案请求。
     */
    public record CloseRequest(@NotBlank String requestId, String feedback) {
    }

    @PostMapping("/reports")
    public RescueCase report(@RequestBody @jakarta.validation.Valid ReportRequest req) {
        return rescueService.report(req.requestId(), req.elevatorCode(), req.reportedAt());
    }

    @PostMapping("/cases/{id}/dispatch")
    public RescueCase dispatch(@PathVariable long id, @RequestBody @jakarta.validation.Valid DispatchRequest req) {
        return rescueService.dispatch(id, req.requestId(), req.dispatcher());
    }

    @PostMapping("/cases/{id}/arrive")
    public RescueCase arrive(@PathVariable long id) {
        return rescueService.arrive(id);
    }

    @PostMapping("/cases/{id}/rescue")
    public RescueCase rescue(@PathVariable long id, @RequestBody RescueRequest req) {
        return rescueService.rescue(id, req.staffCertNos());
    }

    @PostMapping("/cases/{id}/recheck")
    public RescueCase recheck(@PathVariable long id, @RequestBody @jakarta.validation.Valid RecheckRequest req) {
        return rescueService.recheck(id, req.result());
    }

    @PostMapping("/cases/{id}/close")
    public RescueCase close(@PathVariable long id, @RequestBody @jakarta.validation.Valid CloseRequest req) {
        return rescueService.close(id, req.requestId(), req.feedback());
    }

    /**
     * 超时升级扫描：接报后恰满 30 分钟未到场即升级通报。
     */
    @PostMapping("/escalations/run")
    public List<Long> escalateOverdue() {
        return rescueService.escalateOverdue();
    }

    @GetMapping("/cases/{id}")
    public RescueCase getCase(@PathVariable long id) {
        return rescueService.getCase(id);
    }

    @GetMapping("/cases/{id}/timeline")
    public List<RescueRepository.CaseEvent> timeline(@PathVariable long id) {
        return rescueService.timeline(id);
    }
}
