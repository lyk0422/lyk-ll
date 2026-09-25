package com.example.starter.invoice.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.invoice.repo.CreditAccountRepository;
import com.example.starter.invoice.repo.CreditAccountRow;
import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.CreateCreditAccountRequest;
import com.example.starter.invoice.web.CreditAccountResponse;

/**
 * 授信账户服务：开户与额度查询。
 */
@Service
public class CreditAccountService {

    private final CreditAccountRepository creditAccountRepository;
    private final Clock clock;

    public CreditAccountService(CreditAccountRepository creditAccountRepository, Clock clock) {
        this.creditAccountRepository = creditAccountRepository;
        this.clock = clock;
    }

    /**
     * 新建授信账户；同销方税号重复开户返回 409。
     */
    @Transactional
    public CreditAccountResponse create(CreateCreditAccountRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            creditAccountRepository.insert(request.sellerTaxNo(), request.totalLimit(), now);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS",
                    "销方授信账户已存在：" + request.sellerTaxNo());
        }
        return toResponse(creditAccountRepository.findBySellerTaxNo(request.sellerTaxNo()).orElseThrow());
    }

    /**
     * 查询授信账户与可用额度。重复查询结果稳定。
     */
    @Transactional(readOnly = true)
    public CreditAccountResponse getBySellerTaxNo(String sellerTaxNo) {
        return creditAccountRepository.findBySellerTaxNo(sellerTaxNo)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("销方授信账户不存在：" + sellerTaxNo));
    }

    private CreditAccountResponse toResponse(CreditAccountRow account) {
        return new CreditAccountResponse(account.sellerTaxNo(), account.totalLimit(),
                account.blueUnarchivedAmount(), account.redConfirmedAmount(), account.available());
    }
}
