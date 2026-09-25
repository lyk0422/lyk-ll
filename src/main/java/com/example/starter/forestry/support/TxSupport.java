package com.example.starter.forestry.support;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编程式事务支撑：业务事务默认 REQUIRED；证件作废/限批等“须先落库再返回错误”
 * 的副作用使用 REQUIRES_NEW 独立提交。
 */
@Component
public class TxSupport {

    private final PlatformTransactionManager transactionManager;

    public TxSupport(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * 在默认事务（无则新建）中执行并返回结果。
     */
    public <T> T execute(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    /**
     * 在默认事务中执行无返回值操作。
     */
    public void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 在独立新事务中执行并提交（即使外层随后回滚）。
     */
    public <T> T executeRequiresNew(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    /**
     * 在独立新事务中执行并提交。
     */
    public void runRequiresNew(Runnable action) {
        executeRequiresNew(() -> {
            action.run();
            return null;
        });
    }
}
