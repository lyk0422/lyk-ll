package com.example.starter.web;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.ValveVersion;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.BalanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 换热站运行监测与调节指令入口。
 */
@RestController
@RequestMapping("/api/stations")
public class StationController {

    private final BalanceService balanceService;
    private final AdjustmentService adjustmentService;
    private final Clock clock;

    public StationController(BalanceService balanceService,
                             AdjustmentService adjustmentService, Clock clock) {
        this.balanceService = balanceService;
        this.adjustmentService = adjustmentService;
        this.clock = clock;
    }

    /**
     * 温度上报请求。
     *
     * @param readings 各单元回水温度，采集时间为 UTC
     */
    public record ReadingsRequest(@NotEmpty List<@Valid Reading> readings) {
        /**
         * 单条温度上报。
         */
        public record Reading(@NotNull Long unitId, @NotNull BigDecimal temperature,
                              @NotNull Instant recordedAt) {
        }
    }

    /**
     * 调节令请求。
     *
     * @param requestId       客户端幂等键
     * @param unitId          目标单元 ID
     * @param stepPercent     步进幅度，单位 %，(0, 10]，越界由服务层返回 422
     * @param expectedVersion 受控阀门当前开度版本；阀门有有效铅封时必填，否则可省略
     */
    public record AdjustmentRequest(@NotBlank String requestId, @NotNull Long unitId,
                                    @NotNull BigDecimal stepPercent, Integer expectedVersion) {
    }

    /**
     * 批量上报回水温度；归档后迟到数据只记迟到事件。
     */
    @PostMapping("/{stationId}/readings")
    public BalanceService.ReadingResult submitReadings(@PathVariable long stationId,
                                                       @Valid @RequestBody ReadingsRequest request) {
        List<BalanceService.ReadingCommand> commands = request.readings().stream()
                .map(r -> new BalanceService.ReadingCommand(r.unitId(), r.temperature(), r.recordedAt()))
                .toList();
        return balanceService.submitReadings(stationId, commands);
    }

    /**
     * 失衡判定：站内温度分布、最高最低差与平衡结论（温差不超过 2℃ 判平衡）。
     */
    @GetMapping("/{stationId}/balance")
    public BalanceService.BalanceView balance(@PathVariable long stationId) {
        return balanceService.evaluateBalance(stationId, clock.instant());
    }

    /**
     * 下达调节令（初调节/再调节）：requestId 幂等，站内并发唯一受理；
     * 阀门存在有效铅封时须携带 expectedVersion 并持生效中的解封单。
     */
    @PostMapping("/{stationId}/adjustments")
    public AdjustmentTask adjust(@PathVariable long stationId,
                                 @Valid @RequestBody AdjustmentRequest request) {
        return adjustmentService.issueAdjustment(stationId, request.requestId(),
                request.unitId(), request.stepPercent(), request.expectedVersion());
    }

    /**
     * 查询单元阀门开度版本链。
     */
    @GetMapping("/units/{unitId}/valve-versions")
    public List<ValveVersion> valveVersions(@PathVariable long unitId) {
        return adjustmentService.getValveVersions(unitId);
    }
}
