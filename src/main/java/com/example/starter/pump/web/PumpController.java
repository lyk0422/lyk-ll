package com.example.starter.pump.web;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.PumpCommandResult;
import com.example.starter.pump.web.dto.ReplayResult;
import com.example.starter.pump.web.dto.TideCandidateView;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideConfigView;
import com.example.starter.pump.web.dto.TideConfirmRequest;
import com.example.starter.pump.web.dto.TideConfirmResult;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.TideLevelResult;
import com.example.starter.pump.web.dto.TideLevelView;
import com.example.starter.pump.web.dto.TideTrailView;
import com.example.starter.pump.web.dto.WarningRequest;
import com.example.starter.pump.web.dto.WarningResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 分区潮汐控制配置：新建或按版本修改顶托门槛。 */
    @PostMapping("/tide-configs")
    @ResponseStatus(HttpStatus.OK)
    public TideConfigResult tideConfig(@Valid @RequestBody TideConfigRequest request) {
        return facade.handleTideConfig(request);
    }

    /** 潮位上送：滞回判定进入/退出强排并生成切换候选。 */
    @PostMapping("/tide-levels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideLevelResult tideLevel(@Valid @RequestBody TideLevelRequest request) {
        return facade.handleTideLevel(request);
    }

    /** 强排切换候选确认：全部到位才原子切换，任一不到位零切换。 */
    @PostMapping("/tide-switches/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TideConfirmResult tideConfirm(@Valid @RequestBody TideConfirmRequest request) {
        return facade.handleTideConfirm(request);
    }

    /** 分区潮汐配置查询。 */
    @GetMapping("/tide-configs/{zoneCode}")
    public TideConfigView tideConfigView(@PathVariable String zoneCode) {
        return facade.tideConfig(zoneCode);
    }

    /** 分区潮位曲线与迟到证据查询。 */
    @GetMapping("/tide-levels")
    public TideLevelView tideLevels(@RequestParam String zoneCode) {
        return facade.tideLevels(zoneCode);
    }

    /** 分区全部切换候选查询。 */
    @GetMapping("/tide-switches/candidates")
    public List<TideCandidateView> tideCandidates(@RequestParam String zoneCode) {
        return facade.tideCandidates(zoneCode);
    }

    /** 单个切换候选查询。 */
    @GetMapping("/tide-switches/candidates/{id}")
    public TideCandidateView tideCandidate(@PathVariable Long id) {
        return facade.tideCandidate(id);
    }

    /** 分区工况轨迹查询。 */
    @GetMapping("/tide-switches/trail")
    public TideTrailView tideTrail(@RequestParam String zoneCode) {
        return facade.tideTrail(zoneCode);
    }
}
