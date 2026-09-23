package com.example.starter.dr.web;

import com.example.starter.dr.ApiModels;
import com.example.starter.dr.service.DemandResponseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求响应事件执行与结算冻结 API。
 *
 * <ul>
 *   <li>POST /api/dr/events 建事件</li>
 *   <li>POST /api/dr/events/{eventId}/enrollments 登记电表</li>
 *   <li>PUT  /api/dr/events/{eventId}/meters/{meterId}/curves/{dataType} 写入/更正曲线</li>
 *   <li>POST /api/dr/events/{eventId}/settlements/freeze 冻结结算</li>
 *   <li>GET  /api/dr/events/{eventId}/settlement 查询冻结证据</li>
 *   <li>GET  /api/dr/events/{eventId}/adjustments 查询调整建议</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dr")
public class DemandResponseController {

    private final DemandResponseService service;

    public DemandResponseController(DemandResponseService service) {
        this.service = service;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.EventResponse createEvent(@Valid @RequestBody ApiModels.CreateEventRequest request) {
        return service.createEvent(request);
    }

    @GetMapping("/events/{eventId}")
    public ApiModels.EventResponse getEvent(@PathVariable String eventId) {
        return service.getEvent(eventId);
    }

    @PostMapping("/events/{eventId}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.EventResponse enrollMeter(@PathVariable String eventId,
                                               @Valid @RequestBody ApiModels.EnrollMeterRequest request) {
        return service.enrollMeter(eventId, request);
    }

    @PostMapping("/events/{eventId}/meters/{meterId}/curves/{dataType}")
    public ApiModels.CurveWriteResponse writeCurve(@PathVariable String eventId,
                                                   @PathVariable String meterId,
                                                   @PathVariable String dataType,
                                                   @Valid @RequestBody ApiModels.CurveWriteRequest request) {
        return service.writeCurve(eventId, meterId, dataType, request);
    }

    @PostMapping("/events/{eventId}/settlements/freeze")
    public ApiModels.SettlementResponse freeze(@PathVariable String eventId,
                                               @Valid @RequestBody ApiModels.FreezeRequest request) {
        return service.freeze(eventId, request);
    }

    @GetMapping("/events/{eventId}/settlement")
    public ApiModels.SettlementResponse getSettlement(@PathVariable String eventId) {
        return service.getSettlement(eventId);
    }

    @GetMapping("/events/{eventId}/adjustments")
    public ApiModels.AdjustmentResponse getAdjustments(@PathVariable String eventId) {
        return service.getAdjustments(eventId);
    }
}
