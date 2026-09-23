package com.example.starter.scene.web;

import com.example.starter.scene.model.SceneApi;
import com.example.starter.scene.service.PlanningSceneService;
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
 * Planning Scene REST 接口。
 */
@RestController
@RequestMapping("/api/scene")
public class PlanningSceneController {

    private final PlanningSceneService service;

    public PlanningSceneController(PlanningSceneService service) {
        this.service = service;
    }

    @PostMapping("/robot-model")
    @ResponseStatus(HttpStatus.CREATED)
    public SceneApi.CommitResult initRobotModel(@Valid @RequestBody SceneApi.RobotModelRequest request) {
        long version = service.initializeRobotModel(request);
        return new SceneApi.CommitResult(null, version, true, false);
    }

    @GetMapping("/current")
    public SceneApi.SnapshotResponse current() {
        return service.currentSnapshot();
    }

    @PostMapping("/changes")
    public SceneApi.CommitResult apply(@Valid @RequestBody SceneApi.ChangeRequest request) {
        return service.apply(request);
    }

    @GetMapping("/snapshots/{version}")
    public SceneApi.SnapshotResponse snapshot(@PathVariable long version) {
        return service.snapshot(version);
    }
}
