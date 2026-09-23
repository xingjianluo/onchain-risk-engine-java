package com.github.xingjianluo.riskengine.score;

import java.math.BigDecimal;

/**
 * 版本化、不可变的风险规则集。
 *
 * <p>规则本身是记录对象，一旦发布即不可修改；规则集的"更新"是整体替换引用，
 * 进行中的评分继续使用开始时拿到的旧引用，因此历史结论可随时按版本复现。</p>
 *
 * @param version               规则集版本号，单调递增
 * @param largeAmountThreshold  大额转账阈值（含）
 * @param fanOutThreshold       对外交易对手数阈值（含）
 * @param fanInThreshold        交易对手来源数阈值（含）
 * @param largeAmountWeight     命中单笔大额的加分
 * @param fanOutWeight          命中分散转出的加分
 * @param fanInWeight           命中多方归集的加分
 * @param alertScoreThreshold   告警分数线（含）
 */
public record RiskRuleSet(
        int version,
        BigDecimal largeAmountThreshold,
        int fanOutThreshold,
        int fanInThreshold,
        int largeAmountWeight,
        int fanOutWeight,
        int fanInWeight,
        int alertScoreThreshold) {

    public static final BigDecimal DEFAULT_LARGE_AMOUNT = new BigDecimal("10000");
    public static final int DEFAULT_FAN_OUT = 5;
    public static final int DEFAULT_FAN_IN = 5;
    public static final int DEFAULT_LARGE_WEIGHT = 20;
    public static final int DEFAULT_FAN_OUT_WEIGHT = 30;
    public static final int DEFAULT_FAN_IN_WEIGHT = 20;
    public static final int DEFAULT_ALERT_THRESHOLD = 50;

    public static RiskRuleSet defaultRules() {
        return new RiskRuleSet(1,
                DEFAULT_LARGE_AMOUNT,
                DEFAULT_FAN_OUT,
                DEFAULT_FAN_IN,
                DEFAULT_LARGE_WEIGHT,
                DEFAULT_FAN_OUT_WEIGHT,
                DEFAULT_FAN_IN_WEIGHT,
                DEFAULT_ALERT_THRESHOLD);
    }

    /**
     * 构造新版本规则集并做合法性校验；非法规则不允许发布。
     */
    public static RiskRuleSet validated(int version,
                                        BigDecimal largeAmountThreshold,
                                        int fanOutThreshold,
                                        int fanInThreshold,
                                        int largeAmountWeight,
                                        int fanOutWeight,
                                        int fanInWeight,
                                        int alertScoreThreshold) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (largeAmountThreshold == null || largeAmountThreshold.signum() <= 0) {
            throw new IllegalArgumentException("largeAmountThreshold must be positive");
        }
        if (fanOutThreshold <= 0 || fanInThreshold <= 0) {
            throw new IllegalArgumentException("fan thresholds must be positive");
        }
        if (largeAmountWeight < 0 || fanOutWeight < 0 || fanInWeight < 0
                || alertScoreThreshold < 0 || alertScoreThreshold > 100) {
            throw new IllegalArgumentException("weights and alert threshold out of range [0,100]");
        }
        return new RiskRuleSet(version, largeAmountThreshold,
                fanOutThreshold, fanInThreshold,
                largeAmountWeight, fanOutWeight, fanInWeight,
                alertScoreThreshold);
    }
}
