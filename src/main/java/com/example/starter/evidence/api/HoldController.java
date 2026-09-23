package com.example.starter.evidence.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.evidence.api.dto.CreateHoldRequest;
import com.example.starter.evidence.api.dto.HoldView;
import com.example.starter.evidence.api.dto.ReleaseHoldRequest;
import com.example.starter.evidence.service.HoldService;

/**
 * 法律保全创建、解除与查询 API。
 */
@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    /**
     * 创建保全单；同幂等键同参数重放返回 200，新创建返回 201。
     */
    @PostMapping
    public ResponseEntity<HoldView> create(@Valid @RequestBody CreateHoldRequest request) {
        HoldView view = holdService.create(request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 解除保全单，需携带匹配的预期版本。
     */
    @PostMapping("/{holdId}/release")
    public HoldView release(@PathVariable String holdId, @Valid @RequestBody ReleaseHoldRequest request) {
        return holdService.release(holdId, request);
    }

    /**
     * 查询保全单。
     */
    @GetMapping("/{holdId}")
    public HoldView get(@PathVariable String holdId) {
        return holdService.get(holdId);
    }
}
