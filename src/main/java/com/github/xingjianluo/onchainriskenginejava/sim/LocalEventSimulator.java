package com.github.xingjianluo.onchainriskenginejava.sim;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RiskAlert;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.service.RiskEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地模拟事件流。
 *
 * <p>固定、确定的数据集（数据集本身永远按时间顺序定义），包含：</p>
 * <ul>
 *   <li>正常多对手转账（A 向 6 个对手转出，含向风险地址 DARK1 转账，并在收款后快速过账）；</li>
 *   <li>只进不出的沉淀地址 COLLECTOR；</li>
 *   <li>重复投递（相同 eventId）与业务重复（相同 txHash、不同 eventId）；</li>
 *   <li>乱序事件（{@code outOfOrderView()} 打乱顺序后摄入，结论必须一致）；</li>
 *   <li>非法事件：缺字段、非法地址、自发自收、金额越界。</li>
 * </ul>
 */
@Component
public class LocalEventSimulator {

    private static final Logger log = LoggerFactory.getLogger(LocalEventSimulator.class);

    public static final String A = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    public static final String B = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    public static final String C = "0xcccccccccccccccccccccccccccccccccccccccc";
    public static final String D = "0xdddddddddddddddddddddddddddddddddddddddd";
    public static final String E = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    public static final String F = "0xffffffffffffffffffffffffffffffffffffffff";
    public static final String G = "0x1111111111111111111111111111111111111111";
    /** 风险名单地址（由 v1/v2 规则集以参数形式携带）。 */
    public static final String DARK1 = "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead";
    public static final String COLLECTOR = "0x2222222222222222222222222222222222222222";

    private final RiskEngineService service;

    public LocalEventSimulator(RiskEngineService service) {
        this.service = service;
    }

    /** 固定的合法事件数据集（按时间顺序定义）。 */
    public List<TransactionEvent> dataset() {
        long t = 1_700_000_000_000L;
        List<TransactionEvent> events = new ArrayList<>();
        // A 先收到 100，随后 50 分钟内向 8 个对手（含风险地址 DARK1）转出 128（含部分自有资金）：
        // v1: R001 大额转出(128>=100, 30) + R002 扩散(8>=5, 20) + R003 风险对手(DARK1, 25) = 75；
        //     另有两笔合计 33 在收款后 10 分钟内转出（>=0.8*100? 否，单笔15/18），R004 不命中
        events.add(tx("e-001", "tx-001", t, B, A, "100", "ETH"));
        events.add(tx("e-002", "tx-002", t + 5 * 60_000L, A, C, "18", "ETH"));
        events.add(tx("e-003", "tx-003", t + 8 * 60_000L, A, D, "15", "ETH"));
        events.add(tx("e-004", "tx-004", t + 15 * 60_000L, A, E, "15", "ETH"));
        events.add(tx("e-005", "tx-005", t + 20 * 60_000L, A, F, "15", "ETH"));
        events.add(tx("e-006", "tx-006", t + 25 * 60_000L, A, G, "15", "ETH"));
        events.add(tx("e-007", "tx-007", t + 35 * 60_000L,
                A, "0x3333333333333333333333333333333333333333", "15", "ETH"));
        events.add(tx("e-008", "tx-008", t + 45 * 60_000L,
                A, "0x4444444444444444444444444444444444444444", "15", "ETH"));
        events.add(tx("e-009", "tx-009", t + 50 * 60_000L, A, DARK1, "20", "ETH"));

        // COLLECTOR：从 3 个来源累计收入 120，无任何转出 -> R005 命中（10 分，低风险沉淀）
        events.add(tx("e-010", "tx-010", t + 60 * 60_000L, B, COLLECTOR, "40", "ETH"));
        events.add(tx("e-011", "tx-011", t + 61 * 60_000L, C, COLLECTOR, "40", "ETH"));
        events.add(tx("e-012", "tx-012", t + 62 * 60_000L, D, COLLECTOR, "40", "ETH"));

        // D 收到 A 的 16 之后无动作，低风险普通地址
        return List.copyOf(events);
    }

