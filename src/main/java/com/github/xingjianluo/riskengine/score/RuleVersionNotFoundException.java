package com.github.xingjianluo.riskengine.score;

/**
 * 按版本号复算时，该版本从未发布过。
 */
public class RuleVersionNotFoundException extends RuntimeException {

    public RuleVersionNotFoundException(int version) {
        super("rule set version not found: " + version);
    }
}
