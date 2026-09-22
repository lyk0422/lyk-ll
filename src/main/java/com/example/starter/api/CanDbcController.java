package com.example.starter.api;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.api.dto.DbcImportResponse;
import com.example.starter.api.dto.FrameParseRequest;
import com.example.starter.api.dto.FrameParseResponse;
import com.example.starter.api.dto.SignalStateDto;
import com.example.starter.service.DbcImportService;
import com.example.starter.service.FrameParseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAN DBC 增量解析服务入口。
 */
@RestController
@RequestMapping("/api/can")
public class CanDbcController {

    private final DbcImportService dbcImportService;
    private final FrameParseService frameParseService;

    public CanDbcController(DbcImportService dbcImportService, FrameParseService frameParseService) {
        this.dbcImportService = dbcImportService;
        this.frameParseService = frameParseService;
    }

    /** 原子导入一个 DBC 版本；同 requestId 同参重放幂等，异参返回 409。 */
    @PostMapping("/dbc/versions")
    public DbcImportResponse importVersion(@Valid @RequestBody DbcImportRequest request) {
        return dbcImportService.importVersion(request);
    }

    /** 按指定 DBC 版本解析一帧 CAN 报文。 */
    @PostMapping("/frames")
    public FrameParseResponse parseFrame(@Valid @RequestBody FrameParseRequest request) {
        return frameParseService.parse(request);
    }

    /** 查询某版本 + CAN ID 下全部信号的最新状态。 */
    @GetMapping("/versions/{version}/ids/{canId}/states")
    public List<SignalStateDto> listStates(@PathVariable String version, @PathVariable long canId) {
        return frameParseService.listStates(version, canId);
    }
}
