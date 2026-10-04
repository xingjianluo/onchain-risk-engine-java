# 本地链上交易风控评分引擎

基于 Spring Boot 3.4 / Java 21 的本地链上交易风控评分引擎。使用本地模拟事件流驱动钱包资金流向
分析与风险判定，在**并发摄入、重复投递、乱序到达、规则热更新**等场景下保持结论一致、可解释、可复现。

## 1. 总体结构

```
model/    TransactionEvent / IngestResult / RejectionReason（输入与可区分拒绝原因）
ingest/   EventValidator（字段与范围校验）、EventIngestionService（校验+两级幂等+归一化）
store/    EventStore（线程安全事件存储，eventId+txHash 双键原子判重，确定性快照）
score/    RiskRuleSet（版本化不可变规则）、RuleSetProvider（追加式版本注册表 + volatile 当前引用）
          RiskScoringService（确定性评分 + 文字依据链 + 结构化命中 + 事件集合指纹）
          RuleHit（结构化判定依据）、EventSetFingerprint（SHA-256 集合指纹）
          RuleVersionConflictException（版本冲突，HTTP 409）
alert/    AlertService（阈值告警查询，快照语义）
api/      REST 控制器（摄入 / 评分 / 告警 / 规则与历史版本）
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
- **可解释依据链（人读）**：`RiskScoreResult.evidence` 逐条记录命中规则、加减分、
  事件 ID、交易哈希、金额、方向与对手方集合，并记录使用的规则集版本，例如
  `LARGE_AMOUNT(+20): event=evt-1 tx=0xtx1 50000 ETH 0xaa..->0x00..01 >= threshold 10000`。
- **结构化判定依据（机读，新增）**：`RiskScoreResult.ruleHits` 以结构化记录给出
  每条命中——规则编码（`LARGE_AMOUNT`/`FAN_OUT`/`FAN_IN`）、资金方向
  （`OUTGOING`/`INCOMING`）、加分、相关金额与资产、对手方列表、关联的
  `eventIds`/`txHashes`，可直接用于自动对账与比对，无需解析文字。
- **事件集合指纹（新增）**：`RiskScoreResult.eventSetFingerprint` 是"本次结论实际
  依据的事件子集"（该地址作为收/付方参与的事件）的 SHA-256。每条事件先序列化为
  规范行（字段均已归一化），去重排序后再哈希，因此**同一批事件无论以什么顺序
  到达、重复投递多少次，指纹与结构化依据逐字节一致；事件集合只要有增删改，
  指纹必然变化**。
- 告警列表按分数降序、地址升序排列，保证多次查询输出稳定。

规则集是不可变 record，"发布规则"是向注册表追加一个新版本（`PUT /api/rules`）。
历史结论可随时按版本号复算：`GET /api/scores/{address}?version=N`，
或在代码中用 `scoreOnSnapshot(address, snapshot, provider.version(N))`。

## 3. 幂等与并发语义

### 3.1 摄入幂等（两级去重）

- **投递级**：唯一键 `eventId`。同一个 `eventId` 无论被多少线程、投递多少次，
  最多计价一次，且先到记录不会被后到覆盖。
- **交易级（新增）**：唯一键 `txHash`（归一化为小写）。业务上的"同一笔链上交易"
  即使换了投递编号、地址大小写写法不同、几十个线程同时灌入多个副本，
  也只允许一条事件落库计价。两个唯一键的"检查 + 写入"在 `EventStore` 内
  同一把锁中原子完成，并发下有且仅有一个副本被接受。
- 写入前先经 `EventValidator` 校验。被拒绝的事件不会进入统计，且返回**可区分**的拒绝原因
  （`RejectionReason`）：

  | 原因 | 含义 |
  | --- | --- |
  | DUPLICATE_EVENT | 投递编号重复：eventId 已接受过 |
  | DUPLICATE_TRANSACTION | 交易本身重复：eventId 是新的，但 txHash 已被其他事件计价过 |
  | MISSING_EVENT_ID / MISSING_TX_HASH / MISSING_AMOUNT / MISSING_ASSET / MISSING_TIMESTAMP | 必填字段缺失 |
  | INVALID_FROM_ADDRESS / INVALID_TO_ADDRESS | 非 `0x` + 40 位十六进制地址 |
  | NON_POSITIVE_AMOUNT / AMOUNT_OUT_OF_RANGE | 金额 ≤ 0 或超过 10 亿上限 |
  | TIMESTAMP_OUT_OF_RANGE | 时间早于 2009-01-03 或超过当前时间 24 小时 |

  两种重复的 `detail` 文案也不同（分别含 `eventId already accepted` /
  `txHash already accepted under another eventId`），调用方可以明确区分。
  绝不静默丢弃：REST 响应与日志都带有 `rejectionReason` 与 `detail`。

### 3.2 乱序到达

- 存储不依赖到达顺序；`snapshot()` 始终按 `(timestamp, eventId)` 做确定性排序后返回。
- 评分只基于集合统计（交易对手用 `TreeSet` 去重），因此同一批数据无论按什么顺序摄入，
  得分与依据链逐字节一致（见 `ConcurrentIngestionTest` 中 5 种乱序顺序的断言）。

### 3.3 规则发布与查询的快照语义

- `RuleSetProvider` 持有当前规则集的 `volatile` 引用，发布是一次原子引用赋值；
  进行中的评分/告警查询持有的是查询开始时拿到的不可变规则集对象，
  要么完整使用旧版本、要么完整使用新版本，绝不新旧混用。
- `AlertService` 一次查询固定使用"一个规则集引用 + 一个事件快照"，
  查询期间的写入/规则发布只影响下一次查询（见 `RuleUpdateAndAlertTest`
  中 200 次查询与规则发布并发执行的版本自洽断言，以及
  `RuleVersionRegistryTest` 中 50 轮发布与 500 次评分并发执行的断言）。

### 3.4 规则版本留存（新增）

- **每个发布过的版本都留存在系统内**：`RuleSetProvider` 内置版本注册表
  （`version -> 不可变规则集`），只追加、不覆盖、不删除；默认规则即 v1。
- **追加式发布**：新版本号必须严格大于已发布的最新版本号。用同一个版本号
  再次发布（无论内容是否相同）或回退版本号，都会以
  `RuleVersionConflictException` 拒绝（REST 层映射为 **409**），历史内容不受污染。
- **按版本复算**：只凭版本号即可重现当时的评分与依据——
  `GET /api/scores/{address}?version=N`，中间更新过多少轮都不影响。
- **累积几百个版本也不拖慢查询**：当前评分/告警查询只读 `volatile` 当前引用，
  完全不触碰版本表；历史版本按版本号 O(1) 取回
  （`RuleVersionRegistryTest` 实测 300 个版本下 200 轮当前评分约几十毫秒）。

## 4. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/events` | 摄入单条事件，返回 `{accepted, rejectionReason, detail}` |
| POST | `/api/events/batch` | 批量摄入 |
| GET  | `/api/scores/{address}` | 按当前规则集查询钱包评分；非法地址返回 400 |
| GET  | `/api/scores/{address}?version=N` | 按历史版本 N 的规则集复算当时结论；版本不存在返回 400 |
| GET  | `/api/alerts` | 查询达到告警线的全部钱包 |
| GET  | `/api/rules` | 当前生效规则集 |
| GET  | `/api/rules/versions` | 全部已发布版本号（升序） |
| GET  | `/api/rules/{version}` | 取回任一历史版本规则集；版本不存在返回 400 |
| PUT  | `/api/rules` | 追加发布新版本规则集（非法参数 400；版本号重复/回退 409） |

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

