package com.example.starter.dr.web;

import com.example.starter.dr.dto.AdjustmentResponse;
import com.example.starter.dr.dto.EnrollRequest;
import com.example.starter.dr.dto.EnrollmentResponse;
import com.example.starter.dr.dto.EventCreateRequest;
import com.example.starter.dr.dto.EventResponse;
import com.example.starter.dr.dto.FreezeRequest;
import com.example.starter.dr.dto.FreezeResponse;
import com.example.starter.dr.dto.PointResponse;
import com.example.starter.dr.dto.PointWriteRequest;
import com.example.starter.dr.service.DrService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地电力需求响应 API。
 * 事件使用 UTC 半开区间 [start,end)，所有写操作具备事务与幂等语义。
 */
@RestController
@RequestMapping("/api/dr/events")
public class DrController {

    private final DrService service;

    public DrController(DrService service) {
        this.service = service;
    }

    /** 创建需求响应事件。 */
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEvent(request));
    }

    /** 查询事件（含区间数与冻结状态）。 */
    @GetMapping("/{eventRef}")
    public EventResponse getEvent(@PathVariable String eventRef) {
        return service.getEvent(eventRef);
    }

    /** 登记参与电表。 */
    @PostMapping("/{eventRef}/enrollments")
    public ResponseEntity<EnrollmentResponse> enroll(@PathVariable String eventRef,
                                                     @Valid @RequestBody EnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.enrollMeter(eventRef, request));
    }

    /** 查询事件下已登记电表。 */
    @GetMapping("/{eventRef}/enrollments")
    public List<EnrollmentResponse> listEnrollments(@PathVariable String eventRef) {
        return service.listEnrollments(eventRef);
    }

    /** 写入或更正单个曲线点（基线/实测）。 */
    @PostMapping("/{eventRef}/meters/{meterId}/points")
    public PointResponse writePoint(@PathVariable String eventRef,
                                    @PathVariable String meterId,
                                    @Valid @RequestBody PointWriteRequest request) {
        return service.writePoint(eventRef, meterId, request);
    }

    /** 在一个事务内校验证据版本并冻结结算。 */
    @PostMapping("/{eventRef}/freeze")
    public FreezeResponse freeze(@PathVariable String eventRef,
                                 @Valid @RequestBody FreezeRequest request) {
        return service.freeze(eventRef, request);
    }

    /** 查询冻结证据（逐区间快照与总交付量）。 */
    @GetMapping("/{eventRef}/freeze")
    public FreezeResponse getFreeze(@PathVariable String eventRef) {
        return service.getFreezeEvidence(eventRef);
    }

    /** 查询冻结后更正形成的调整建议。 */
    @GetMapping("/{eventRef}/adjustments")
    public List<AdjustmentResponse> listAdjustments(@PathVariable String eventRef) {
        return service.listAdjustments(eventRef);
    }
}
