package com.example.starter.web;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.WeighingService;

/**
 * 进场称重与收运联单登记入口。
 */
@RestController
@RequestMapping("/api/weighings")
public class WeighingController {

    private final WeighingService weighingService;

    public WeighingController(WeighingService weighingService) {
        this.weighingService = weighingService;
    }

    /**
     * 进场登记请求体。重量单位 kg；tareKg 仅在皮重缺失或超过 7 日须复称时必填。
     */
    public record EntryRequest(
            @NotBlank String requestId,
            @NotBlank String plateNo,
            @NotBlank String community,
            @NotBlank String category,
            @NotNull @Positive BigDecimal grossKg,
            @NotBlank String facilityCode,
            @Positive BigDecimal tareKg) {
    }

    @PostMapping("/entry")
    @ResponseStatus(HttpStatus.CREATED)
    public WeighingService.EntryResult registerEntry(@Valid @RequestBody EntryRequest request) {
        return weighingService.registerEntry(new WeighingService.EntryCommand(
                request.requestId(),
                request.plateNo(),
                request.community(),
                request.category(),
                request.grossKg(),
                request.facilityCode(),
                request.tareKg()));
    }
}
