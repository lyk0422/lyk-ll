package com.example.starter.scene.web;

import com.example.starter.scene.dto.ApplyRequest;
import com.example.starter.scene.dto.ApplyResponse;
import com.example.starter.scene.dto.SnapshotView;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.service.SceneService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planning Scene 版本服务 REST 入口。
 */
@RestController
@RequestMapping("/api/scene")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    /**
     * 原子提交一批场景修改。成功返回 200 及新版本；版本过期或幂等键异参返回 409；非法请求返回 400。
     */
    @PostMapping("/apply")
    public ResponseEntity<ApplyResponse> apply(@RequestBody ApplyRequest request) {
        return ResponseEntity.ok(sceneService.apply(request));
    }

    /**
     * 读取当前版本快照。
     */
    @GetMapping("/current")
    public ResponseEntity<SnapshotView> current() {
        return ResponseEntity.ok(sceneService.currentSnapshot());
    }

    /**
     * 读取指定历史版本的不可变快照；版本不存在返回 404。
     */
    @GetMapping("/versions/{version}")
    public ResponseEntity<SnapshotView> version(@PathVariable long version) {
        if (version < 0L) {
            throw new SceneValidationException("version must be non-negative");
        }
        SnapshotView snapshot = sceneService.snapshotAt(version);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }
}
