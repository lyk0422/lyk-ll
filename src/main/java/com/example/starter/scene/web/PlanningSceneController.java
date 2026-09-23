package com.example.starter.scene.web;

import com.example.starter.scene.model.SceneSnapshot;
import com.example.starter.scene.model.SceneUpdateRequest;
import com.example.starter.scene.model.UpdateResult;
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
@RequestMapping("/api/planning-scene")
public class PlanningSceneController {

    private final PlanningSceneService service;

    public PlanningSceneController(PlanningSceneService service) {
        this.service = service;
    }

    /**
     * 提交批量修改；成功 201，版本过期或幂等参数冲突 409，非法操作 400。
     */
    @PostMapping("/updates")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateResult submit(@Valid @RequestBody SceneUpdateRequest request) {
        return service.submit(request);
    }

    /**
     * 查询当前场景快照。
     */
    @GetMapping("/current")
    public SceneSnapshot current() {
        return service.currentSnapshot();
    }

    /**
     * 查询任意历史版本的不可变快照。
     */
    @GetMapping("/versions/{version}")
    public SceneSnapshot snapshot(@PathVariable long version) {
        return service.historicalSnapshot(version);
    }
}
