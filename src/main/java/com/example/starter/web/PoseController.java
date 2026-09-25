package com.example.starter.web;

import com.example.starter.service.PoseService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 塔机位姿上报接口。重复读数返回 duplicate=true 且不重复触发预警。
 */
@RestController
@RequestMapping("/api/cranes")
public class PoseController {

    private final PoseService poseService;

    public PoseController(PoseService poseService) {
        this.poseService = poseService;
    }

    @PostMapping("/{craneId}/poses")
    public PoseDtos.Response ingest(@PathVariable String craneId, @RequestBody PoseDtos.Request body) {
        PoseService.PoseIngestResult result = poseService.ingest(
                craneId, body.timestamp(), body.hookX(), body.hookY());
        return new PoseDtos.Response(result.duplicate(), result.breachEvents(), result.brakeCommands());
    }
}
