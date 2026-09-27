package com.example.starter.pump.web;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.CandidateConfirmRequest;
import com.example.starter.pump.web.dto.CandidateConfirmResult;
import com.example.starter.pump.web.dto.GateReceiptRequest;
import com.example.starter.pump.web.dto.GateReceiptResult;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import com.example.starter.pump.web.dto.ReplayResult;
import com.example.starter.pump.web.dto.StrongDrainView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideReadingRequest;
import com.example.starter.pump.web.dto.TideReadingResult;
import com.example.starter.pump.web.dto.WarningRequest;
import com.example.starter.pump.web.dto.WarningResult;
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
 * 城市内涝泵站联动预降服务 HTTP 入口。
 */
@RestController
@RequestMapping("/api")
public class PumpController {

    private final PumpdownFacade facade;

    public PumpController(PumpdownFacade facade) {
        this.facade = facade;
    }

    /** 气象预警上送：橙色及以上启动预降。 */
    @PostMapping("/warnings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WarningResult warning(@Valid @RequestBody WarningRequest request) {
        return facade.handleWarning(request);
    }

    /** 泵站启停：分区唯一序列、启泵间隔与闸门联动在服务层保证。 */
    @PostMapping("/pump-tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PumpCommandResult pumpTask(@Valid @RequestBody PumpCommandRequest request) {
        return facade.handlePumpCommand(request);
    }

    /** 液位复测上送：去重、自动停泵与失联派单。 */
    @PostMapping("/levels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LevelIngestResult level(@Valid @RequestBody LevelIngestRequest request) {
        return facade.handleLevel(request);
    }

    /** 复盘归档：终态，重复归档幂等。 */
    @PostMapping("/archives")
    @ResponseStatus(HttpStatus.OK)
    public ArchiveResult archive(@Valid @RequestBody ArchiveRequest request) {
        return facade.handleArchive(request);
    }

    /** 台账复算：水位曲线、启停序列、失联派单。 */
    @GetMapping("/pumpdowns/{id}/replay")
    public ReplayResult replay(@PathVariable Long id) {
        return facade.replay(id);
    }

    // ============ 潮汐顶托工况切换与强排联锁（合成，新增） ============

    /** 新增/修改分区潮汐控制配置（版本从1开始，成功变更加1）。 */
    @PostMapping("/tide/configs")
    @ResponseStatus(HttpStatus.OK)
    public TideConfigResult tideConfig(@Valid @RequestBody TideConfigRequest request) {
        return facade.handleTideConfig(request);
    }

    /** 潮位上送：按UTC时刻唯一，跨门槛生成强排候选。 */
    @PostMapping("/tide/readings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideReadingResult tideReading(@Valid @RequestBody TideReadingRequest request) {
        return facade.handleTideReading(request);
    }

    /** 闸门合成到位回执：只归所报候选序列。 */
    @PostMapping("/tide/gate-receipts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GateReceiptResult gateReceipt(@Valid @RequestBody GateReceiptRequest request) {
        return facade.handleGateReceipt(request);
    }

    /** 候选确认：事务内联锁原子切换，任一不到位零切换。 */
    @PostMapping("/tide/candidate-confirmations")
    @ResponseStatus(HttpStatus.OK)
    public CandidateConfirmResult confirmCandidate(@Valid @RequestBody CandidateConfirmRequest request) {
        return facade.handleCandidateConfirm(request);
    }

    /** 潮位、候选确认及工况轨迹查询；未配置潮汐控制的分区 configured=false、保持原行为。 */
    @GetMapping("/zones/{zoneCode}/tide")
    public StrongDrainView tideView(@PathVariable String zoneCode) {
        return facade.tideView(zoneCode);
    }
}
