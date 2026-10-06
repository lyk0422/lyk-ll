package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.RollActualItemView;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollFreezeView;
import com.example.starter.rolling.api.dto.RollLedgerView;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.service.RollLifeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/actuals")
    public RollActualView reportActual(@Valid @RequestBody RollActualRequest request) {
        return service.reportActual(request);
    }

    @PostMapping("/mount")
    public RollLedgerView mount(@Valid @RequestBody RollMountRequest request) {
        return service.mount(request);
    }

    @PostMapping("/freeze")
    public RollFreezeView freezeAll(@Valid @RequestBody RollFreezeRequest request) {
        return service.freezeAll(request);
    }

    @GetMapping("/{rollNo}")
    public RollLedgerView getLedger(@PathVariable String rollNo) {
        return service.getLedger(rollNo);
    }

    @GetMapping("/{rollNo}/actuals")
    public List<RollActualItemView> listActuals(@PathVariable String rollNo) {
        return service.listActuals(rollNo);
    }
}
