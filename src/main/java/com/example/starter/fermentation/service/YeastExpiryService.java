package com.example.starter.fermentation.service;

import com.example.starter.fermentation.repository.YeastRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 酵母失效标记：可用单元距最近回收超过 72 小时即失效。
 * 失效是独立的状态变更，在独立事务中提交——即使触发它的投酵请求被 422 拒绝并回滚，
 * 失效结果（状态 EXPIRED、版本 +1）仍然保留，供后续查询与投酵检查。
 */
@Service
public class YeastExpiryService {

    private final YeastRepository yeastRepository;

    public YeastExpiryService(YeastRepository yeastRepository) {
        this.yeastRepository = yeastRepository;
    }

    /**
     * 在独立新事务中将可用单元置为 EXPIRED（版本 +1），不随调用方事务回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(long unitId, int expectedVersion) {
        yeastRepository.markExpired(unitId, expectedVersion);
    }
}
