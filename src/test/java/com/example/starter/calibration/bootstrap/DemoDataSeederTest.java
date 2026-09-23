package com.example.starter.calibration.bootstrap;

import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.domain.ParamStatus;
import com.example.starter.calibration.repo.CalibrationRepository;
import com.example.starter.calibration.service.CalibrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 本地启动入口冒烟：启用合成数据种子后，应用上下文启动即自动初始化，
 * v1 全量生效、v2 已批准待灰度，且可正常解析。
 */
@SpringBootTest(properties = {
        "calibration.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:calibration_seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class DemoDataSeederTest {

    @Autowired
    private CalibrationService service;
    @Autowired
    private CalibrationRepository repository;

    @Test
    void seedsBaselineAndCanaryCandidateOnStartup() {
        assertEquals(ParamStatus.ACTIVE,
                repository.findParamByKeyAndVersion("cam_front.intrinsic", "v1").getStatus());
        assertEquals(ParamStatus.APPROVED,
                repository.findParamByKeyAndVersion("cam_front.intrinsic", "v2").getStatus());

        // v1 全量服务所有车辆
        var resolved = service.resolve(
                new ResolveRequest("cam_front.intrinsic", "V999", null, null, null));
        assertEquals("v1", resolved.version());
        assertNotNull(resolved.resolutionId());
    }
}
