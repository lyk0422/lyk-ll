package com.example.starter.planningscene.web;

import com.example.starter.planningscene.dto.SceneUpdateRequest;
import com.example.starter.planningscene.service.SceneService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planning Scene REST 入口：提交批处理、读取当前快照与历史快照。
 */
@RestController
@RequestMapping("/api/planning-scene")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    /** 原子提交一批场景修改。 */
    @PostMapping("/commit")
    public ResponseEntity<?> commit(@Valid @RequestBody SceneUpdateRequest request) {
        return ResponseEntity.ok(sceneService.commit(request));
    }

    /** 读取当前版本快照。 */
    @GetMapping
    public ResponseEntity<?> current() {
        return ResponseEntity.ok(sceneService.currentView());
    }

    /** 读取任意历史版本的不可变快照。 */
    @GetMapping("/versions/{version}")
    public ResponseEntity<?> snapshot(@PathVariable long version) {
        return ResponseEntity.ok(sceneService.snapshot(version));
    }
}
