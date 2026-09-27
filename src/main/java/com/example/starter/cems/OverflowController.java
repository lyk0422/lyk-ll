package com.example.starter.cems;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.CemsTypes.DischargeStatsView;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentRequest;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentView;
import com.example.starter.cems.CemsTypes.OverflowAnomalyView;
import com.example.starter.cems.CemsTypes.OverflowHoursRequest;
import com.example.starter.cems.CemsTypes.OverflowHoursResult;
import com.example.starter.cems.CemsTypes.OverflowPermitRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.OverflowSettlementRequest;
import com.example.starter.cems.CemsTypes.OverflowSnapshotView;
import com.example.starter.cems.CemsTypes.StatsCaliber;

/**
 * 雨季溢流证据标记与双口径统计 HTTP 入口（业务时刻均为 UTC）。
 * 全部写操作以 requestId 幂等（结构化指纹），许可变更携带 expectedVersion 乐观校验。
 */
@RestController
@RequestMapping("/api/cems/overflow")
public class OverflowController {

    private final OverflowService service;

    public OverflowController(OverflowService service) {
        this.service = service;
    }

    /** 溢流许可版本新增/修改：expectedVersion=0 新增（写入版本 1），否则须等于当前版本。 */
    @PostMapping("/permits")
    public OverflowPermitView upsertPermit(@RequestBody OverflowPermitRequest req) {
        return service.upsertPermit(req);
    }

    /** 当前溢流许可版本。 */
    @GetMapping("/permits/{code}")
    public OverflowPermitView permit(@PathVariable String code) {
        return service.getPermit(code);
    }

    /** 小时溢流数据上报/更正（集合，UTC 整点）；复核冻结前更正雨量会雨量版本加 1 并重算。 */
    @PostMapping("/hours")
    public OverflowHoursResult reportHours(@RequestBody OverflowHoursRequest req) {
        return service.reportHours(req);
    }

    /** 标记复核：冻结该 UTC 日所用许可及雨量版本，重建连续段并冻结日结快照。 */
    @PostMapping("/reviews")
    public OverflowReviewResult review(@RequestBody OverflowReviewRequest req) {
        return service.review(req);
    }

    /** 日结：冻结该 UTC 日双口径快照；与标记复核竞争冻结，先冻者为准、结果一致。 */
    @PostMapping("/settlements")
    public OverflowSnapshotView settle(@RequestBody OverflowSettlementRequest req) {
        return service.settle(req);
    }

    /** 日结快照查询。 */
    @GetMapping("/settlements/{code}/{day}")
    public OverflowSnapshotView snapshot(@PathVariable String code,
                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return service.getSnapshot(code, day);
    }

    /** 独立调差：仅允许在日结快照冻结后调用，不改快照。 */
    @PostMapping("/adjustments")
    public OverflowAdjustmentView adjust(@RequestBody OverflowAdjustmentRequest req) {
        return service.adjust(req);
    }

    /** 双口径统计：caliber=NORMAL 仅排除证据齐全的合规溢流小时；caliber=ALL 不排除。 */
    @GetMapping("/stats/{code}")
    public DischargeStatsView stats(@PathVariable String code,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                    @RequestParam String caliber) {
        StatsCaliber parsed;
        try {
            parsed = StatsCaliber.valueOf(caliber.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("caliber 非法（仅支持 NORMAL/ALL）: " + caliber);
        }
        return service.stats(code, from, to, parsed);
    }

    /** 专项异常列表：连续合规溢流段超过许可最大连续小时数。 */
    @GetMapping("/anomalies/{code}")
    public List<OverflowAnomalyView> anomalies(@PathVariable String code) {
        return service.listAnomalies(code);
    }
}
