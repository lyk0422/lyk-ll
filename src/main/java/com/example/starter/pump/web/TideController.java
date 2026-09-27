package com.example.starter.pump.web;

import com.example.starter.pump.service.TideFacade;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.CandidateDetailResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.GateReceiptResult;
import com.example.starter.pump.web.dto.ModeTrajectoryResult;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
import com.example.starter.pump.web.dto.TideControlResult;
import com.example.starter.pump.web.dto.TideControlUpdateRequest;
import com.example.starter.pump.web.dto.TideIngestRequest;
import com.example.starter.pump.web.dto.TideIngestResult;
import com.example.starter.pump.web.dto.TideReadingListResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 潮汐顶托工况切换与强排联锁 HTTP 入口。未配置潮汐控制的分区保持原行为。
 */
@RestController
@RequestMapping("/api")
public class TideController {

    private final TideFacade facade;

    public TideController(TideFacade facade) {
        this.facade = facade;
    }

    /** 创建分区潮汐控制配置：版本从1开始。 */
    @PostMapping("/tide-controls")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideControlResult createControl(@Valid @RequestBody TideControlCreateRequest request) {
        return facade.createControl(request);
    }

    /** 更新顶托门槛：携带期望版本，成功变更加1。 */
    @PutMapping("/tide-controls/{zoneCode}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideControlResult updateThreshold(@PathVariable String zoneCode,
                                             @Valid @RequestBody TideControlUpdateRequest request) {
        return facade.updateThreshold(zoneCode, request);
    }

    /** 分区潮位上送：UTC时刻唯一，越限生成切换候选。 */
    @PostMapping("/tide-readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideIngestResult ingestTide(@Valid @RequestBody TideIngestRequest request) {
        return facade.ingestTide(request);
    }

    /** 闸门合成到位回执上报。 */
    @PostMapping("/tide-candidates/{candidateId}/gate-receipts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GateReceiptResult submitReceipt(@PathVariable Long candidateId,
                                           @Valid @RequestBody GateReceiptRequest request) {
        return facade.submitReceipt(candidateId, request);
    }

    /** 确认切换候选：版本、占用、回执校验全部通过才原子切换。 */
    @PostMapping("/tide-candidates/{candidateId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CandidateConfirmResult confirm(@PathVariable Long candidateId,
                                          @Valid @RequestBody CandidateConfirmRequest request) {
        return facade.confirm(candidateId, request);
    }

    /** 分区潮位查询。 */
    @GetMapping("/zones/{zoneCode}/tide-readings")
    public TideReadingListResult tideReadings(@PathVariable String zoneCode) {
        return facade.listReadings(zoneCode);
    }

    /** 候选确认查询：状态、固定目标与已收回执。 */
    @GetMapping("/tide-candidates/{candidateId}")
    public CandidateDetailResult candidateDetail(@PathVariable Long candidateId) {
        return facade.candidateDetail(candidateId);
    }

    /** 分区工况轨迹查询。 */
    @GetMapping("/zones/{zoneCode}/mode-trajectory")
    public ModeTrajectoryResult modeTrajectory(@PathVariable String zoneCode) {
        return facade.trajectory(zoneCode);
    }
}
