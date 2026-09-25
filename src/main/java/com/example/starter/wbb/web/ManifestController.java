package com.example.starter.wbb.web;

import com.example.starter.wbb.dto.CheckinRequest;
import com.example.starter.wbb.dto.CloseRequest;
import com.example.starter.wbb.dto.FuelChangeRequest;
import com.example.starter.wbb.dto.LateEventResponse;
import com.example.starter.wbb.dto.LmcRequest;
import com.example.starter.wbb.dto.LoadConfirmRequest;
import com.example.starter.wbb.dto.ManifestResponse;
import com.example.starter.wbb.dto.PreplanRequest;
import com.example.starter.wbb.dto.ReviewRequest;
import com.example.starter.wbb.service.ManifestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 载重平衡舱单 REST 入口。flightKey = 航班号-航班日期（如 CA1858-2026-09-26）。
 */
@RestController
@RequestMapping("/api")
public class ManifestController {

    private final ManifestService service;

    public ManifestController(ManifestService service) {
        this.service = service;
    }

    /** 预配：创建首版舱单，返回 201。 */
    @PostMapping("/manifests/preplan")
    public ResponseEntity<ManifestResponse> preplan(@Valid @RequestBody PreplanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.preplan(req));
    }

    /** 值机数据上载（全量替换业载）。 */
    @PostMapping("/flights/{flightKey}/checkin")
    public ManifestResponse checkin(@PathVariable String flightKey,
                                    @Valid @RequestBody CheckinRequest req) {
        return service.checkin(flightKey, req);
    }

    /** 装载确认。 */
    @PostMapping("/flights/{flightKey}/load-confirm")
    public ManifestResponse loadConfirm(@PathVariable String flightKey,
                                        @Valid @RequestBody LoadConfirmRequest req) {
        return service.loadConfirm(flightKey, req);
    }

    /** 最后一分钟修正；限额内原地更新，超限重打新版本。 */
    @PostMapping("/flights/{flightKey}/lmc")
    public ManifestResponse lmc(@PathVariable String flightKey, @Valid @RequestBody LmcRequest req) {
        return service.lmc(flightKey, req);
    }

    /** 加油量变更（同事务触发重心重算）。 */
    @PostMapping("/flights/{flightKey}/fuel")
    public ManifestResponse changeFuel(@PathVariable String flightKey,
                                       @Valid @RequestBody FuelChangeRequest req) {
        return service.changeFuel(flightKey, req);
    }

    /** 单岗复核签字（WBO/DSP 两岗不同人先后签）。 */
    @PostMapping("/flights/{flightKey}/reviews")
    public ManifestResponse review(@PathVariable String flightKey,
                                   @Valid @RequestBody ReviewRequest req) {
        return service.review(flightKey, req);
    }

    /** 放行关闭（终态，重复关闭幂等成功）。 */
    @PostMapping("/flights/{flightKey}/close")
    public ManifestResponse close(@PathVariable String flightKey,
                                  @RequestBody(required = false) CloseRequest req) {
        return service.close(flightKey, req == null ? new CloseRequest(null) : req);
    }

    /** 查询当前有效舱单。 */
    @GetMapping("/flights/{flightKey}/manifests/active")
    public ManifestResponse active(@PathVariable String flightKey) {
        return service.getActive(flightKey);
    }

    /** 查询舱单版本链（LMC 重打链可复算）。 */
    @GetMapping("/flights/{flightKey}/manifests")
    public List<ManifestResponse> versions(@PathVariable String flightKey) {
        return service.versions(flightKey);
    }

    /** 按版本 id 查询指定舱单。 */
    @GetMapping("/manifests/{id}")
    public ManifestResponse byId(@PathVariable long id) {
        return service.getById(id);
    }

    /** 查询关闭后的迟到事件与告警。 */
    @GetMapping("/flights/{flightKey}/late-events")
    public List<LateEventResponse> lateEvents(@PathVariable String flightKey) {
        return service.lateEvents(flightKey);
    }
}
