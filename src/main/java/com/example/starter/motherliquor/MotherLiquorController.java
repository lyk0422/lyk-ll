package com.example.starter.motherliquor;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 母液登记、库存与勾稽台账查询接口。
 */
@RestController
@RequestMapping("/api/mother-liquors")
public class MotherLiquorController {

    private final MotherLiquorService service;

    public MotherLiquorController(MotherLiquorService service) {
        this.service = service;
    }

    /**
     * 登记母液请求。
     *
     * @param nuclideId          核素
     * @param initialActivityMbq 初始活度，单位 MBq
     * @param measuredAt         初始活度测量时刻（UTC）
     */
    public record RegisterRequest(@NotNull Long nuclideId, @Positive double initialActivityMbq,
                                  @NotNull Instant measuredAt) {
    }

    /**
     * 登记母液。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MotherLiquorService.MotherLiquorView register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.nuclideId(), request.initialActivityMbq(), request.measuredAt());
    }

    /**
     * 查询母液当前库存。
     */
    @GetMapping("/{id}")
    public MotherLiquorService.MotherLiquorView get(@PathVariable long id) {
        return service.require(id);
    }

    /**
     * 查询母液勾稽台账。
     */
    @GetMapping("/{id}/ledger")
    public List<MotherLiquorService.LedgerView> ledger(@PathVariable long id) {
        return service.ledger(id);
    }
}
