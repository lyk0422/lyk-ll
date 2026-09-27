package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastRecoveryRequest;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.YeastService;
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
 * 合成酵母单元登记、受控投酵与回收追溯 API。写接口以 requestId 幂等，
 * 修改类操作携带 expectedVersion 乐观版本；旧的无酵母主酵入口保留兼容。
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

    /** 查询酵母单元（含按当前时刻计算的失效有效状态）。 */
    @GetMapping("/yeast-units/{unitCode}")
    public YeastDtos.YeastUnitResponse getUnit(@PathVariable String unitCode) {
        return yeastService.getUnit(unitCode);
    }

    /** 查询单元回收记录链（追溯）。 */
    @GetMapping("/yeast-units/{unitCode}/recoveries")
    public Object getRecoveries(@PathVariable String unitCode) {
        return yeastService.getRecoveries(unitCode);
    }

    /** 受控投酵：批次启动主酵并原子绑定酵母。 */
    @PostMapping("/batches/{id}/yeast-pitch")
    public ResponseEntity<String> pitch(@PathVariable long id,
                                        @Valid @RequestBody PitchYeastRequest request) {
        return respond(yeastService.pitch(id, request));
    }

    /** 回收绑定酵母：绑定批次完成主酵后受理。 */
    @PostMapping("/batches/{id}/yeast-recovery")
    public ResponseEntity<String> recover(@PathVariable long id,
                                          @Valid @RequestBody YeastRecoveryRequest request) {
        return respond(yeastService.recover(id, request));
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
