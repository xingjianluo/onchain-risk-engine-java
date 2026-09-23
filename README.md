# 本地链上交易风控评分引擎（Java / Spring Boot）

一个**纯本地、内存态**的链上交易风控评分引擎：用本地模拟事件流驱动钱包资金关系分析与风险判定，
在**并发摄入、重复投递、乱序到达、规则热更新**等条件下给出**一致、可复现、可解释**的结论。

- Java 21 + Spring Boot 3.4，无外部数据库 / 消息队列依赖；
- 金额使用 `BigDecimal`，地址规范化（小写、trim）；
- 所有拒绝都返回**可区分的原因码**，不做任何静默丢弃；
- 单测日志打印**输入事件**与**判定依据链**。

---

## 1. 构建与本地验证

```bash
# 编译 + 全量测试（15 个测试：校验 / 幂等并发 / 确定性 / 端到端集成）
mvn test

# 启动应用（默认不跑模拟流）
mvn spring-boot:run

# 启动并自动执行一遍本地模拟事件流（日志打印每条输入事件、接受/拒绝原因、评分依据、告警）
mvn spring-boot:run -Dspring-boot.run.arguments="--risk.engine.simulate-on-startup=true"
```

模拟数据集是固定确定的，包含：正常多对手转账、只进不出的沉淀地址、
相同 `eventId` 的重复投递、相同 `txHash` 的业务重复，以及四类非法事件
（缺字段 / 非法地址 / 自发自收 / 金额越界）。

### HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/risk/events` | 摄入单条事件；重复/非法返回 `accepted=false` + 明确 `reason` |
| POST | `/api/risk/events/batch` | 批量摄入 |
| GET  | `/api/risk/score/{address}` | 查询钱包评分与依据链（含快照版本与事件集哈希） |
| GET  | `/api/risk/alerts?threshold=60` | 查询得分 ≥ 阈值的全部钱包告警；阈值必须在 [0,100] |
| POST | `/api/admin/rules/publish-builtin?name=v2` | 发布内置规则集（v1 / v2），演示规则更新 |
| POST | `/api/admin/reset` | 清空已摄入事件（规则集保留） |

示例：

```bash
curl -X POST localhost:8080/api/risk/events -H 'Content-Type: application/json' -d '{
  "eventId":"e1","txHash":"t1","timestamp":1700000000000,
  "from":"0xbbbb...bbbb","to":"0xaaaa...aaaa","amount":100,"asset":"ETH"}'

curl "localhost:8080/api/risk/score/0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
curl "localhost:8080/api/risk/alerts?threshold=60"
```

事件字段：`eventId`（投递唯一键，幂等用）、`txHash`（业务交易唯一键）、
`timestamp`(epoch millis)、`from`、`to`（`0x` + 40 位十六进制）、`amount`（正数，≤ 10^9）、`asset`。

---

## 2. 评分模型

### 2.1 总体流程

```
事件摄入
  └─ 规范化（地址 trim + 小写）
      └─ EventValidator 合法性校验（失败 → 明确原因码，不进存储）
          └─ EventStore 原子判重落库（eventId / txHash）
查询/评分
  └─ 取事件不可变快照 + 取当前不可变规则集
      └─ WalletGraph 规范化排序构图（与摄入顺序无关）
          └─ ScoringEngine 逐规则求值（纯函数）
              └─ 依据链按规则码排序、分值累加并在 [0,100] 封顶
```

### 2.2 规则集 v1（`builtin-v1`）

每条规则是一个**不可变参数对象 + 纯函数** `evaluate(address, graph)`，
只读取入参的不可变资金图，不碰任何可变状态。命中返回一条 `Evidence`
（规则码、规则版本、分值、人可读理由、支撑事实 `facts`）。

| 规则码 | 名称 | v1 参数 / 分值 | 含义 |
| --- | --- | --- | --- |
| R001_HIGH_OUTGOING_VOLUME | 大额转出 | 累计转出 ≥ 100，**30** | 钱包累计转出额异常大 |
| R002_COUNTERPARTY_FANOUT | 交易对手扩散 | 不重复转出对手 ≥ 5，**20** | 向外撒钱 / 归集特征 |
| R003_RISKY_COUNTERPARTY | 高风险对手往来 | 命中风险名单即 **25** | 与名单地址有直接资金往来（转入或转出，按对手去重，仅计一次分） |
| R004_RAPID_PASS_THROUGH | 快速过账 | 60 分钟内转出/转入 ≥ 0.8 且非原路，**15** | 资金快速中转（混币 / 归集打散） |
| R005_INFLOW_OUTFLOW_IMBALANCE | 流入流出失衡 | 累计流入 ≥ 50 且流出/流入 < 0.2，**10** | 只进不出的沉淀地址 |

- 最终分 `score = min(100, Σ 命中规则分值)`；`rawPoints` 保留封顶前原始分。
- 示例（内置模拟数据）：钱包 A 累计转出 128、8 个对手、含风险地址 DARK1
  → R001(30)+R002(20)+R003(25) = **75 分**，触发 60 分阈值告警；
  沉淀地址 COLLECTOR → 仅 R005 = 10 分。

### 2.3 可解释性

评分响应里的 `evidences` 即依据链，按规则码稳定排序，例如：

```json
{
  "ruleCode": "R003_RISKY_COUNTERPARTY",
  "ruleName": "高风险对手往来",
  "points": 25,
  "rationale": "与 1 个风险名单地址发生直接资金往来: 0xdead...dead",
  "facts": {
    "riskyCounterparties": ["0xdead...dead"],
    "riskyCounterpartyCount": 1,
    "relatedTransactions": [ {"direction":"OUT","txHash":"tx-009","amount":"20", ...} ]
  }
}
```

