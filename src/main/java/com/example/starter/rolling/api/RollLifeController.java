package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.service.RollLifeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轧辊寿命台账 HTTP 接口。
 */
@RestController
@RequestMapping("/api/rolling/rolls")
public class RollLifeController {

    private final RollLifeService service;

    public RollLifeController(RollLifeService service) {
        this.service = service;
    }

    @PostMapping("/mount")
    public RollView mount(@Valid @RequestBody RollMountRequest request) {
        return service.mount(request);
    }

    @PostMapping("/actuals")
    public RollActualView reportActual(@Valid @RequestBody RollActualRequest request) {
        return service.reportActual(request);
    }

    @PostMapping("/{rollNo}/freeze")
    public RollView freeze(@PathVariable String rollNo, @Valid @RequestBody RollFreezeRequest request) {
        return service.freeze(rollNo, request);
    }

    @PostMapping("/{rollNo}/recompute")
    public RollView recompute(@PathVariable String rollNo) {
        return service.recompute(rollNo);
    }

    @GetMapping("/{rollNo}")
    public RollView get(@PathVariable String rollNo) {
        return service.get(rollNo);
    }
}
