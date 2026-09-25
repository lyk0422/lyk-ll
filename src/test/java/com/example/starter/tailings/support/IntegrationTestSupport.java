package com.example.starter.tailings.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.tailings.service.CommandService;
import com.example.starter.tailings.service.FloodRoutingService;
import com.example.starter.tailings.service.PlanService;
import com.example.starter.tailings.service.PondService;
import com.example.starter.tailings.service.ReviewService;

/**
 * 集成测试基座：H2(MySQL 兼容模式) 独立命名内存库，逐用例清库，提供合成尾矿库与全链路辅助。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class IntegrationTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PondService pondService;

    @Autowired
    protected FloodRoutingService floodRoutingService;

    @Autowired
    protected PlanService planService;

    @Autowired
    protected CommandService commandService;

    @Autowired
    protected ReviewService reviewService;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM rainfall_warning");
        jdbc.update("DELETE FROM danger_report");
        jdbc.update("DELETE FROM review_archive");
        jdbc.update("DELETE FROM pond_executing_plan");
        jdbc.update("DELETE FROM pond_active_plan");
        jdbc.update("DELETE FROM discharge_command");
        jdbc.update("DELETE FROM discharge_plan");
        jdbc.update("DELETE FROM flood_calc");
        jdbc.update("DELETE FROM storage_curve");
        jdbc.update("DELETE FROM tailings_pond");
        clock.setInstant(TestClockConfiguration.T0);
    }

    protected static String rid() {
        return UUID.randomUUID().toString();
    }

    /**
     * 创建合成尾矿库：允许最高调洪水位200m，下游安全泄量100m3/s，
     * 干滩下限50m，浸润线下限2m；库容曲线 180→100万，190→160万，200→240万 m3。
     * 即水位190m时可用调洪库容恰为80万m3。
     */
    protected long createPond() {
        return pondService.create("合成尾矿库", bd("200"), bd("100"), bd("50"), bd("2"),
                List.of(new PondService.CurveInput(bd("180"), bd("1000000")),
                        new PondService.CurveInput(bd("190"), bd("1600000")),
                        new PondService.CurveInput(bd("200"), bd("2400000"))))
                .pondId();
    }

    /**
     * 在默认合成库上做一次演算：水位190m（可用库容80万m3），设计洪水量与安全指标可配。
     */
    protected FloodRoutingService.CalcResult calc(long pondId, String designFloodVolume,
                                                  String dryBeach, String phreatic) {
        return floodRoutingService.calculate(new FloodRoutingService.CalcRequest(
                rid(), pondId, bd("190"), bd(designFloodVolume), bd(dryBeach), bd(phreatic)));
    }

    /**
     * 跑通 演算→方案→下达→签收→执行→回执 全链路，返回指令ID。
     */
    protected long runFullChain(long pondId) {
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        PlanService.PlanResult plan = planService.create(new PlanService.PlanRequest(
                rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("80")))));
        CommandService.CommandResult command = commandService.issue(rid(), plan.planId());
        commandService.ack(command.commandId());
        commandService.execute(command.commandId());
        commandService.receipt(rid(), command.commandId(),
                List.of(new PlanService.OrificeInput("1#孔", bd("80"))), bd("80"), null);
        return command.commandId();
    }

    protected static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
