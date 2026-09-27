package com.example.starter.web;

import java.util.List;

import com.example.starter.service.ObligationService;
import com.example.starter.web.dto.ApiDtos.ObligationAllocateRequest;
import com.example.starter.web.dto.ApiDtos.ObligationCompleteRequest;
import com.example.starter.web.dto.ApiDtos.ObligationEventView;
import com.example.starter.web.dto.ApiDtos.ObligationItemsRequest;
import com.example.starter.web.dto.ApiDtos.ObligationStartRequest;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维保履约续派接口：扫描义务 → 派发/续派 → 开始 → 清单回报 → 双人确认完成；
 * 并提供义务与尝试轨迹查询。写操作携带 expectedVersion 与 requestId。
 */
@RestController
@RequestMapping("/api/obligations")
public class ObligationController {

    private final ObligationService obligationService;

    public ObligationController(ObligationService obligationService) {
        this.obligationService = obligationService;
    }

    /** 扫描：为已进入到期前 24 小时窗口的周期创建义务，重复扫描不重复派发 */
    @PostMapping("/scan")
    public List<ObligationView> scan(@RequestParam long elevatorId,
                                     @RequestParam(required = false) String at) {
        java.time.Instant instant = at == null || at.isBlank() ? null : java.time.Instant.parse(at);
        return obligationService.scan(elevatorId, instant);
    }

    /** 派发/续派一次履约尝试（requestId 幂等） */
    @PostMapping("/{id}/attempts")
    public ResponseEntity<String> allocate(@PathVariable long id, @Valid @RequestBody ObligationAllocateRequest req) {
        return obligationService.allocate(id, req);
    }

    /** 开始尝试：重查两人持证且无进行中救援 */
    @PostMapping("/attempts/{attemptId}/start")
    public ResponseEntity<String> start(@PathVariable long attemptId, @Valid @RequestBody ObligationStartRequest req) {
        return obligationService.start(attemptId, req);
    }

    /** 回报已做检查项（开始/进行中均可） */
    @PostMapping("/attempts/{attemptId}/items")
    public ResponseEntity<String> items(@PathVariable long attemptId, @Valid @RequestBody ObligationItemsRequest req) {
        return obligationService.reportItems(attemptId, req);
    }

    /** 完成尝试：全部清单 + 双人共同确认；旧尝试回报只记迟到 */
    @PostMapping("/attempts/{attemptId}/complete")
    public ResponseEntity<String> complete(@PathVariable long attemptId,
                                           @Valid @RequestBody ObligationCompleteRequest req) {
        return obligationService.complete(attemptId, req);
    }

    @GetMapping("/{id}")
    public ObligationView get(@PathVariable long id) {
        return obligationService.getObligation(id);
    }

    @GetMapping
    public List<ObligationView> list(@RequestParam long elevatorId) {
        return obligationService.listForElevator(elevatorId);
    }

    /** 义务与尝试轨迹 */
    @GetMapping("/{id}/events")
    public List<ObligationEventView> events(@PathVariable long id) {
        return obligationService.events(id);
    }
}
