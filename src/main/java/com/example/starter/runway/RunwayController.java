package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 跑道临时关闭巡检与开放决策 API。
 */
@RestController
@RequestMapping("/api")
public class RunwayController {

    private final RunwayService service;

    public RunwayController(RunwayService service) {
        this.service = service;
    }

    /**
     * 接报：触发临时关闭并同步发布关闭通告；requestId 幂等。
     */
    @PostMapping("/runway-events/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse report(@Valid @RequestBody ReportRequest request) {
        return service.report(request);
    }

    /**
     * 巡检开始：15分钟迟延判定、低能见度双人校验；归档后仅登记迟到记录。
     */
    @PostMapping("/runway-events/{eventId}/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResponse startInspection(@PathVariable long eventId,
                                              @Valid @RequestBody InspectionRequest request) {
        return service.startInspection(eventId, request);
    }

    /**
     * 巡检评估：记录摩擦系数与评估类型；requestId 幂等。
     */
    @PostMapping("/runway-events/{eventId}/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentResponse assess(@PathVariable long eventId,
                                     @Valid @RequestBody AssessmentRequest request) {
        return service.assess(eventId, request);
    }

    /**
     * 恢复开放：与航行通告发布同一事务完成；requestId 幂等。
     */
    @PostMapping("/runway-events/{eventId}/reopen")
    public ReopenResponse reopen(@PathVariable long eventId,
                                 @Valid @RequestBody ReopenRequest request) {
        return service.reopen(eventId, request);
    }

    /**
     * 归档：终态；重复归档返回幂等成功。
     */
    @PostMapping("/runway-events/{eventId}/archive")
    public ArchiveResponse archive(@PathVariable long eventId) {
        return service.archive(eventId);
    }

    /**
     * 事件详情与状态时间线（可复算）。
     */
    @GetMapping("/runway-events/{eventId}")
    public EventDetailResponse detail(@PathVariable long eventId) {
        return service.getDetail(eventId);
    }

    /**
     * 摩擦系数轨迹（可复算）。
     */
    @GetMapping("/runway-events/{eventId}/friction-trajectory")
    public List<FrictionPoint> frictionTrajectory(@PathVariable long eventId) {
        return service.getFrictionTrajectory(eventId);
    }

    /**
     * 通告记录（可复算）。
     */
    @GetMapping("/runway-events/{eventId}/notams")
    public List<NotamView> notams(@PathVariable long eventId) {
        return service.getNotams(eventId);
    }

    /**
     * 上报气象读数（合成数据）。
     */
    @PostMapping("/weather-readings")
    @ResponseStatus(HttpStatus.CREATED)
    public WeatherResponse addWeather(@Valid @RequestBody WeatherRequest request) {
        return service.addWeatherReading(request);
    }

    /**
     * 按跑道查询气象读数。
     */
    @GetMapping("/weather-readings")
    public List<WeatherResponse> weatherReadings(@RequestParam String runwayId) {
        return service.listWeatherReadings(runwayId);
    }
}
