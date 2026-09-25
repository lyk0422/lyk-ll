package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.service.CommandService;
import com.example.starter.tailings.service.FloodRoutingService;
import com.example.starter.tailings.service.PlanService;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 泄流指令链：签收恰超时升级、回执偏差恰10%一致、超10%须附原因、回执幂等。
 */
class CommandServiceTest extends IntegrationTestSupport {

    /**
     * 建一条已下达的指令，返回指令ID。
     */
    private long issuedCommand(long pondId) {
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        PlanService.PlanResult plan = planService.create(new PlanService.PlanRequest(
                rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("100")))));
        return commandService.issue(rid(), plan.planId()).commandId();
    }

    @Test
    void issueThenAckWithinDeadline() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        clock.advance(Duration.ofMinutes(14));
        CommandService.AckResult ack = commandService.ack(commandId);
        assertTrue(ack.accepted());
        assertEquals("ACKED", ack.status());
    }

    @Test
    void ackExactlyAtDeadlineEscalates() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        clock.advance(Duration.ofMinutes(15));
        CommandService.AckResult ack = commandService.ack(commandId);
        assertFalse(ack.accepted());
        assertTrue(ack.escalated());
        assertEquals("ESCALATED", ack.status());
        CommandService.CommandResult state = commandService.get(commandId);
        assertEquals("ESCALATED", state.status());
        assertTrue(state.escalated());
        assertEquals(1, jdbc.queryForObject(
                "SELECT escalated FROM discharge_command WHERE id = ?", Integer.class, commandId));
    }

    @Test
    void executeRequiresAcked() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> commandService.execute(commandId));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void receiptDeviationExactlyTenPercentIsConsistent() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        commandService.ack(commandId);
        commandService.execute(commandId);
        CommandService.ReceiptResult receipt = commandService.receipt(rid(), commandId,
                List.of(new PlanService.OrificeInput("1#孔", bd("110"))), bd("110"), null);
        assertEquals(0, bd("10.0000").compareTo(receipt.deviationPct()));
        assertTrue(receipt.consistent());
        assertEquals("RECEIPTED", receipt.status());
    }

    @Test
    void receiptDeviationAboveTenPercentRequiresReason() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        commandService.ack(commandId);
        commandService.execute(commandId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> commandService.receipt(rid(), commandId,
                        List.of(new PlanService.OrificeInput("1#孔", bd("111"))), bd("111"), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        // 校验失败回滚：指令仍为执行中
        assertEquals("EXECUTING", commandService.get(commandId).status());
        CommandService.ReceiptResult receipt = commandService.receipt(rid(), commandId,
                List.of(new PlanService.OrificeInput("1#孔", bd("111"))), bd("111"), "孔口淤堵，实际泄量偏大");
        assertFalse(receipt.consistent());
        assertEquals("RECEIPTED", receipt.status());
    }

    @Test
    void receiptIdempotentReplayAndConflict() {
        long pondId = createPond();
        long commandId = issuedCommand(pondId);
        commandService.ack(commandId);
        commandService.execute(commandId);
        String requestId = rid();
        List<PlanService.OrificeInput> orifices = List.of(new PlanService.OrificeInput("1#孔", bd("100")));
        CommandService.ReceiptResult first = commandService.receipt(requestId, commandId,
                orifices, bd("100"), null);
        CommandService.ReceiptResult replay = commandService.receipt(requestId, commandId,
                orifices, bd("100"), null);
        assertEquals(first.commandId(), replay.commandId());
        assertEquals(first.deviationPct(), replay.deviationPct());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> commandService.receipt(requestId, commandId, orifices, bd("120"), "改参"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }
}