    /**
     * 含投递异常与非法输入的完整事件序列（基于 {@link #dataset()} 追加）：
     * 一个完全相同的重复投递、一个同 txHash 不同 eventId 的业务重复、
     * 以及四类非法事件。
     */
    public List<TransactionEvent> datasetWithDuplicatesAndInvalid() {
        List<TransactionEvent> all = new ArrayList<>(dataset());
        TransactionEvent first = dataset().get(0);
        // 相同 eventId 重复投递
        all.add(new TransactionEvent(first.eventId(), first.txHash(), first.timestamp(),
                first.from(), first.to(), first.amount(), first.asset()));
        // 不同 eventId、相同 txHash 的业务重复
        all.add(tx("e-dup-txhash", "tx-001", first.timestamp(), first.from(), first.to(),
                first.amount().toPlainString(), first.asset()));
        // 非法：金额为 0
        all.add(tx("e-bad-amount", "tx-bad-1", first.timestamp(), A, B, "0", "ETH"));
        // 非法：地址格式错误
        all.add(new TransactionEvent("e-bad-addr", "tx-bad-2", first.timestamp(),
                "0x123", B, new BigDecimal("1"), "ETH"));
        // 非法：自发自收
        all.add(tx("e-self", "tx-bad-3", first.timestamp(), A, A, "1", "ETH"));
        // 非法：缺失资产字段
        all.add(new TransactionEvent("e-missing", "tx-bad-4", first.timestamp(),
                A, B, new BigDecimal("1"), null));
        return List.copyOf(all);
    }

    /**
     * 将数据集确定性地打乱（固定步长置换，不依赖随机数，便于本地复现），
     * 模拟乱序到达。
     */
    public List<TransactionEvent> outOfOrderView(List<TransactionEvent> events) {
        List<TransactionEvent> shuffled = new ArrayList<>(events.size());
        int n = events.size();
        // 固定置换：先奇数位后偶数位，再整体反转，结果确定且与原序显著不同
        for (int i = 1; i < n; i += 2) {
            shuffled.add(events.get(i));
        }
        for (int i = 0; i < n; i += 2) {
            shuffled.add(events.get(i));
        }
        java.util.Collections.reverse(shuffled);
        return List.copyOf(shuffled);
    }

    /** 顺序摄入数据集并逐条打印输入事件与判定依据，随后打印评分与告警。 */
    public List<IngestionResult> runSequential() {
        log.info("================ 本地模拟事件流开始（顺序摄入） ================");
        List<IngestionResult> results = new ArrayList<>();
        for (TransactionEvent e : datasetWithDuplicatesAndInvalid()) {
            log.info("输入事件 | eventId={} txHash={} ts={} {} -> {} amount={} {}",
                    e.eventId(), e.txHash(), e.timestamp(), e.from(), e.to(),
                    e.amount() == null ? "null" : e.amount().toPlainString(), e.asset());
            results.add(service.ingest(e));
        }
        printConclusions();
        log.info("================ 本地模拟事件流结束 ================");
        return List.copyOf(results);
    }

    /** 摄入后输出关键钱包评分与告警，展示判定依据链。 */
    public void printConclusions() {
        for (String address : List.of(A, COLLECTOR, DARK1, B)) {
            RiskScore score = service.score(address);
            log.info("结论 | address={} score={} raw={} hits={}",
                    address, score.score(), score.rawPoints(),
                    score.evidences().stream().map(ev -> ev.ruleCode()).toList());
        }
        List<RiskAlert> alerts = service.alerts(60);
        log.info("阈值告警(>=60) | {}", alerts.stream().map(RiskAlert::address).toList());
    }

    private static TransactionEvent tx(String eventId, String txHash, long ts,
                                      String from, String to, String amount, String asset) {
        return new TransactionEvent(eventId, txHash, ts, from, to, new BigDecimal(amount), asset);
    }
}
