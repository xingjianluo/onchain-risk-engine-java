package com.github.xingjianluo.riskengine.score;

/**
 * 规则版本冲突：试图用已发布过的版本号（或落后于当前版本的版本号）再次发布。
 * 规则版本只能往前追加，不允许覆盖历史内容。
 */
public class RuleVersionConflictException extends RuntimeException {

    public RuleVersionConflictException(String message) {
        super(message);
    }
}
