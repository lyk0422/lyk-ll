package com.example.starter.cems.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.service.CemsService;
import com.example.starter.cems.web.dto.ArchiveRequest;
import com.example.starter.cems.web.dto.ConfirmRequest;
import com.example.starter.cems.web.dto.ConfirmResult;
import com.example.starter.cems.web.dto.MutationResponse;
import com.example.starter.cems.web.dto.NoticeView;
import com.example.starter.cems.web.dto.OrderView;
import com.example.starter.cems.web.dto.OutfallView;
import com.example.starter.cems.web.dto.ReadingRequest;
import com.example.starter.cems.web.dto.ReadingResult;
import com.example.starter.cems.web.dto.RegisterRequest;
import com.example.starter.cems.web.dto.ReleaseRequest;
import com.example.starter.cems.web.dto.ReleaseResult;

import jakarta.validation.Valid;

/**
 * 污水排放在线监测与限排联动 API。
 */
@RestController
@RequestMapping("/api/cems")
public class CemsController {

    private final CemsService service;

    public CemsController(CemsService service) {
        this.service = service;
    }

    /** 许可登记。 */
    @PostMapping("/outfalls")
    @ResponseStatus(HttpStatus.CREATED)
    public MutationResponse<OutfallView> register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }

    /** 在线监测读数上报。 */
    @PostMapping("/readings")
    public MutationResponse<ReadingResult> reading(@Valid @RequestBody ReadingRequest request) {
        return service.reading(request);
    }

    /** 限排令确认执行。 */
    @PostMapping("/orders/confirm")
    public MutationResponse<ConfirmResult> confirm(@Valid @RequestBody ConfirmRequest request) {
        return service.confirm(request);
    }

    /** 复测合格解除。 */
    @PostMapping("/orders/release")
    public MutationResponse<ReleaseResult> release(@Valid @RequestBody ReleaseRequest request) {
        return service.release(request);
    }

    /** 归档（终态）。 */
    @PostMapping("/archive")
    public MutationResponse<OutfallView> archive(@Valid @RequestBody ArchiveRequest request) {
        return service.archive(request);
    }

    /** 超时升级扫描（恰满 2 小时未确认的限排令升级并联动限产）。 */
    @PostMapping("/orders/escalation-scan")
    public List<OrderView> escalationScan() {
        return service.scanEscalations();
    }

    /** 查询排口。 */
    @GetMapping("/outfalls/{code}")
    public OutfallView getOutfall(@PathVariable String code) {
        return service.getOutfall(code);
    }

    /** 查询执行中限排令。 */
    @GetMapping("/outfalls/{code}/active-order")
    public OrderView getActiveOrder(@PathVariable String code) {
        return service.getActiveOrder(code);
    }

    /** 查询缺失督办链。 */
    @GetMapping("/outfalls/{code}/notices")
    public List<NoticeView> getNotices(@PathVariable String code) {
        return service.getNotices(code);
    }

    /** 依真实读数复算缺失督办（删除重建）。 */
    @PostMapping("/outfalls/{code}/recompute-missing")
    public List<NoticeView> recomputeMissing(@PathVariable String code) {
        return service.recomputeMissing(code);
    }

    /** 查询超标标记链。 */
    @GetMapping("/outfalls/{code}/marks")
    public List<com.example.starter.cems.web.dto.MarkView> getMarks(@PathVariable String code) {
        return service.getMarks(code);
    }

    /** 查询限排记录链。 */
    @GetMapping("/outfalls/{code}/orders")
    public List<OrderView> getOrders(@PathVariable String code) {
        return service.getOrders(code);
    }

    /** 查询排口事件流。 */
    @GetMapping("/outfalls/{code}/events")
    public List<com.example.starter.cems.repo.EventRepository.OutfallEventRow> getEvents(
            @PathVariable String code) {
        return service.getEvents(code);
    }
}
