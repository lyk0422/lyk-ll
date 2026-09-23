package com.example.starter.ros.bootstrap;

import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.model.ParamType;
import com.example.starter.ros.repo.ParameterJdbcRepository;
import com.example.starter.ros.repo.SchemaJdbcRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成演示数据：注册两个示例节点（camera_node、planner_node）的参数 Schema，
 * 初始化单调版本 0，并写入一条合成的已提交必填值。仅在 Schema 表为空时执行，
 * 不依赖外部数据库、Docker 或 ROS。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    public static final String CAMERA_NODE = "camera_node";
    public static final String PLANNER_NODE = "planner_node";

    private final SchemaJdbcRepository schemaRepository;
    private final ParameterJdbcRepository parameterRepository;

    public DemoDataInitializer(SchemaJdbcRepository schemaRepository,
                               ParameterJdbcRepository parameterRepository) {
        this.schemaRepository = schemaRepository;
        this.parameterRepository = parameterRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!schemaRepository.findAll().isEmpty()) {
            return;
        }

        // camera_node / vision 参数组
        schemaRepository.insert(new ParamSchema(
                CAMERA_NODE, "vision", "enabled", ParamType.BOOLEAN, "true",
                false, true, null));
        schemaRepository.insert(new ParamSchema(
                CAMERA_NODE, "vision", "fps", ParamType.INTEGER, "15",
                false, true,
                "['vision.fps'] >= 1 and ['vision.fps'] <= ['vision.fps_max']"));
        schemaRepository.insert(new ParamSchema(
                CAMERA_NODE, "vision", "fps_max", ParamType.INTEGER, "60",
                false, false, null));
        schemaRepository.insert(new ParamSchema(
                CAMERA_NODE, "vision", "calibration", ParamType.STRING, null,
                true, true, null));

        // planner_node / ctrl 参数组
        schemaRepository.insert(new ParamSchema(
                PLANNER_NODE, "ctrl", "speed", ParamType.DOUBLE, "1.0",
                false, true,
                "['ctrl.speed'] >= 0 and ['ctrl.speed'] <= ['ctrl.max_speed']"));
        schemaRepository.insert(new ParamSchema(
                PLANNER_NODE, "ctrl", "max_speed", ParamType.DOUBLE, "5.0",
                false, false, null));
        schemaRepository.insert(new ParamSchema(
                PLANNER_NODE, "ctrl", "use_sim_time", ParamType.BOOLEAN, "false",
                false, false, null));

        // 初始版本 0
        parameterRepository.initVersionIfAbsent(CAMERA_NODE);
        parameterRepository.initVersionIfAbsent(PLANNER_NODE);

        // 必填参数合成初始值（版本 0 预置）
        parameterRepository.upsertValuesWithVersion(CAMERA_NODE, "vision",
                java.util.Map.of("calibration", "factory"), 0);
    }
}