`facts` 只包含可从事件快照直接推导出的确定性事实，可人工审计、可对账。

### 2.4 规则更新与历史复现

- 规则集是**不可变对象**（版本号 + 规则列表），更新 = 整体发布一个新版本（`v1` → `v2`，
  v2 收紧了大额转出阈值 100→80、扩散阈值 5→4、过账窗口 60min→30min）。
- 历史版本在 `RuleRegistry` 中**永久保留且不可覆盖**；重复发布同版本号会被拒绝。
- `GET /api/risk/score/{address}` 使用当前版本；
  服务层 `scoreWithVersion(address, version)` 可用**任意历史版本**重算——
  结合响应中的 `snapshot.ruleSetVersion` 与 `snapshot.eventsHash`，
  历史结论可在相同事件集 + 相同规则版本下精确复现。

---

## 3. 幂等与并发语义

### 3.1 幂等摄入（重复 / 乱序 / 并发不错账）

- 存储层写入在单把 `ReentrantLock` 下完成
  **“eventId 判重 → txHash 判重 → 落库”原子动作**：
  - 相同 `eventId` 的重复投递（即使来自不同线程）：只有第一次被接受，
    其余返回 `DUPLICATE_EVENT_ID`；
  - `eventId` 不同但 `txHash` 相同（业务重复 / 重放）：返回 `DUPLICATE_TX_HASH`；
  - 两种重复原因彼此可区分，且都带上冲突的键值，绝不静默丢弃。
- 读取返回不可变拷贝（`List.copyOf`），写入不影响进行中的读取。
- 并发测试验证：16 线程同时投递同一事件，**恰好一次**接受；
  200 线程各写不同事件无丢失；3 轮重复数据被 12 线程打乱并发摄入后，
  全部钱包评分与顺序摄入**逐字段相等**。

### 3.2 乱序到达的一致性

评分不依赖摄入顺序，而依赖数据本身：

- 构图前事件按确定性主键排序：
  `(timestamp, txHash, from, to, amount, asset)`；
- 评分是 `(address, WalletGraph, RiskRuleSet)` 的**纯函数**；
- 快照哈希 `eventsHash` 对排序后的规范化事件行计算 SHA-256。

因此同一批数据无论正序、逆序、随机乱序、并发摄入，都产生相同的图、相同的分值、
相同的依据链与相同的 `eventsHash`（测试 `ScoringDeterminismTest` 与
`RiskEngineIntegrationTest#sameDataDifferentOrderSameConclusion` 覆盖）。

### 3.3 规则更新与查询并发的快照一致性

一次评分 / 告警查询在同一个逻辑动作内固定两件东西：

1. 事件不可变快照（`store.snapshotEvents()`）；
2. 当前规则集不可变引用（`AtomicReference` 原子交换）。

规则发布是引用的原子切换，所以进行中的查询要么完整使用旧版本、
要么完整使用新版本，不会出现“新规则 + 半截数据”的撕裂结果；
同一次告警查询的所有钱包共享同一个 `SnapshotInfo`（规则版本、事件数、事件哈希）。
集成测试在 8 线程 × 50 次查询与规则更新并发下校验“版本与分值始终自洽”。

### 3.4 非法数据不进统计

校验顺序固定（保证同一输入永远得到同一失败原因）：

1. `MISSING_FIELD`：eventId / txHash / from / to / amount / asset / timestamp 任一缺失；
2. `INVALID_ADDRESS`：地址不是 `0x` + 40 位十六进制；
3. `SELF_TRANSACTION`：from == to；
4. `AMOUNT_OUT_OF_RANGE`：金额 ≤ 0 或 > 10^9；
5. `TIMESTAMP_OUT_OF_RANGE`：时间戳 ≤ 0 或超过当前时间 + 1 天。

非法事件在校验层即被拒绝，不会进入存储、图与统计；HTTP 层统一映射为 400。

---

## 4. 代码结构

```
model/          领域类型（TransactionEvent / IngestionResult / RejectReason /
                Evidence / RiskScore / RiskAlert / SnapshotInfo）
validation/     EventValidator：输入合法性校验
store/          EventStore + InMemoryEventStore：锁内原子判重落库、不可变快照
engine/         WalletGraph（规范化排序的不可变资金图）、ScoringEngine（纯函数评分）
rules/          RiskRule / RiskRuleSet / RuleRegistry（不可变规则集、原子发布、版本留存）
rules/builtin/  R001..R005 五条内置规则 + v1/v2 工厂
service/        RiskEngineService：规范化 → 校验 → 摄入 → 快照 → 构图 → 评分/告警编排
web/            HTTP 控制器与全局异常处理
sim/            LocalEventSimulator：确定性本地模拟事件流
config/         配置属性与启动模拟钩子
src/test/       校验、幂等并发、确定性、端到端集成共 15 个测试
```

## 5. 测试一览

| 测试类 | 覆盖内容 |
| --- | --- |
| `EventValidatorTest` | 缺字段、非法地址、自发自收、金额/时间越界的**可区分原因** |
| `InMemoryEventStoreTest` | eventId/txHash 判重；16 线程重复投递恰好一次接受；200 并发写入无丢失 |
| `ScoringDeterminismTest` | 逆序/随机乱序评分逐字段一致；v1/v2 参数差异；R004 窗口边界 |
| `RiskEngineIntegrationTest` | 顺序 vs 乱序端到端一致、重复与非法不计数、12 线程并发（含重复）结论一致、规则更新并发快照自洽与历史版本复现、阈值告警与非法阈值拒绝 |

测试日志（`mvn test` 输出）包含每条输入事件、`accepted/reason` 判定以及命中规则的
分值、理由与 facts。
