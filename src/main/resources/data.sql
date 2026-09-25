-- 合成主数据：业务系统、设备、设备-业务关联、窗口日历。
-- 全部为虚构数据，仅用于本地演示与测试，仅 JVM 生命周期内有效。

MERGE INTO business_system (id, code, name) KEY(id) VALUES
    (1, 'BIZ-PAY', '支付核心系统'),
    (2, 'BIZ-ORDER', '订单交易系统'),
    (3, 'BIZ-RISK', '风控平台'),
    (4, 'BIZ-BI', '经营分析平台');

MERGE INTO device (id, code, name) KEY(id) VALUES
    (1, 'DEV-CORE-SW-01', '核心区交换机01'),
    (2, 'DEV-CORE-SW-02', '核心区交换机02'),
    (3, 'DEV-FW-01', '边界防火墙01');

MERGE INTO device_business (id, device_code, business_code) KEY(id) VALUES
    (1, 'DEV-CORE-SW-01', 'BIZ-PAY'),
    (2, 'DEV-CORE-SW-01', 'BIZ-ORDER'),
    (3, 'DEV-CORE-SW-01', 'BIZ-RISK'),
    (4, 'DEV-CORE-SW-02', 'BIZ-ORDER'),
    (5, 'DEV-CORE-SW-02', 'BIZ-BI'),
    (6, 'DEV-FW-01', 'BIZ-PAY'),
    (7, 'DEV-FW-01', 'BIZ-RISK');

-- 示例窗口日历（窗口建议落在凌晨低峰；业务校验以申请提交的窗口时间为准）
MERGE INTO window_calendar (id, window_start, window_end, description) KEY(id) VALUES
    (1, '2026-09-26 01:00:00', '2026-09-26 05:00:00', '周五凌晨标准割接窗口');
