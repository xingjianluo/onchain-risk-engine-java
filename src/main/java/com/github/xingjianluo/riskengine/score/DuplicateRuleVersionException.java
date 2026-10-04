package com.github.xingjianluo.riskengine.score;

/**
 * 试图用已发布过的版本号再次发布规则集时抛出。
 * 规则版本只追加、不覆盖，保证历史结论永远可按版本号复现。
 */
public class DuplicateRuleVersionException extends RuntimeException {

    public DuplicateRuleVersionException(int version) {
        super("rule set version already published: " + version);
    }
}
