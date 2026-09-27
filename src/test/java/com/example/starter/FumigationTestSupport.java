package com.example.starter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.dto.FumigationDtos.ApplyFumigationRequest;
import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.StartRequest;

/**
 * 熏蒸测试支撑：合成数据构造与流程推进助手。作业窗与采样时刻均为 UTC 合成时刻。
 */
abstract class FumigationTestSupport extends AbstractIntegrationTest {

    /** 熏蒸作业窗起点：2026-09-27 00:00 UTC，避开通风测试窗口。 */
    protected static final LocalDateTime F0 = LocalDateTime.of(2026, 9, 27, 0, 0);

    /** 申请 02:00-04:00 UTC、阈值 0.500 的熏蒸单（版本 1，待审批）。 */
    protected FumigationView newOrder(long siloId, String key) {
        return fumigationService.apply(siloId, new ApplyFumigationRequest("f-" + key + "-apply",
                F0.plusHours(2), F0.plusHours(4), "合成方案-" + key, new BigDecimal("0.500")));
    }

    /** 两名不同审批人批准，返回批准后视图（版本 3，已批准）。 */
    protected FumigationView approveTwice(long orderId, String key) {
        fumigationService.approve(orderId, new ApproveRequest("f-" + key + "-ap1", "审批人甲", 1));
        fumigationService.approve(orderId, new ApproveRequest("f-" + key + "-ap2", "审批人乙", 2));
        return fumigationService.getOrder(orderId).order();
    }

    /** 申请并批准（版本 3，已批准）。 */
    protected FumigationView newApprovedOrder(long siloId, String key) {
        FumigationView order = newOrder(siloId, key);
        return approveTwice(order.id(), key);
    }

    /** 申请、批准并开始（版本 4，熏蒸中，方案版本冻结为 3）。 */
    protected FumigationView newFumigatingOrder(long siloId, String key) {
        FumigationView approved = newApprovedOrder(siloId, key);
        return fumigationService.start(approved.id(),
                new StartRequest("f-" + key + "-start", 3, F0.plusHours(2)));
    }

    /** 申请、批准、开始并结束转散气（版本 5，散气中，绑定风机）。 */
    protected FumigationView newAeratingOrder(long siloId, long fanId, String key) {
        FumigationView fumigating = newFumigatingOrder(siloId, key);
        return fumigationService.finish(fumigating.id(),
                new FinishRequest("f-" + key + "-finish", 4, fanId, F0.plusHours(3)));
    }

    /** 上报一条散气读数。 */
    protected void sample(long orderId, String key, int seqNo, String concentration, LocalDateTime measuredAt) {
        fumigationService.sample(orderId, new GasReadingRequest("f-" + key + "-g" + seqNo, seqNo,
                new BigDecimal(concentration), measuredAt));
    }

    /** 上报连续三点达标读数（seq 1..3，10:00/10:15/10:30 UTC，恰满 30 分钟间隔）。 */
    protected void sampleThreeGoodPoints(long orderId, String key) {
        sample(orderId, key, 1, "0.400", F0.plusHours(10));
        sample(orderId, key, 2, "0.500", F0.plusHours(10).plusMinutes(15));
        sample(orderId, key, 3, "0.300", F0.plusHours(10).plusMinutes(30));
    }
}
