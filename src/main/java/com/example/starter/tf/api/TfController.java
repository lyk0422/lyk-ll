package com.example.starter.tf.api;

import com.example.starter.tf.domain.LookupResult;
import com.example.starter.tf.domain.TfService;
import com.example.starter.tf.math.Transform3d;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * TF2 风格坐标变换缓存的 HTTP 入口。
 */
@RestController
@RequestMapping("/api/tf")
public class TfController {

    private final TfService tfService;

    public TfController(TfService tfService) {
        this.tfService = tfService;
    }

    /** 注册坐标系，已存在时幂等。 */
    @PostMapping("/frames")
    @ResponseStatus(HttpStatus.CREATED)
    public void registerFrame(@Valid @RequestBody FrameRequest request) {
        tfService.registerFrame(request.frame());
    }

    /** 写入静态变换（parent -> child，全时刻有效）。 */
    @PostMapping("/transforms/static")
    @ResponseStatus(HttpStatus.CREATED)
    public void writeStatic(@Valid @RequestBody TransformWriteRequest request) {
        tfService.writeStatic(request.parentFrame(), request.childFrame(),
                request.source(), toTransform(request.pose()));
    }

    /** 写入动态样本（允许乱序补录，同参重放幂等）。 */
    @PostMapping("/transforms/dynamic")
    @ResponseStatus(HttpStatus.CREATED)
    public void writeDynamic(@Valid @RequestBody TransformWriteRequest request) {
        if (request.stampNanos() == null) {
            throw new IllegalArgumentException("动态变换必须提供 stampNanos（epoch 纳秒）");
        }
        tfService.writeDynamic(request.parentFrame(), request.childFrame(),
                request.source(), request.stampNanos(), toTransform(request.pose()));
    }

    /** 查询指定时刻 source 在 target 坐标系中的位姿。 */
    @GetMapping("/lookup")
    public LookupResponse lookup(@RequestParam String source,
                                 @RequestParam String target,
                                 @RequestParam("stampNanos") long stampNanos) {
        LookupResult result = tfService.lookup(source, target, stampNanos);
        Transform3d t = result.transform();
        return new LookupResponse(
                source, target, stampNanos, result.path(),
                new PoseDto(t.tx(), t.ty(), t.tz(), t.qx(), t.qy(), t.qz(), t.qw()),
                result.interpolated());
    }

    /** 手动触发一次缓存窗口淘汰。 */
    @PostMapping("/evict")
    public EvictResponse evict() {
        return new EvictResponse(tfService.evictExpired());
    }

    private static Transform3d toTransform(PoseDto pose) {
        return new Transform3d(pose.tx(), pose.ty(), pose.tz(),
                pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalized();
    }
}
