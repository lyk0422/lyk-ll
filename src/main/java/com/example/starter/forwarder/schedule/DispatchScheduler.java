package com.example.starter.forwarder.schedule;

import com.example.starter.forwarder.config.ForwarderProperties;
import com.example.starter.forwarder.service.ForwarderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 补发调度器：按固定间隔驱动一轮补发。线程必须等待启动恢复完成后才开始补发，
 * 避免上一进程遗留的 in-flight 记录被重复发送。
 */
@Component
public class DispatchScheduler implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final ForwarderService forwarderService;
    private final ForwarderProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean recoveryDone = new AtomicBoolean(false);
    private final Object recoveryLock = new Object();
    private Thread worker;

    public DispatchScheduler(ForwarderService forwarderService, ForwarderProperties properties) {
        this.forwarderService = forwarderService;
        this.properties = properties;
    }

    /** 由启动恢复器在 in-flight 重置完成后放行调度循环。 */
    public void markRecoveryDone() {
        synchronized (recoveryLock) {
            recoveryDone.set(true);
            recoveryLock.notifyAll();
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runLoop, "forwarder-dispatch");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        synchronized (recoveryLock) {
            while (!recoveryDone.get()) {
                try {
                    recoveryLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        while (running.get()) {
            try {
                forwarderService.dispatchOnce();
            } catch (Exception e) {
                log.warn("补发轮询异常: {}", e.getMessage());
            }
            try {
                Thread.sleep(Math.max(50L, properties.getDispatchIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }
}
