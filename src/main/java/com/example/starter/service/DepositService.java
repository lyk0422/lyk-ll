package com.example.starter.service;

import com.example.starter.domain.MarginMath;
import com.example.starter.dto.ApiDtos;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import com.example.starter.repo.DepositRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 追加保证金入账服务。以 requestId 幂等：指纹覆盖 requestId、账户、金额与币种；
 * 同键同参返回首次结果，同键异参返回 409 且不重复入账。
 * 入账与强平执行通过账户行级悲观锁互斥，先到达者决定结果。
 */
@Service
public class DepositService {

    private final AccountRepository accountRepo;
    private final DepositRepository depositRepo;
    private final LiquidationService liquidationService;
    private final Clock clock;

    public DepositService(AccountRepository accountRepo, DepositRepository depositRepo,
                          LiquidationService liquidationService, Clock clock) {
        this.accountRepo = accountRepo;
        this.depositRepo = depositRepo;
        this.liquidationService = liquidationService;
        this.clock = clock;
    }

    @Transactional
    public ApiDtos.DepositResponse deposit(long accountId, ApiDtos.DepositRequest req) {
        String requestId = req.requestId() == null ? "" : req.requestId().trim();
        if (requestId.isEmpty()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        String currency = req.currency() == null ? "" : req.currency().trim();
        if (currency.isEmpty()) {
            throw ApiException.badRequest("currency 不能为空");
        }
        final long amount;
        try {
            amount = MarginMath.parseCents(req.amount());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (amount <= 0) {
            throw ApiException.badRequest("入账金额必须大于 0");
        }

        String fingerprint = fingerprint(requestId, accountId, amount, currency);
        LocalDateTime now = LocalDateTime.now(clock);

        var inserted = depositRepo.tryInsert(requestId, accountId, amount, currency, fingerprint, now);
        if (inserted.isEmpty()) {
            // 同键重放：同参返回首次结果，异参 409 且不重复入账
            var existing = depositRepo.findByRequestId(requestId).orElseThrow();
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且参数不一致，拒绝重复入账: " + requestId);
            }
            return toResponse(existing, true);
        }

        // 账户行锁内入账并重算：与强平执行互斥
        AccountRepository.AccountRow account = accountRepo.lockById(accountId);
        long assets = account.totalAssets() + amount;
        accountRepo.updateTotals(accountId, assets, account.totalLiabilities());

        Long resolvedCallId = null;
        List<Long> cancelledOrderIds = List.of();
        if (MarginMath.atLeast(assets, account.totalLiabilities(), MarginMath.RECOVER_THRESHOLD_PCT)) {
            // 入账使比例达标：结清追保并撤销该账户待发强平
            var settlement = liquidationService.settleRecovered(accountId, now);
            resolvedCallId = settlement.resolvedMarginCallId();
            cancelledOrderIds = settlement.cancelledOrderIds();
        }

        Integer ratioAfter = MarginMath.ratioBp(assets, account.totalLiabilities());
        depositRepo.updateOutcome(inserted.get(), ratioAfter, resolvedCallId,
                String.join(",", cancelledOrderIds.stream().map(String::valueOf).toList()));
        var stored = depositRepo.findByRequestId(requestId).orElseThrow();
        return toResponse(stored, false);
    }

    private ApiDtos.DepositResponse toResponse(DepositRepository.DepositRow row, boolean duplicated) {
        List<Long> cancelled = row.cancelledOrderIds() == null || row.cancelledOrderIds().isBlank()
                ? List.of()
                : Arrays.stream(row.cancelledOrderIds().split(",")).map(Long::valueOf).toList();
        return new ApiDtos.DepositResponse(row.id(), row.requestId(), duplicated,
                MarginMath.formatCents(row.amount()), row.currency(), row.ratioAfterBp(),
                row.resolvedMarginCallId(), cancelled, row.createdAt().toString());
    }

    /** 幂等指纹：覆盖 requestId、账户、金额与币种。 */
    static String fingerprint(String requestId, long accountId, long amountCents, String currency) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((requestId + "|" + accountId + "|" + amountCents + "|" + currency)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
