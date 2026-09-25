package com.example.starter.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.starter.api.dto.ToolRegisterRequest;
import com.example.starter.service.OperationService;

/**
 * 本地合成数据初始化：启动时登记一把当日（UTC）校验有效的力矩工具，
 * 使开箱即可走通派工→力矩→见证→归档流程。所有数据均为合成数据且仅存于内存。
 *
 * <p>仅登记工具台账；工序卡与紧固点由调用方通过真实派工接口创建。
 * 可通过 {@code app.synthetic-seed=false} 关闭。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    /** 开箱即用的合成工具编号。 */
    public static final String DEMO_TOOL_CODE = "DEMO-WRENCH-01";

    private final OperationService operationService;
    private final Clock clock;
    private final boolean enabled;

    public SyntheticDataInitializer(OperationService operationService,
                                    Clock clock,
                                    org.springframework.core.env.Environment environment) {
        this.operationService = operationService;
        this.clock = clock;
        this.enabled = environment.getProperty("app.synthetic-seed", Boolean.class, true);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        LocalDate todayUtc = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        operationService.registerTool(new ToolRegisterRequest(DEMO_TOOL_CODE, todayUtc));
    }
}
