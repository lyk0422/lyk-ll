package com.example.starter.ros.support;

import com.example.starter.ros.ParamType;
import com.example.starter.ros.ParameterSchema;
import com.example.starter.ros.db.SchemaRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地启动时注册示例节点的参数 Schema（模拟 ROS 2 节点启动时声明自身参数）。
 * 仅写入 Schema 元数据，不产生业务数据；重复启动幂等。
 */
@Component
@Order(10)
public class DemoSchemaInitializer implements ApplicationRunner {

    public static final String CAMERA_NODE = "camera_node";
    public static final String PLANNER_NODE = "planner_node";

    private final SchemaRepository schemaRepository;

    public DemoSchemaInitializer(SchemaRepository schemaRepository) {
        this.schemaRepository = schemaRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ParameterSchema> camera = List.of(
                new ParameterSchema(CAMERA_NODE, "camera", "enabled", ParamType.BOOL,
                        "true", true, true, null),
                new ParameterSchema(CAMERA_NODE, "camera", "frame_rate", ParamType.INT,
                        "30", true, true, "frame_rate > 0 && frame_rate <= 120"),
                new ParameterSchema(CAMERA_NODE, "camera", "resolution_width", ParamType.INT,
                        "640", true, true, "resolution_width > 0"),
                new ParameterSchema(CAMERA_NODE, "camera", "resolution_height", ParamType.INT,
                        "480", true, true,
                        "resolution_height > 0 && resolution_height <= resolution_width"),
                new ParameterSchema(CAMERA_NODE, "camera", "device_serial", ParamType.STRING,
                        "SIM-CAM-0001", true, false, null));
        List<ParameterSchema> planner = List.of(
                new ParameterSchema(PLANNER_NODE, "planner", "max_speed", ParamType.DOUBLE,
                        "1.5", true, true, "max_speed > 0 && max_speed <= safety_limit"),
                new ParameterSchema(PLANNER_NODE, "planner", "safety_limit", ParamType.DOUBLE,
                        "2.0", true, true, "safety_limit >= max_speed"),
                new ParameterSchema(PLANNER_NODE, "planner", "map_frame", ParamType.STRING,
                        "map", true, false, null));
        for (ParameterSchema schema : camera) {
            schemaRepository.upsert(schema);
        }
        for (ParameterSchema schema : planner) {
            schemaRepository.upsert(schema);
        }
    }
}
