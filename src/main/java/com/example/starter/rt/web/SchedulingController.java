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
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.service.SchedulingService;

import jakarta.validation.Valid;

/**
 * 分次设备时长配额与排程门禁 REST 入口：设备登记/能力下调、排程发布/取消与占用查询。
 */
@RestController
@RequestMapping("/api/radiotherapy")
public class SchedulingController {

    private final SchedulingService schedulingService;

    public SchedulingController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    // ---------------- 设备 ----------------

    /** 登记合成设备（初始能力等级1-3，版本从1开始）。 */
    @PostMapping("/devices")
    public ResponseEntity<DeviceResponse> registerDevice(@Valid @RequestBody DeviceRegisterRequest request) {
        return ResponseEntity.status(201).body(schedulingService.registerDevice(request));
    }

    /** 设备清单。 */
    @GetMapping("/devices")
    public List<DeviceResponse> devices() {
        return schedulingService.devices();
    }

    /** 设备能力下调（只能下调；expectedVersion 门禁；成功版本加1）。 */
    @PostMapping("/devices/capability")
    public ResponseEntity<Object> downgradeCapability(@Valid @RequestBody DeviceCapabilityRequest request) {
        return toEntity(schedulingService.downgradeCapability(request));
    }

    /** 设备占用查询：可按 UTC 半开区间 [fromUtc,toUtc) 过滤；缺省返回该设备全部 BOOKED。 */
    @GetMapping("/devices/{deviceId}/occupancies")
    public List<ScheduleResponse> deviceOccupancies(
            @PathVariable String deviceId,
            @RequestParam(value = "fromUtc", required = false) OffsetDateTime fromUtc,
            @RequestParam(value = "toUtc", required = false) OffsetDateTime toUtc) {
        return schedulingService.deviceOccupancies(deviceId, fromUtc, toUtc);
    }

    // ---------------- 排程发布 / 取消 ----------------

    /** 批量发布分次设备时长排程（最多10个连续分次，整批原子占用）。 */
    @PostMapping("/schedules")
    public ResponseEntity<Object> publish(@Valid @RequestBody SchedulePublishRequest request) {
        return toEntity(schedulingService.publish(request));
    }

    /** 取消已发布未完成(BOOKED)排程，原子释放设备时长与分次占用。 */
    @PostMapping("/schedules/cancel")
    public ResponseEntity<Object> cancel(@Valid @RequestBody ScheduleCancelRequest request) {
        return toEntity(schedulingService.cancel(request));
    }

    // ---------------- 排程查询 ----------------

    /** 患者+计划的排程与占用视图，可按 status=BOOKED/EXECUTED/CANCELLED 过滤。 */
    @GetMapping("/patients/{patientId}/plans/{planCode}/schedules")
    public List<ScheduleResponse> planSchedules(@PathVariable String patientId,
                                                @PathVariable String planCode,
                                                @RequestParam(value = "status", required = false)
                                                String status) {
        return schedulingService.planSchedules(patientId, planCode, status);
    }

    private ResponseEntity<Object> toEntity(RecordedResponse result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
