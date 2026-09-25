package com.example.starter.schedule.web;

import com.example.starter.schedule.repository.MasterDataRepository;
import com.example.starter.schedule.service.ReportService;
import com.example.starter.schedule.service.ScheduleService;
import com.example.starter.schedule.web.dto.CancelRequestDto;
import com.example.starter.schedule.web.dto.EntryViewDto;
import com.example.starter.schedule.web.dto.PublishRequestDto;
import com.example.starter.schedule.web.dto.PublishResponseDto;
import com.example.starter.schedule.web.dto.ReportRequestDto;
import com.example.starter.schedule.web.dto.ReportResponseDto;
import com.example.starter.schedule.web.dto.ScheduleRequestDto;
import com.example.starter.schedule.web.dto.ScheduleResponseDto;
import com.example.starter.schedule.web.dto.VersionViewDto;
import com.example.starter.schedule.web.dto.WindowViewDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 测控过境窗口编排 REST 入口。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ReportService reportService;
    private final MasterDataRepository masterData;

    public ScheduleController(ScheduleService scheduleService, ReportService reportService,
                              MasterDataRepository masterData) {
        this.scheduleService = scheduleService;
        this.reportService = reportService;
        this.masterData = masterData;
    }

    /** 查询某站某日合成过境预报窗口（供申请前获取 windowId）。 */
    @GetMapping("/stations/{stationCode}/days/{dayKey}/windows")
    public List<WindowViewDto> windows(@PathVariable String stationCode,
                                       @PathVariable String dayKey) {
        var station = masterData.findStationByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("地面站不存在: " + stationCode));
        return masterData.findStationDayWindows(station.id(), dayKey).stream()
                .map(w -> new WindowViewDto(
                        w.id(),
                        masterData.findSatelliteById(w.satelliteId())
                                .orElseThrow(() -> ApiException.notFound("卫星不存在")).code(),
                        stationCode, w.start(), w.end(), w.dayKey()))
                .toList();
    }

    /** 提交编排申请（requestId 幂等；高优先级抢占；同级先到保留）。 */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.OK)
    public ScheduleResponseDto submit(@Valid @RequestBody ScheduleRequestDto dto) {
        return scheduleService.submit(dto);
    }

    /** 撤销已编排窗口（发布后拒绝）。 */
    @PostMapping("/cancel")
    public EntryViewDto cancel(@Valid @RequestBody CancelRequestDto dto) {
        return scheduleService.cancel(dto.requestId());
    }

    /** 按日发布编排表；重复发布返回幂等成功。 */
    @PostMapping("/publish")
    public PublishResponseDto publish(@Valid @RequestBody PublishRequestDto dto) {
        return scheduleService.publish(dto.stationCode(), dto.dayKey());
    }

    /** 查询同站某日全部版本（旧版本可查）。 */
    @GetMapping("/stations/{stationCode}/days/{dayKey}/versions")
    public List<VersionViewDto> versions(@PathVariable String stationCode,
                                         @PathVariable String dayKey) {
        return scheduleService.versions(stationCode, dayKey);
    }

    /** 提交执行回报（按“窗口＋站”去重；漏报后迟到回报只记事件）。 */
    @PostMapping("/stations/{stationCode}/reports")
    public ReportResponseDto report(@PathVariable String stationCode,
                                    @Valid @RequestBody ReportRequestDto dto) {
        return reportService.submit(stationCode, dto);
    }
}
