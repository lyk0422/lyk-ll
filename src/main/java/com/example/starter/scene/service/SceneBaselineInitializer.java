package com.example.starter.scene.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后为初始版本 0 构建不可变快照；快照已存在时幂等跳过。
 */
@Component
public class SceneBaselineInitializer implements ApplicationRunner {

    private final SceneService sceneService;

    public SceneBaselineInitializer(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    @Override
    public void run(ApplicationArguments args) {
        sceneService.initializeBaselineSnapshot();
    }
}
