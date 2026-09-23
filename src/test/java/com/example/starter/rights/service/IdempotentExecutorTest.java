package com.example.starter.rights.service;

import com.example.starter.rights.ApiException;
import com.example.starter.rights.AppClock;
import com.example.starter.rights.repo.RequestLedgerRepository;
import com.example.starter.rights.repo.RequestLedgerRepository.LedgerEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等执行器纯单元测试：不触碰数据库，验证指纹重放、异参冲突、失败不登记与并发占位。
 */
class IdempotentExecutorTest {

    private RequestLedgerRepository ledger;
    private IdempotentExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ledger = mock(RequestLedgerRepository.class);
        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        executor = new IdempotentExecutor(ledger, clock, objectMapper);
        when(ledger.find(anyString())).thenReturn(Optional.empty());
    }

    private record Result(String licenseId, int version) {
    }

    @Test
    void firstRequestRunsActionAndCompletesLedger() {
        AtomicInteger calls = new AtomicInteger();
        Result out = executor.execute("r1", "CREATE_LICENSE", "fp-a", Result.class,
                () -> {
                    calls.incrementAndGet();
                    return new Result("lic-1", 1);
                });
        assertThat(out).isEqualTo(new Result("lic-1", 1));
        assertThat(calls).hasValue(1);
        verify(ledger).insertPending(anyString(), anyString(), anyString(), any());
        verify(ledger).complete(anyString(), anyInt(), anyString());
    }

    @Test
    void sameFingerprintReplaysStoredResponseWithoutRunningAction() {
        String json = "{\"licenseId\":\"lic-1\",\"version\":1}";
        when(ledger.find("r1")).thenReturn(Optional.of(
                new LedgerEntry("CREATE_LICENSE", "fp-a", 200, json)));
        AtomicInteger calls = new AtomicInteger();
        Result out = executor.execute("r1", "CREATE_LICENSE", "fp-a", Result.class,
                () -> {
                    calls.incrementAndGet();
                    return new Result("should-not-run", 9);
                });
        assertThat(out).isEqualTo(new Result("lic-1", 1));
        assertThat(calls).hasValue(0);
        verify(ledger, never()).insertPending(anyString(), anyString(), anyString(), any());
    }

    @Test
    void differentFingerprintConflicts() {
        when(ledger.find("r1")).thenReturn(Optional.of(
                new LedgerEntry("CREATE_LICENSE", "fp-a", 200, "{}")));
        assertThatThrownBy(() -> executor.execute("r1", "CREATE_LICENSE", "fp-different",
                Result.class, () -> new Result("x", 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void businessFailureSkipsCompletionAndPropagates() {
        assertThatThrownBy(() -> executor.execute("r1", "CREATE_LICENSE", "fp-a", Result.class, () -> {
            throw ApiException.conflict("EXCLUSIVE_CONFLICT", "boom");
        })).isInstanceOf(ApiException.class);
        verify(ledger).insertPending(anyString(), anyString(), anyString(), any());
        verify(ledger, never()).complete(anyString(), anyInt(), anyString());
    }

    @Test
    void concurrentPendingInsertLosesAndReplaysWinner() {
        String json = "{\"licenseId\":\"lic-winner\",\"version\":1}";
        when(ledger.find("r1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new LedgerEntry("CREATE_LICENSE", "fp-a", 200, json)));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("unique"))
                .when(ledger).insertPending(anyString(), anyString(), anyString(), any());
        Result out = executor.execute("r1", "CREATE_LICENSE", "fp-a", Result.class,
                () -> new Result("loser-action", 1));
        assertThat(out.licenseId()).isEqualTo("lic-winner");
        verify(ledger, times(2)).find("r1");
    }

    @Test
    void fingerprintIsStableAndSensitiveToPayload() {
        String fp1 = executor.fingerprint("CREATE_LICENSE", new Result("lic-1", 1));
        String fp1Again = executor.fingerprint("CREATE_LICENSE", new Result("lic-1", 1));
        String fp2 = executor.fingerprint("CREATE_LICENSE", new Result("lic-1", 2));
        String fp3 = executor.fingerprint("REVOKE_LICENSE", new Result("lic-1", 1));
        assertThat(fp1).isEqualTo(fp1Again).hasSize(64);
        assertThat(fp1).isNotEqualTo(fp2);
        assertThat(fp1).isNotEqualTo(fp3);
    }
}
