package com.example.starter.playout.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合成播出适配器：仅记录日志，模拟向播出设备下发开始指令。
 */
@Component
public class LoggingPlayoutAdapter implements PlayoutAdapter {

    private static final Logger log = LoggerFactory.getLogger(LoggingPlayoutAdapter.class);

    @Override
    public void start(PlayoutCommand command) {
        log.info("synthetic playout start channel={} asRunSequence={} content={}",
                command.channel(), command.asRunSequence(), command.content());
    }
}
