package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 滚动调洪演算版本 PO：每 30 分钟滚动重算生成新版本，旧版本保留可查；
 * 输入快照含过程线与库容曲线，任一版本可单独复算。
 */
public class RollingRoutingPO {
    public Long id;
    public String rollingNo;
    public Long warningId;
    public Long pondId;
    /** 版本号，同一处置链内自 1 递增。 */
    public Integer versionNo;
    public String requestId;
    /** 上一版本主键，首版本为 null。 */
    public Long previousId;
    /** 本次演算基准时刻（时区 Asia/Shanghai）。 */
    public LocalDateTime scheduledTime;
    public BigDecimal currentWaterLevel;
    public BigDecimal currentStorage;
    public BigDecimal maxStorage;
    public BigDecimal availableCapacity;
    /** 基准时刻入库流量，立方米每秒。 */
    public BigDecimal inflowRate;
    /** 本时段（30 分钟）入库洪量，立方米。 */
    public BigDecimal periodInflowVolume;
    /** 当前生效方案泄流能力，立方米每秒，无方案为 0。 */
    public BigDecimal releaseCapacity;
    /** 过程线剩余段峰值泄流需求，立方米每秒。 */
    public BigDecimal requiredRelease;
    /** 下一演算时刻预计蓄水量，立方米。 */
    public BigDecimal projectedStorage;
    /** 泄流能力判定：SUFFICIENT / CRITICAL / INSUFFICIENT。 */
    public String releaseJudgment;
    public BigDecimal dryBeachLength;
    public BigDecimal phreaticDepth;
    public String dryBeachJudgment;
    public String phreaticJudgment;
    public String dangerJudgment;
    /** 本次演算后的预警级别。 */
    public String warningLevelAfter;
    /** 本次演算后当前方案版本号，无方案为 null。 */
    public Integer planVersionAfter;
    /** 全部源输入快照（JSON，含过程线与库容曲线），供单版本复算。 */
    public String inputSnapshot;
    public String criterion;
    public LocalDateTime createdAt;
}
