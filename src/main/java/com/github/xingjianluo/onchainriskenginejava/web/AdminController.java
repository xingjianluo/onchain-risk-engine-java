package com.github.xingjianluo.onchainriskenginejava.web;

import com.github.xingjianluo.onchainriskenginejava.service.RiskEngineService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理接口：规则发布与引擎重置（本地演示 / 测试用）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RiskEngineService service;

    public AdminController(RiskEngineService service) {
        this.service = service;
    }

    /** 按名称发布内置规则集：POST /api/admin/rules/publish-builtin?name=v2 */
    @PostMapping("/rules/publish-builtin")
    public Map<String, String> publishBuiltIn(@RequestParam String name) {
        return Map.of("activeVersion", service.publishBuiltIn(name));
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        service.reset();
        return Map.of("status", "RESET");
    }
}
