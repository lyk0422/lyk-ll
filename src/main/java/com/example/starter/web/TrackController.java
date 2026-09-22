package com.example.starter.web;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.service.TrackLifecycleService;
import com.example.starter.web.dto.AssociateRequest;
import com.example.starter.web.dto.AssociateResponse;
import com.example.starter.web.dto.IdSwitchRequest;
import com.example.starter.web.dto.TransitionRequest;
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
 * 感知轨迹关联与状态机 HTTP 入口。
 */
@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackLifecycleService service;

    public TrackController(TrackLifecycleService service) {
        this.service = service;
    }

    /** 提交检测：自动门限关联/新建，或携带 targetTrackUid+expectedVersion 显式原子关联。 */
    @PostMapping("/associations")
    @ResponseStatus(HttpStatus.OK)
    public AssociateResponse associate(@Valid @RequestBody AssociateRequest request) {
        return service.associate(request.toDetection(), request.targetTrackUid(),
                request.expectedVersion(), request.gateThreshold());
    }

    /** 显式合法状态迁移（OCCLUDED/LOST 等），按版本原子更新。 */
    @PostMapping("/{trackUid}/transitions")
    @ResponseStatus(HttpStatus.OK)
    public TrackSnapshot transition(@PathVariable String trackUid,
                                    @Valid @RequestBody TransitionRequest request) {
        return service.transition(trackUid, request.expectedVersion(),
                request.targetStatus(), request.eventTime(), request.reason());
    }

    /** 目标 ID 切换：旧身份轨迹回收，检测由新身份承接，双向保留证据。 */
    @PostMapping("/{trackUid}/id-switches")
    @ResponseStatus(HttpStatus.OK)
    public AssociateResponse idSwitch(@PathVariable String trackUid,
                                      @Valid @RequestBody IdSwitchRequest request) {
        return service.idSwitch(trackUid, request.expectedVersion(), request.toDetection());
    }

    /** 查询单条轨迹当前快照。 */
    @GetMapping("/{trackUid}")
    public TrackSnapshot get(@PathVariable String trackUid) {
        return service.getTrack(trackUid);
    }

    /** 查询全部轨迹当前快照。 */
    @GetMapping
    public List<TrackSnapshot> list() {
        return service.listTracks();
    }

    /** 查询某条轨迹的关联证据（交叉/ID 切换/重识别）。 */
    @GetMapping("/{trackUid}/evidence")
    public List<AssociationEvidence> evidence(@PathVariable String trackUid) {
        return service.evidence(trackUid);
    }

    /** 从事件流重放至指定时刻（含该时刻，epoch 毫秒），返回全部轨迹快照。 */
    @GetMapping("/snapshots")
    public List<TrackSnapshot> snapshot(@RequestParam("asOf") Long asOf) {
        return service.replayAsOf(asOf);
    }
}
