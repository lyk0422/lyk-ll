package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.service.YeastService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成酵母单元受控投酵与回收追溯 API。写接口以 requestId 幂等，
 * 变更接口以 expectedVersion 做乐观并发控制。
 */
@RestController
@RequestMapping("/api")
public class YeastController {

    private final YeastService yeastService;

    public YeastController(YeastService yeastService) {
        this.yeastService = yeastService;
    }

    /** 登记合成酵母单元。 */
    @PostMapping("/yeast-units")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterYeastRequest request) {
        return respond(yeastService.register(request));
    }

    /** 查询酵母单元。 */
    @GetMapping("/yeast-units/{unitCode}")
    public YeastDtos.YeastUnitResponse getUnit(@PathVariable String unitCode) {
        var unit = yeastService.getUnit(unitCode);
        return new YeastDtos.YeastUnitResponse(unit.unitCode(), unit.generation(), unit.status(),
                unit.lastRecoveredAt(), unit.occupiedByBatchId(), unit.version());
    }

    /** 受控投酵：指定酵母原子绑定并启动主酵。 */
    @PostMapping("/batches/{id}/yeast-pitch")
    public ResponseEntity<String> pitch(@PathVariable long id,
                                        @Valid @RequestBody PitchYeastRequest request) {
        return respond(yeastService.pitch(id, request));
    }

    /** 回收批次绑定的酵母。 */
    @PostMapping("/batches/{id}/yeast-recovery")
    public ResponseEntity<String> recover(@PathVariable long id,
                                          @Valid @RequestBody RecoverYeastRequest request) {
        return respond(yeastService.recover(id, request));
    }

    /** 查询批次的酵母回收追溯记录。 */
    @GetMapping("/batches/{id}/yeast-recovery")
    public YeastDtos.RecoveryResponse getRecovery(@PathVariable long id) {
        return yeastService.getRecovery(id);
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
