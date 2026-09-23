package com.example.starter.lot.web;

import com.example.starter.lot.api.BalanceView;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.GenealogyView;
import com.example.starter.lot.api.ImpactView;
import com.example.starter.lot.api.LotView;
import com.example.starter.lot.api.QuarantineRequest;
import com.example.starter.lot.api.QuarantineResponse;
import com.example.starter.lot.api.TransformRequest;
import com.example.starter.lot.api.TransformResponse;
import com.example.starter.lot.service.LotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次谱系 REST API。
 */
@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotService lotService;

    public LotController(LotService lotService) {
        this.lotService = lotService;
    }

    /** 建批次。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotView create(@Valid @RequestBody CreateLotRequest request) {
        return lotService.createLot(request);
    }

    /** 批次详情（含当前生效隔离来源）。 */
    @GetMapping("/{lotId}")
    public LotView get(@PathVariable String lotId) {
        return lotService.getLot(lotId);
    }

    /** 执行转换（多父消费、多子产出、损耗约束、幂等）。 */
    @PostMapping("/transforms")
    public TransformResponse transform(@Valid @RequestBody TransformRequest request) {
        return lotService.transform(request);
    }

    /** 设置或解除隔离。 */
    @PostMapping("/quarantine")
    public QuarantineResponse quarantine(@Valid @RequestBody QuarantineRequest request) {
        return lotService.quarantine(request);
    }

    /** 谱系上下游查询。 */
    @GetMapping("/{lotId}/genealogy")
    public GenealogyView genealogy(@PathVariable String lotId) {
        return lotService.genealogy(lotId);
    }

    /** 可用余额查询。 */
    @GetMapping("/{lotId}/balance")
    public BalanceView balance(@PathVariable String lotId) {
        return lotService.balance(lotId);
    }

    /** 隔离影响面查询。 */
    @GetMapping("/{lotId}/impact")
    public ImpactView impact(@PathVariable String lotId) {
        return lotService.impact(lotId);
    }
}
