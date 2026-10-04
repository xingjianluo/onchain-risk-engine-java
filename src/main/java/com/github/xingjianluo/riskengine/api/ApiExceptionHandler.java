package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RuleVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一把非法输入（非法地址、非法规则集）映射为 400，而不是 500；
 * 规则版本冲突（同版本号重复发布 / 版本回退）映射为 409。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(RuleVersionConflictException.class)
    public ResponseEntity<Map<String, String>> handleRuleVersionConflict(RuleVersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "RULE_VERSION_CONFLICT", "message", String.valueOf(ex.getMessage())));
    }
}
