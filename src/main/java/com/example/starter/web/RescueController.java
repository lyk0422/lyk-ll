package com.example.starter.web;

import java.util.List;

import com.example.starter.service.RescueService;
import com.example.starter.web.dto.ApiDtos.ArriveRequest;
import com.example.starter.web.dto.ApiDtos.CloseRequest;
import com.example.starter.web.dto.ApiDtos.DispatchRequest;
import com.example.starter.web.dto.ApiDtos.EventView;
import com.example.starter.web.dto.ApiDtos.RecheckRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import com.example.starter.web.dto.ApiDtos.RescueRequest;
import com.example.starter.web.dto.ApiDtos.RescueView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 救援主流程接口：接报→派单→到场→施救→复检恢复→回访结案。
 */
@RestController
@RequestMapping("/api/rescues")
public class RescueController {

    private final RescueService rescueService;

    public RescueController(RescueService rescueService) {
        this.rescueService = rescueService;
    }

    /** 接报（requestId 幂等） */
    @PostMapping("/report")
    public ResponseEntity<String> report(@Valid @RequestBody ReportRequest req) {
        return rescueService.report(req);
    }

    /** 派单（requestId 幂等） */
    @PostMapping("/{id}/dispatch")
    public ResponseEntity<String> dispatch(@PathVariable long id, @Valid @RequestBody DispatchRequest req) {
        return rescueService.dispatch(id, req);
    }

    /** 到场登记；接报后满 30 分钟未到场判超时并升级通报 */
    @PostMapping("/{id}/arrive")
    public RescueView arrive(@PathVariable long id, @RequestBody ArriveRequest req) {
        return rescueService.arrive(id, req);
    }

    /** 施救登记：须恰两名持证人员 */
    @PostMapping("/{id}/rescue")
    public RescueView rescue(@PathVariable long id, @Valid @RequestBody RescueRequest req) {
        return rescueService.rescue(id, req);
    }

    /** 复检：合格后与恢复运行同一事务完成 */
    @PostMapping("/{id}/recheck")
    public RescueView recheck(@PathVariable long id, @RequestBody RecheckRequest req) {
        return rescueService.recheck(id, req);
    }

    /** 回访结案（requestId 幂等，终态） */
    @PostMapping("/{id}/close")
    public ResponseEntity<String> close(@PathVariable long id, @Valid @RequestBody CloseRequest req) {
        return rescueService.close(id, req);
    }

    @GetMapping("/{id}")
    public RescueView get(@PathVariable long id) {
        return rescueService.getRescue(id);
    }

    /** 救援时间线（可复算） */
    @GetMapping("/{id}/timeline")
    public List<EventView> timeline(@PathVariable long id) {
        return rescueService.timeline(id);
    }
}
