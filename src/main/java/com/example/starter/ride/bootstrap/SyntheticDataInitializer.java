package com.example.starter.ride.bootstrap;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.example.starter.ride.BusinessClock;
import com.example.starter.ride.RideStatus;
import com.example.starter.ride.domain.Ride;
import com.example.starter.ride.repo.ChecklistRepository;
import com.example.starter.ride.repo.RideRepository;

/**
 * 合成数据初始化：播种两台合成设备及各自日检清单。
 *
 * <p>数据全部为合成数据，仅在 JVM 生命周期内保留；启动时若表中已存在则跳过，
 * 因此测试清库后重新初始化或本地重启都能得到一致的初始状态。</p>
 */
@Component
@Order(0)
public class SyntheticDataInitializer implements CommandLineRunner {

    private final RideRepository rideRepository;
    private final ChecklistRepository checklistRepository;
    private final BusinessClock clock;

    public SyntheticDataInitializer(RideRepository rideRepository,
                                    ChecklistRepository checklistRepository,
                                    BusinessClock clock) {
        this.rideRepository = rideRepository;
        this.checklistRepository = checklistRepository;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        seedRide("R001", "翻滚过山车-云霄号", 800.0d, 20.0d);
        seedRide("R002", "旋转飞椅-追风号", 500.0d, 12.0d);
    }

    private void seedRide(String rideId, String name, double loadThreshold, double speedThreshold) {
        if (rideRepository.findById(rideId) != null) {
            ensureChecklist(rideId);
            return;
        }
        Ride ride = new Ride();
        ride.setId(rideId);
        ride.setName(name);
        ride.setLoadThreshold(loadThreshold);
        ride.setSpeedThreshold(speedThreshold);
        ride.setStatus(RideStatus.PENDING_DAILY_CHECK.name());
        ride.setDailyCheckPassed(false);
        ride.setTrialPassedCount(0);
        ride.setReopenCount(0);
        ride.setOpenSeq(0);
        ride.setUpdatedAt(clock.now());
        rideRepository.insert(ride);
        ensureChecklist(rideId);
    }

    private void ensureChecklist(String rideId) {
        if (!checklistRepository.findItemsByRide(rideId).isEmpty()) {
            return;
        }
        checklistRepository.insertItem(rideId, "PRESSURE_BAR", "压杠装置");
        checklistRepository.insertItem(rideId, "SEAT_BELT", "安全带");
        checklistRepository.insertItem(rideId, "INTERLOCK", "安全联锁装置");
        checklistRepository.insertItem(rideId, "BRAKE", "制动系统");
        checklistRepository.insertItem(rideId, "EMERGENCY_STOP", "急停按钮");
    }
}
