package com.example.starter.calibration.bootstrap;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.service.CalibrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地演示数据初始化（纯合成数据）。
 * 默认写入一组相机内参 v1（全量生效）与 LiDAR 外参 v2（已批准待灰度）；
 * 可通过 calibration.seed.enabled=false 关闭。
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "calibration.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CalibrationService service;

    public DemoDataSeeder(CalibrationService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (service.listVersions("cam_front.intrinsic").stream().findAny().isPresent()) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }

        Map<String, Object> intrinsicV1 = new LinkedHashMap<>();
        intrinsicV1.put("fx", 1050.0);
        intrinsicV1.put("fy", 1048.5);
        intrinsicV1.put("cx", 960.0);
        intrinsicV1.put("cy", 540.0);
        intrinsicV1.put("distortion", List.of(-0.05, 0.01, 0.0, 0.0, 0.0));

        service.create(new CreateParamRequest(
                "cam_front.intrinsic", CalibrationKind.INTRINSIC, SensorType.CAMERA,
                "v1", intrinsicV1, null, null, null, null, null, null, "seeder"));
        service.approve("cam_front.intrinsic", "v1", "seeder");
        service.activate("cam_front.intrinsic", "v1", "seeder");

        // 已批准的灰度候选：仅声明三辆灰度车辆，尚未发布
        Map<String, Object> intrinsicV2 = new LinkedHashMap<>(intrinsicV1);
        intrinsicV2.put("fx", 1055.2);
        intrinsicV2.put("fy", 1053.8);
        service.create(new CreateParamRequest(
                "cam_front.intrinsic", CalibrationKind.INTRINSIC, SensorType.CAMERA,
                "v2", intrinsicV2, null, null,
                List.of("V001", "V002", "V003"), null, null, null, "seeder"));
        service.approve("cam_front.intrinsic", "v2", "seeder");

        log.info("演示数据初始化完成：cam_front.intrinsic v1 已全量生效；v2 已批准待灰度（车辆 V001/V002/V003）");
    }

    /** 供文档/手动调用参考：对 V001 发起灰度。 */
    @SuppressWarnings("unused")
    private CanaryReleaseRequest demoCanary() {
        return new CanaryReleaseRequest(
                "cam_front.intrinsic", "v2", List.of("V001"), "operator-a", "示例灰度");
    }
}
