package com.example.starter.adapter;

import com.example.starter.asrun.AsRunRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 合成播出适配器：仅记录指令序列，不连接真实设备；测试可读取已发指令。
 */
@Component
public class SyntheticPlayoutAdapter implements PlayoutAdapter {

    /**
     * 已发送指令：格式 "ON#<channel>#<seq>#<mediaDigest>" / "OFF#<channel>#<seq>"。
     */
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @Override
    public void onAir(AsRunRecord record) {
        commands.add("ON#" + record.channelId() + "#" + record.seq() + "#" + record.mediaDigest());
    }

    @Override
    public void offAir(AsRunRecord record) {
        commands.add("OFF#" + record.channelId() + "#" + record.seq());
    }

    public List<String> commands() {
        return List.copyOf(commands);
    }

    public void clear() {
        commands.clear();
    }
}
