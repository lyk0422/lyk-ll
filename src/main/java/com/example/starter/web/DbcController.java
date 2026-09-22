package com.example.starter.web;

import com.example.starter.repo.FrameRepository;
import com.example.starter.service.DbcImportService;
import com.example.starter.service.FrameParseService;
import com.example.starter.web.dto.FrameRequest;
import com.example.starter.web.dto.FrameResult;
import com.example.starter.web.dto.ImportRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DBC 导入与 CAN 帧解析 REST 入口。
 */
@RestController
@RequestMapping("/api/dbc")
public class DbcController {

    private final DbcImportService importService;
    private final FrameParseService frameParseService;
    private final FrameRepository frameRepository;

    public DbcController(DbcImportService importService,
                         FrameParseService frameParseService,
                         FrameRepository frameRepository) {
        this.importService = importService;
        this.frameParseService = frameParseService;
        this.frameRepository = frameRepository;
    }

    /** 原子导入 DBC 版本；同 requestId 同参重放 200(replayed=true)，异参 409；非法定义 422。 */
    @PostMapping("/imports")
    public ResponseEntity<Map<String, Object>> importVersion(@Valid @RequestBody ImportRequest request) {
        DbcImportService.ImportOutcome outcome = importService.importVersion(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", outcome.version());
        body.put("messageCount", outcome.messageCount());
        body.put("replayed", outcome.replayed());
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** 解析单帧。非法长度/未知 ID/多路复用不匹配返回 200 + accepted=false 与可区分 reason。 */
    @PostMapping("/frames")
    public FrameResult parseFrame(@Valid @RequestBody FrameRequest request) {
        return frameParseService.parse(request);
    }

    /** 查询某 version+canId 下信号当前最新状态（便于核验；无状态时 404）。 */
    @GetMapping("/versions/{version}/ids/{canId}/signals/{signalName}")
    public Map<String, Object> getSignalState(@PathVariable String version,
                                              @PathVariable long canId,
                                              @PathVariable String signalName) {
        FrameRepository.SignalStateRow state = frameRepository.findState(version, canId, signalName);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "信号状态不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("canId", canId);
        body.put("signalName", state.signalName());
        body.put("rawValue", state.rawValue());
        body.put("physicalValue", state.physicalValue());
        body.put("enumLabel", state.enumLabel());
        body.put("timestampNs", state.timestampNs());
        body.put("seq", state.seq());
        return body;
    }
}