## 5. 与旧版本的兼容范围

**保持不变（旧调用方无需改动）：**

- 所有既有端点的路径、方法、请求体格式不变；`GET /api/scores/{address}`
  不带 `version` 参数时行为与之前完全一致。
- 摄入响应仍是 `{accepted, rejectionReason, detail}`；同一 `eventId` 重复投递
  仍返回 `DUPLICATE_EVENT`，文案不变。
- 评分响应的既有字段 `address` / `score` / `ruleSetVersion` / `evidence`
  含义与格式不变，`evidence` 文字依据链逐字保留；`ruleHits` 与
  `eventSetFingerprint` 为**新增字段**，不读取即不受影响。
- `GET /api/rules` 与 `PUT /api/rules` 的报文结构不变。

**行为变化（需要知晓）：**

- 摄入去重口径从"仅 eventId"收紧为"eventId + txHash 双键"：**换投递编号
  重送同一笔链上交易（txHash 相同）现在会被拒绝**（`DUPLICATE_TRANSACTION`），
  不再被当成新交易计价。若旧调用方存在"同一 txHash 换 eventId 重送"的用法，
  升级后这些投递会收到拒绝响应（此前是静默计价）。
- `PUT /api/rules` 现在要求版本号严格递增：同版本号重复发布或回退会收到
  **409 RULE_VERSION_CONFLICT**（此前是静默覆盖当前规则）。

