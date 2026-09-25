package com.example.starter.crane.web;

import com.example.starter.crane.api.AuthorizationRequest;
import com.example.starter.crane.api.AuthorizationView;
import com.example.starter.crane.api.BrakeView;
import com.example.starter.crane.api.CorrectionRequest;
import com.example.starter.crane.api.PoseAcceptance;
import com.example.starter.crane.api.PoseRequest;
import com.example.starter.crane.api.ReleaseRequest;
import com.example.starter.crane.api.WarningView;
import com.example.starter.crane.service.AuthorizationService;
import com.example.starter.crane.service.EventService;
import com.example.starter.crane.service.PoseProcessingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群塔防碰撞 HTTP 入口。
 */
@RestController
@RequestMapping("/api/crane")
public class CraneController {

    private final AuthorizationService authorizationService;
    private final PoseProcessingService poseProcessingService;
    private final EventService eventService;

    public CraneController(AuthorizationService authorizationService,
            PoseProcessingService poseProcessingService,
            EventService eventService) {
        this.authorizationService = authorizationService;
        this.poseProcessingService = poseProcessingService;
        this.eventService = eventService;
    }

    // ---- 授权 ----

    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizationView apply(@Valid @RequestBody AuthorizationRequest request) {
        return authorizationService.apply(request);
    }

    @GetMapping("/authorizations/{requestId}")
    public AuthorizationView get(@PathVariable String requestId) {
        return authorizationService.get(requestId);
    }

    @PostMapping("/authorizations/{requestId}/release")
    public AuthorizationView release(@PathVariable String requestId,
            @Valid @RequestBody ReleaseRequest request) {
        return authorizationService.release(requestId, request);
    }

    // ---- 位姿 ----

    @PostMapping("/poses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PoseAcceptance ingest(@Valid @RequestBody PoseRequest request) {
        return poseProcessingService.ingest(request);
    }

    // ---- 事件 ----

    @GetMapping("/warnings")
    public List<WarningView> warnings(@RequestParam(required = false) String craneCode) {
        return eventService.listWarnings(craneCode);
    }

    @GetMapping("/brakes")
    public List<BrakeView> brakes(@RequestParam(required = false) String craneCode) {
        return eventService.listBrakes(craneCode);
    }

    /** 取回某越限事件的制动指令；重复触发时返回首次指令，未制动返回 404。 */
    @GetMapping("/warnings/{warningId}/brake")
    public BrakeView brakeOfWarning(@PathVariable Long warningId) {
        BrakeView brake = eventService.findBrakeByWarning(warningId);
        if (brake == null) {
            throw new com.example.starter.crane.NotFoundException(
                    "预警 " + warningId + " 尚未下发制动指令");
        }
        return brake;
    }

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public void correction(@Valid @RequestBody CorrectionRequest request) {
        eventService.appendCorrection(request);
    }
}
