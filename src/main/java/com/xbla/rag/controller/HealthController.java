package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * <p><b>为什么有了 actuator 还要自己写一个？</b>
 * actuator 的 {@code /actuator/health} 返回的是「运维视角」的信息，
 * 格式固定、不方便前端对接。这个 {@code /api/health} 用的是我们自己的
 * 统一响应格式，用来验证「Web 层链路是通的」。
 * 两者用途不同，后面阶段 6 做高可用时两个都会用到。
 *
 * <p><b>注意这个类的写法遵循了 CLAUDE.md 的硬性约定：</b>
 * controller 层不写业务逻辑，只做参数校验和响应封装。
 * 所以这里连 {@code @Service} 都没注入 —— 一旦需要业务逻辑，
 * 就应该挪到 service 层去。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * {@code @GetMapping("/health")} 表示这个方法处理
     * {@code GET /api/health} 请求（类上的 {@code @RequestMapping("/api")}
     * 和方法上的路径会拼起来）。
     *
     * <p>{@code @RestController} 等价于 {@code @Controller + @ResponseBody}，
     * 意思是「方法返回值直接序列化成 JSON 写回响应体」，不走视图解析器。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        // LinkedHashMap 保证 JSON 字段顺序和插入顺序一致，方便人眼阅读
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "xbla-rag");
        info.put("status", "UP");
        info.put("timestamp", OffsetDateTime.now().toString());
        info.put("javaVersion", System.getProperty("java.version"));

        return ApiResponse.ok(info);
    }
}
