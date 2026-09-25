package com.example.starter.runway;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.runway.dto.ClosureDetailView;
import com.example.starter.runway.dto.ClosureEventView;
import com.example.starter.runway.dto.InspectionRequest;
import com.example.starter.runway.dto.ReopenRequest;
import com.example.starter.runway.dto.ReportClosureRequest;

/**
 * 跑道临时关闭巡检与开放决策 API。
 */
@RestController
@RequestMapping("/api")
public class RunwayClosureController {

    private final RunwayClosureService service;

    public RunwayClosureController(RunwayClosureService service) {
        this.service = service;
    }

    /**
     * 接报：FOD 或道面破损即触发临时关闭。
     */
    @PostMapping("/runways/{runwayId}/closures")
    public ResponseEntity<String> reportClosure(@PathVariable String runwayId,
            @Valid @RequestBody ReportClosureRequest request) {
        return json(service.reportClosure(runwayId, request));
    }

    /**
     * 巡检评估：记录巡检与摩擦系数，按规则判定生效/迟延/升级。
     */
    @PostMapping("/closures/{eventId}/inspections")
    public ResponseEntity<String> recordInspection(@PathVariable long eventId,
            @Valid @RequestBody InspectionRequest request) {
        return json(service.recordInspection(eventId, request));
    }

    /**
     * 恢复开放：与航行通告发布在同一事务内完成。
     */
    @PostMapping("/closures/{eventId}/reopen")
    public ResponseEntity<String> reopen(@PathVariable long eventId,
            @Valid @RequestBody ReopenRequest request) {
        return json(service.reopen(eventId, request));
    }

    /**
     * 归档：终态，重复归档返回幂等成功。
     */
    @PostMapping("/closures/{eventId}/archive")
    public ClosureEventView archive(@PathVariable long eventId) {
        return service.archive(eventId);
    }

    /**
     * 事件详情：状态时间线、系数轨迹与通告记录。
     */
    @GetMapping("/closures/{eventId}")
    public ClosureDetailView detail(@PathVariable long eventId) {
        return service.detail(eventId);
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
