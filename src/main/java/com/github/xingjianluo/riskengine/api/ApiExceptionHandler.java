package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.DuplicateRuleVersionException;
import com.github.xingjianluo.riskengine.score.RuleVersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常映射：非法输入 400；重复发布同一规则版本 409；版本不存在 404。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(DuplicateRuleVersionException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateVersion(DuplicateRuleVersionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "DUPLICATE_RULE_VERSION", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(RuleVersionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleVersionNotFound(RuleVersionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "RULE_VERSION_NOT_FOUND", "message", String.valueOf(ex.getMessage())));
    }
}