**旧数据处理：**

- 事件与规则版本均为内存态，进程重启即清空，无需迁移；重启后按正常流程
  重新摄入事件即可，历史规则版本需按版本号递增顺序重新发布。
- 运行中升级不影响已接受事件：双键判重对存量与新事件一视同仁。

## 6. 本地验证方法

### 6.1 运行测试（推荐先看日志）

```bash
mvn test
```

测试覆盖：

- `EventIngestionServiceTest`：合法/非法输入、每种可区分拒绝原因、地址大小写归一化；
- `TransactionLevelDedupTest`（新增）：同一 txHash 换投递编号只计一次、
  txHash/地址大小写变体仍判重、32 线程 × 20 份并发灌同一笔交易恰好接受 1 条、
  并发混合重复下 `DUPLICATE_EVENT` 与 `DUPLICATE_TRANSACTION` 数量与文案可区分；
- `ConcurrentIngestionTest`：8 线程并发摄入 200 个唯一事件 × 3 份乱序副本，
  断言接受数、重复拒绝数、存储大小，并对 5 种不同摄入顺序断言评分与依据链完全一致；
- `RuleVersionRegistryTest`（新增）：每个发布版本留存且可按版本号复算、
  同版本号重复发布/版本回退被拒且历史内容不变、300 个版本累积后任意历史版本
  可复算且当前评分不变慢、50 轮发布与 500 次评分并发时每次评分版本自洽；
- `RuleUpdateAndAlertTest`：规则更新后旧版本结论按版本复现、200 次告警查询与
  规则更新并发时每次查询版本自洽、非法规则被拒、告警排序稳定；
- `StructuredEvidenceTest`（新增）：乱序 + 重复投递下指纹与结构化依据逐字节一致、
  事件集合增/删/改后指纹必变、ruleHits 的规则/方向/对手方/金额/关联交易字段齐全、
  既有字段与文字依据链保持原格式；
- `ApiSmokeTest`：REST 全链路（摄入、重复拒绝、评分、告警、规则更新、非法地址 400）。

单测日志中会打印**输入事件**（`ingest input event: ...`）与**判定依据**
（`score ... evidence=... fingerprint=...`、`ingest rejected ... reason=...`）。查看完整日志：

```bash
mvn test -Dtest=TransactionLevelDedupTest
mvn test -Dtest=RuleVersionRegistryTest
mvn test -Dtest=StructuredEvidenceTest
mvn test -Dtest=ConcurrentIngestionTest
```

### 6.2 启动应用 + 本地模拟事件流

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

# 发布 v2 规则后，按历史版本复算 / 查看版本留存
curl -s -XPUT localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "version":2,"largeAmountThreshold":100000,"fanOutThreshold":5,"fanInThreshold":5,
  "largeAmountWeight":10,"fanOutWeight":40,"fanInWeight":10,"alertScoreThreshold":50}'
curl -s localhost:8080/api/rules/versions          # [1,2]
curl -s localhost:8080/api/rules/1                 # 取回 v1 历史规则
curl -s "localhost:8080/api/scores/0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?version=1"  # 按 v1 复算
# 同版本号重复发布 -> 409 RULE_VERSION_CONFLICT
curl -s -o /dev/null -w '%{http_code}\n' -XPUT localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "version":2,"largeAmountThreshold":1,"fanOutThreshold":5,"fanInThreshold":5,
  "largeAmountWeight":10,"fanOutWeight":40,"fanInWeight":10,"alertScoreThreshold":50}'
```
