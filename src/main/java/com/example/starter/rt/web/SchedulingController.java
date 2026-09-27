package com.example.starter.rt.web;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.OccupancyItemView;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.service.SchedulingService;

import jakarta.validation.Valid;

/**
 * 设备与连续分次批量排程 REST 入口（合成规则）。
 * 排程发布/取消/受控治疗与设备能力调整均携带 requestId 幂等键；
 * 修改类操作携带 expectedVersion 乐观并发（版本从1开始，成功变更加1）。
 */
@RestController
@RequestMapping("/api/radiotherapy")
public class SchedulingController {

    private final SchedulingService schedulingService;

    public SchedulingController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    /** 设备登记（合成，可执行复杂度等级1至3），版本从1开始。 */
    @PostMapping("/devices")
    public ResponseEntity<Object> registerDevice(@Valid @RequestBody DeviceRegisterRequest request) {
        return toEntity(schedulingService.registerDevice(request));
    }

    /** 设备能力调整（携带 expectedVersion）；下调只阻断尚未开始项。 */
    @PostMapping("/devices/{deviceId}/capability")
    public ResponseEntity<Object> updateCapability(@PathVariable String deviceId,
                                                   @Valid @RequestBody DeviceCapabilityRequest request) {
        return toEntity(schedulingService.updateCapability(deviceId, request));
    }

    /** 设备占用查询：UTC 范围（可选，缺省全时段）内全部排程项。 */
    @GetMapping("/devices/{deviceId}/occupancy")
    public List<OccupancyItemView> occupancy(@PathVariable String deviceId,
                                             @RequestParam(required = false) OffsetDateTime fromUtc,
                                             @RequestParam(required = false) OffsetDateTime toUtc) {
        return schedulingService.occupancy(deviceId, fromUtc, toUtc);
    }

    /** 连续分次批量排程发布：单批最多10个连续分次，任一冲突整批零占用。 */
    @PostMapping("/schedules")
    public ResponseEntity<Object> publish(@Valid @RequestBody SchedulePublishRequest request) {
        return toEntity(schedulingService.publish(request));
    }

    /** 排程批次取消（携带 expectedVersion）：释放全部未开始项占用。 */
    @PostMapping("/schedules/{scheduleId}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable long scheduleId,
                                         @Valid @RequestBody ScheduleCancelRequest request) {
        return toEntity(schedulingService.cancel(scheduleId, request.expectedVersion(),
                request.requestId()));
    }

    /** 排程受控治疗记录：须在窗口内且匹配排程/计划版本，完成原子释放占用并冻结实绩。 */
    @PostMapping("/treatments")
    public ResponseEntity<Object> treat(@Valid @RequestBody TreatmentRequest request) {
        return toEntity(schedulingService.treat(request));
    }

    private ResponseEntity<Object> toEntity(RecordedResponse result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
