package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastUnitRequest;
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
 * 酵母代次限用与投酵回收追溯 API。写接口以 requestId 幂等（同键同参重放首次结果，
 * 同键异参 409），修改类请求携带 expectedVersion 做乐观锁校验。
 */
@RestController
@RequestMapping("/api/yeast-units")
public class YeastController {

    private final YeastService yeastService;

    public YeastController(YeastService yeastService) {
        this.yeastService = yeastService;
    }

    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody RegisterYeastUnitRequest request) {
        return respond(yeastService.register(request));
    }

    @PostMapping("/{unitCode}/recovery")
    public ResponseEntity<String> recover(@PathVariable String unitCode,
                                          @Valid @RequestBody RecoverYeastRequest request) {
        return respond(yeastService.recover(unitCode, request));
    }

    @GetMapping("/{unitCode}")
    public Object get(@PathVariable String unitCode) {
        return yeastService.getUnit(unitCode);
    }

    @GetMapping("/{unitCode}/recoveries")
    public Object recoveries(@PathVariable String unitCode) {
        return yeastService.getRecoveries(unitCode);
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
