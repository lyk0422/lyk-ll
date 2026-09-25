package com.example.starter.margin;

import com.example.starter.margin.api.SetupAccountRequest;
import com.example.starter.margin.repo.AccountDataRepository;
import com.example.starter.margin.service.AccountSetupService;
import com.example.starter.margin.support.MarginClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空业务表并复位时钟，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractMarginH2Test {

    @Autowired
    protected AccountSetupService setupService;
    @Autowired
    protected AccountDataRepository accountData;
    @Autowired
    protected MarginClock clock;

    @BeforeEach
    void clean() {
        accountData.deleteAll();
        clock.reset();
    }

    /** 装配一个账户：totalAssets 为初始总资产，liabilities 为逐笔负债（编号, 金额）。 */
    protected void setupAccount(String accountNo, String assets, String currency,
                                String[][] liabilities) {
        List<SetupAccountRequest.LiabilitySeed> seeds = java.util.Arrays.stream(liabilities)
                .map(l -> new SetupAccountRequest.LiabilitySeed(l[0], new BigDecimal(l[1])))
                .toList();
        setupService.setup(new SetupAccountRequest(accountNo, "客户-" + accountNo, currency,
                new BigDecimal(assets), seeds));
    }
}
