package com.github.xingjianluo.onchainriskenginejava.rules;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;

import java.util.Optional;

/**
 * 风险规则。实现必须是纯函数：只依赖入参钱包地址与不可变资金图快照，
 * 不允许读写可变全局状态，以保证任意处理顺序下结论一致、可复现。
 */
public interface RiskRule {

    /** 规则码，全局唯一且稳定（作为依据链与历史复现的标识）。 */
    String code();

    /** 规则名。 */
    String name();

    /** 规则集版本（规则定义发生变化时必须递增/变更）。 */
    String version();

    /** 对目标钱包评分，未命中返回 empty。 */
    Optional<Evidence> evaluate(String address, WalletGraph graph);
}
