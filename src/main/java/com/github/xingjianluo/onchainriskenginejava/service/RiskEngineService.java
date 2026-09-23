package com.github.xingjianluo.onchainriskenginejava.service;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RiskAlert;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRuleSet;

import java.util.List;

/**
 * 风控引擎应用服务：编排 校验 -> 幂等摄入 -> 快照 -> 构图 -> 评分/告警，
 * 并保证规则更新与查询并发时使用同一个一致性快照。
 */
public interface RiskEngineService {

    IngestionResult ingest(TransactionEvent event);

    List<IngestionResult> ingestBatch(List<TransactionEvent> events);

    /** 使用当前生效规则集评分。 */
    RiskScore score(String address);

    /**
     * 使用指定历史版本规则集评分（基于当前数据快照），用于规则更新后复现历史结论。
     *
     * @throws IllegalArgumentException 规则版本不存在或地址非法
     */
    RiskScore scoreWithVersion(String address, String ruleSetVersion);

    /** 查询得分 >= threshold 的全部钱包告警（结果按地址排序）。threshold 必须在 [0,100]。 */
    List<RiskAlert> alerts(int threshold);

    /** 发布新规则集（版本号不可与历史版本重复）。 */
    String publishRules(RiskRuleSet ruleSet);

    /**
     * 按名称发布内置规则集（v1 / v2），便于通过 HTTP 演示规则更新。
     * @throws IllegalArgumentException 未知名称
     */
    String publishBuiltIn(String name);

    /** 切回已发布过的历史规则版本（不新增版本）。 */
    String activateVersion(String version);

    /** 重置引擎事件数据（规则集保留，测试 / 本地演示用）。 */
    void reset();
}
