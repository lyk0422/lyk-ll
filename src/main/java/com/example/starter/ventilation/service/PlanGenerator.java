package com.example.starter.ventilation.service;

import com.example.starter.ventilation.DeviceAction;
import com.example.starter.ventilation.DeviceType;
import com.example.starter.ventilation.domain.DeviceCommandRow;
import com.example.starter.ventilation.domain.DeviceRow;
import com.example.starter.ventilation.domain.ZoneDamperRefRow;
import com.example.starter.ventilation.repo.MasterDataRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 设备指令预案生成。
 *
 * <p>激活顺序：先开启对应风阀（按烟流方向假设决定 OPEN/CLOSE），到位确认后再启动风机，
 * 风阀序号全部小于风机序号；复位顺序为激活设备的逆序：先停风机，到位后关风阀。</p>
 */
@Component
public class PlanGenerator {

    /** 烟流正向。 */
    public static final String FORWARD = "FORWARD";
    /** 烟流反向。 */
    public static final String BACKWARD = "BACKWARD";

    private final MasterDataRepository masterData;

    public PlanGenerator(MasterDataRepository masterData) {
        this.masterData = masterData;
    }

    /**
     * 生成激活阶段预案：风阀在前（OPEN/CLOSE 由方向偏置决定），风机 START 在最后。
     *
     * @param zoneCode       分区编码
     * @param smokeDirection 烟气流向假设 FORWARD / BACKWARD
     */
    public List<PlannedCommand> activatePlan(String zoneCode, String smokeDirection) {
        List<PlannedCommand> plan = new ArrayList<>();
        int seq = 1;

        List<ZoneDamperRefRow> refs = masterData.findDamperRefs(zoneCode).stream()
                .sorted(Comparator.comparing(ZoneDamperRefRow::damperCode))
                .toList();
        for (ZoneDamperRefRow ref : refs) {
            DeviceRow device = masterData.findDevice(ref.damperCode());
            boolean shared = device != null && device.shared();
            plan.add(new PlannedCommand(ref.damperCode(), DeviceType.DAMPER,
                    damperTargetAction(ref.bias(), smokeDirection), seq++, shared));
        }

        List<DeviceRow> fans = masterData.findFans(zoneCode).stream()
                .sorted(Comparator.comparing(DeviceRow::deviceCode))
                .toList();
        for (DeviceRow fan : fans) {
            plan.add(new PlannedCommand(fan.deviceCode(), DeviceType.FAN, DeviceAction.START,
                    seq++, false));
        }
        return plan;
    }

    /**
     * 生成复位阶段预案：激活指令的设备逆序——风机 STOP 在最前，风阀 CLOSE 在其后。
     * CONFLICT / ALIGNED 的共享风阀从未由本会话实际驱动，不生成复位指令。
     */
    public List<PlannedCommand> resetPlan(List<DeviceCommandRow> activateCommands) {
        List<PlannedCommand> plan = new ArrayList<>();
        int seq = 1;
        List<DeviceCommandRow> owned = activateCommands.stream()
                .filter(c -> c.getStatus() != com.example.starter.ventilation.CommandStatus.CONFLICT)
                .filter(c -> c.getStatus() != com.example.starter.ventilation.CommandStatus.ALIGNED)
                .sorted(Comparator.comparingInt(DeviceCommandRow::getSeqNo).reversed())
                .toList();
        for (DeviceCommandRow cmd : owned) {
            DeviceAction action = cmd.getDeviceType() == DeviceType.FAN
                    ? DeviceAction.STOP : DeviceAction.CLOSE;
            plan.add(new PlannedCommand(cmd.getDeviceCode(), cmd.getDeviceType(), action,
                    seq++, cmd.getDeviceType() == DeviceType.DAMPER && isShared(cmd.getDeviceCode())));
        }
        return plan;
    }

    private boolean isShared(String deviceCode) {
        DeviceRow device = masterData.findDevice(deviceCode);
        return device != null && device.shared();
    }

    /**
     * 按方向偏置计算风阀目标动作：
     * PLUS 偏置与 FORWARD 同向，MINUS 偏置与 BACKWARD 同向；同向排烟侧 OPEN，对侧 CLOSE。
     */
    public DeviceAction damperTargetAction(String bias, String smokeDirection) {
        boolean sameSide = ("PLUS".equals(bias) && FORWARD.equals(smokeDirection))
                || ("MINUS".equals(bias) && BACKWARD.equals(smokeDirection));
        return sameSide ? DeviceAction.OPEN : DeviceAction.CLOSE;
    }

    /**
     * 预案中的一条设备指令。
     *
     * @param deviceCode 设备编码
     * @param deviceType 设备类型
     * @param action     目标动作
     * @param seqNo      阶段内顺序（小者先发）
     * @param shared     是否共享风阀
     */
    public record PlannedCommand(String deviceCode, DeviceType deviceType, DeviceAction action,
                                 int seqNo, boolean shared) {
    }
}
