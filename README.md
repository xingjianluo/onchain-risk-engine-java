# 本地链上交易风控评分引擎

基于 Spring Boot 3.4 / Java 21 的本地链上交易风控评分引擎。使用本地模拟事件流驱动钱包资金流向
分析与风险判定，在**并发摄入、重复投递、乱序到达、规则热更新**等场景下保持结论一致、可解释、可复现。

## 1. 总体结构

```
model/    TransactionEvent / IngestResult / RejectionReason（输入与拒绝原因）
ingest/   EventValidator（字段与范围校验）、EventIngestionService（校验+幂等+归一化）
store/    EventStore（线程安全事件存储，确定性快照）
score/    RiskRuleSet（版本化不可变规则）、RuleSetProvider（volatile 引用切换）
          RiskScoringService（确定性评分 + 依据链）
alert/    AlertService（阈值告警查询，快照语义）
api/      REST 控制器（摄入 / 评分 / 告警 / 规则）
LocalEventStreamSimulator  启动时本地模拟事件流（默认关闭）
```

## 2. 评分模型

评分完全由"已接受事件集合"和"规则集"决定，是纯函数计算，**与事件到达顺序无关**。

默认规则（`RiskRuleSet.defaultRules()`，v1）：

| 规则 | 触发条件 | 加分 |
| --- | --- | --- |
| LARGE_AMOUNT | 该钱包参与的单笔转账金额 ≥ 10000 | 每笔 +20 |
| FAN_OUT | 对外转账的不同对手方数 ≥ 5 | +30 |
| FAN_IN | 向该钱包转入的不同对手方数 ≥ 5 | +20 |

- 总分封顶 100；告警线默认 50（`alertScoreThreshold`，分数 ≥ 阈值即告警）。
- 地址校验通过后统一归一化为小写，资产代码大写、金额去尾零，因此
  `0xAa...aa` 与 `0xaa...aa` 视为同一钱包。
- **可解释依据链**：`RiskScoreResult.evidence` 逐条记录命中规则、加减分、
  事件 ID、交易哈希、金额、方向与对手方集合，并记录使用的规则集版本，例如
  `LARGE_AMOUNT(+20): event=evt-1 tx=0xtx1 50000 ETH 0xaa..->0x00..01 >= threshold 10000`。
- 告警列表按分数降序、地址升序排列，保证多次查询输出稳定。

规则集是不可变 record，"更新规则"是整体替换引用（`PUT /api/rules`，参数须通过范围校验）。
历史结论可用保存的旧规则集引用通过 `scoreOnSnapshot(address, snapshot, oldRules)` 原样复现。

## 3. 幂等与并发语义

### 3.1 摄入幂等

- 唯一键为 `eventId`；`EventStore` 基于 `ConcurrentHashMap.putIfAbsent` 原子写入，
  同一个 `eventId` 无论被多少线程、投递多少次，**最多计价一次**，且先到记录不会被后到覆盖。
- 写入前先经 `EventValidator` 校验。被拒绝的事件不会进入统计，且返回**可区分**的拒绝原因
  （`RejectionReason`）：

  | 原因 | 含义 |
  | --- | --- |
  | DUPLICATE_EVENT | eventId 已接受过（重复投递） |
  | MISSING_EVENT_ID / MISSING_TX_HASH / MISSING_AMOUNT / MISSING_ASSET / MISSING_TIMESTAMP | 必填字段缺失 |
  | INVALID_FROM_ADDRESS / INVALID_TO_ADDRESS | 非 `0x` + 40 位十六进制地址 |
  | NON_POSITIVE_AMOUNT / AMOUNT_OUT_OF_RANGE | 金额 ≤ 0 或超过 10 亿上限 |
  | TIMESTAMP_OUT_OF_RANGE | 时间早于 2009-01-03 或超过当前时间 24 小时 |

  绝不静默丢弃：REST 响应与日志都带有 `rejectionReason` 与 `detail`。

### 3.2 乱序到达

- 存储不依赖到达顺序；`snapshot()` 始终按 `(timestamp, eventId)` 做确定性排序后返回。
- 评分只基于集合统计（交易对手用 `TreeSet` 去重），因此同一批数据无论按什么顺序摄入，
  得分与依据链逐字节一致（见 `ConcurrentIngestionTest` 中 5 种乱序顺序的断言）。

### 3.3 规则更新与查询的快照语义

- `RuleSetProvider` 持有规则集的 `volatile` 引用，更新是一次原子引用赋值；
  进行中的评分/告警查询持有的是查询开始时拿到的不可变规则集对象，
  要么完整使用旧规则、要么完整使用新规则，不会出现"半套新规则"。
- `AlertService` 一次查询固定使用"一个规则集引用 + 一个事件快照"，
  查询期间的写入/规则更新只影响下一次查询（见 `RuleUpdateAndAlertTest`
  中 200 次查询与规则更新并发执行的版本自洽断言）。

## 4. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/events` | 摄入单条事件，返回 `{accepted, rejectionReason, detail}` |
| POST | `/api/events/batch` | 批量摄入 |
| GET  | `/api/scores/{address}` | 查询钱包评分与依据链；非法地址返回 400 |
| GET  | `/api/alerts` | 查询达到告警线的全部钱包 |
| GET  | `/api/rules` | 当前生效规则集 |
| PUT  | `/api/rules` | 发布新版本规则集（非法参数返回 400） |

事件 JSON 示例：

```json
{
  "eventId": "evt-1",
  "txHash": "0xtx1",
  "fromAddress": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "toAddress": "0x0000000000000000000000000000000000000001",
  "amount": 50000,
  "asset": "ETH",
  "timestamp": "2026-09-01T00:01:00Z"
}
```

## 5. 本地验证方法

### 5.1 运行测试（推荐先看日志）

```bash
mvn test
```

测试覆盖：

- `EventIngestionServiceTest`：合法/非法输入、每种可区分拒绝原因、地址大小写归一化；
- `ConcurrentIngestionTest`：8 线程并发摄入 200 个唯一事件 × 3 份乱序副本，
  断言接受数、重复拒绝数、存储大小，并对 5 种不同摄入顺序断言评分与依据链完全一致；
- `RuleUpdateAndAlertTest`：规则更新后旧版本结论按版本复现、200 次告警查询与
  规则更新并发时每次查询版本自洽、非法规则被拒、告警排序稳定；
- `ApiSmokeTest`：REST 全链路（摄入、重复拒绝、评分、告警、规则更新、非法地址 400）。

单测日志中会打印**输入事件**（`ingest input event: ...`）与**判定依据**
（`score ... evidence=...`、`ingest rejected ... reason=...`）。查看完整日志：

```bash
mvn test -Dtest=ConcurrentIngestionTest
mvn test -Dtest=RuleUpdateAndAlertTest
```

### 5.2 启动应用 + 本地模拟事件流

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--risk.simulator.enabled=true
```

启动时模拟器会生成确定性的一批事件，**故意打乱顺序并重复投递前 3 条**，
通过并行流并发摄入，随后打印接受/拒绝统计与告警结论。多次运行结论完全相同。

手动调用 API：

```bash
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-1","txHash":"0xtx1",
  "fromAddress":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "toAddress":"0x0000000000000000000000000000000000000001",
  "amount":50000,"asset":"ETH","timestamp":"2026-09-01T00:01:00Z"}'
curl -s localhost:8080/api/alerts
curl -s localhost:8080/api/scores/0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
```
