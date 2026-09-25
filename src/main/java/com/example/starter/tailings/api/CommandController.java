package com.example.starter.tailings.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.service.CommandService;
import com.example.starter.tailings.service.PlanService;

/**
 * 泄流指令链：下达 → 签收 → 执行 → 回执。
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    /**
     * 下达指令请求。
     */
    public record IssueBody(@NotBlank String requestId, @NotNull Long planId) {
    }

    /**
     * 回执孔口入参。
     */
    public record OrificeBody(@NotBlank String name, @NotNull BigDecimal discharge) {
    }

    /**
     * 回执请求：实际孔口与泄量；偏差超10%须附原因说明。
     */
    public record ReceiptBody(@NotBlank String requestId,
                              @NotEmpty List<@Valid OrificeBody> actualOrifices,
                              @NotNull BigDecimal actualDischarge,
                              String reason) {
    }

    @PostMapping
    public CommandService.CommandResult issue(@Valid @RequestBody IssueBody body) {
        return commandService.issue(body.requestId(), body.planId());
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<CommandService.AckResult> ack(@PathVariable long id) {
        CommandService.AckResult result = commandService.ack(id);
        return result.accepted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(409).body(result);
    }

    @PostMapping("/{id}/execute")
    public CommandService.CommandResult execute(@PathVariable long id) {
        return commandService.execute(id);
    }

    @PostMapping("/{id}/receipt")
    public CommandService.ReceiptResult receipt(@PathVariable long id,
                                                @Valid @RequestBody ReceiptBody body) {
        List<PlanService.OrificeInput> orifices = body.actualOrifices().stream()
                .map(o -> new PlanService.OrificeInput(o.name(), o.discharge()))
                .toList();
        return commandService.receipt(body.requestId(), id, orifices, body.actualDischarge(), body.reason());
    }

    @GetMapping("/{id}")
    public CommandService.CommandResult get(@PathVariable long id) {
        return commandService.get(id);
    }
}
