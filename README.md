# 本地链上交易风控评分引擎

基于 Spring Boot 3.4 / Java 21 的本地链上交易风控评分引擎。使用本地模拟事件流驱动钱包资金流向
分析与风险判定，在**并发摄入、重复投递、乱序到达、规则热更新**等场景下保持结论一致、可解释、可复现。

## 1. 总体结构

```
model/    TransactionEvent / IngestResult / RejectionReason（输入与拒绝原因）
ingest/   EventValidator（字段与范围校验）、EventIngestionService（校验+幂等+归一化）
store/    EventStore（线程安全事件存储，eventId+txHash 两级去重，确定性快照）
score/    RiskRuleSet（版本化不可变规则）、RuleSetProvider（当前版本 + 全部历史版本留存）
          RiskScoringService（确定性评分 + 依据链）、RuleHit（结构化判定依据）
          EventSetFingerprint（事件集合 SHA-256 指纹）
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
- **交易哈希大小写归一化（本次修复）**：txHash 校验通过后同样统一归一化为
  **小写规范形式**（`EventStore.canonicalTxHash`，ROOT Locale）再入库。
  同一笔链上交易无论上游用 `0XABCDEF1`、`0xabcdef1` 还是 `0xAbCdEf1`
  投递，**系统中留存与对外展示的哈希永远是唯一的小写规范形式，与哪一版先到无关**，
  评分依据 `evidence`、结构化依据 `ruleHits.txHashes`、事件集合指纹
  `eventSetFingerprint` 中出现的哈希与大小写写法无关，只由"是哪笔交易"决定。
- **可解释依据链**：`RiskScoreResult.evidence` 逐条记录命中规则、加减分、
  事件 ID、交易哈希、金额、方向与对手方集合，并记录使用的规则集版本，例如
  `LARGE_AMOUNT(+20): event=evt-1 tx=0xtx1 50000 ETH 0xaa..->0x00..01 >= threshold 10000`。
- **结构化判定依据（新增）**：`RiskScoreResult.ruleHits` 以结构化形式给出同样的结论，
  供自动对账/比对使用。每条 `RuleHit` 包含：
  `rule`（LARGE_AMOUNT / FAN_OUT / FAN_IN）、`direction`（OUTGOING / INCOMING / BOTH）、
  `counterparties`（对手方，升序）、`totalAmount`（相关金额合计）、
  `eventIds` / `txHashes`（关联交易记录，升序）。
- **事件集合指纹（新增）**：`RiskScoreResult.eventSetFingerprint` 是本次评分所依据
  事件集合（该地址参与的全部已接受事件）的 SHA-256。事件先按 `(timestamp, eventId)`
  确定性排序再逐行序列化哈希（序列化前 txHash/地址均为小写规范形式、资产大写、
  金额去尾零），因此**同一批事件无论到达顺序、重复投递次数、哈希大小写写法如何，
  指纹与结构化依据完全一致；事件集合有任何增删改，指纹必然变化**。
- 告警列表按分数降序、地址升序排列，保证多次查询输出稳定。

规则集是不可变 record，"更新规则"是整体替换引用（`PUT /api/rules`，参数须通过范围校验）。
历史结论可用保存的旧规则集引用通过 `scoreOnSnapshot(address, snapshot, oldRules)` 原样复现。

## 2.1 规则版本留存与历史复算（新增）

- 系统内部留存**每一个**发布过的规则版本（`RuleSetProvider` 内的只增映射），
  不再依赖调用方自己保存旧规则；只凭版本号即可复算当时的评分与告警。
- 发布约束：**只追加、不覆盖、不回退**。版本号必须大于当前生效版本且从未发布过：
  - 同版本号重复发布（无论内容是否相同）→ `DuplicateRuleVersionException`，REST 返回 **409**；
  - 版本号不大于当前版本 → **400**；参数非法 → **400**；按未发布版本复算 → **404**。
- 性能：当前评分/告警查询只读一个 volatile 引用，历史查找是 `ConcurrentHashMap` 的
  O(1) 读取，累积几百个版本也不会拖慢当前查询（`RuleVersionHistoryTest` 覆盖 300 个版本）。
- 并发自洽：发布是一次原子引用切换，进行中的查询持有查询开始时的不可变规则集，
  要么完整旧版、要么完整新版，绝不新旧混用（`ConcurrentPublishQueryTest` 覆盖）。

复算接口：

```bash
curl -s localhost:8080/api/rules/versions          # 全部已发布版本号
curl -s localhost:8080/api/rules/1                 # 取回 v1 规则集
curl -s "localhost:8080/api/scores/0xaa...aa?version=1"   # 按 v1 复算评分
curl -s "localhost:8080/api/alerts?version=1"             # 按 v1 复算告警
```

## 3. 幂等与并发语义

### 3.1 摄入幂等（两级去重）

- 业务上的"同一笔交易"只计价一次，判定口径为两级：
  1. **eventId 相同** → 同一次投递的重复（哪怕这次换了哈希写法），拒绝原因 `DUPLICATE_EVENT`；
  2. **txHash 相同（忽略大小写，`0X`/`0x` 前缀也不区分）但 eventId 不同** → 同一笔链上交易换了投递编号重新推送，
     拒绝原因 `DUPLICATE_TRANSACTION`，拒绝说明中带首次接受该交易的 eventId 便于定位。
- **大小写与投递编号对留存内容的口径**：两级检查以 txHash 的**小写规范形式**为键；
  一旦某笔交易被接受，入库记录中的 txHash 即被替换成小写规范形式（地址、金额、资产
  也都已归一化）。被拒绝的重投——无论换大小写、换投递编号还是二者都换——**不会覆盖、
  也不会污染**任何留存内容。因此：
  - 大写版先到还是小写版先到，只影响"哪个 eventId 被接受"这一投递事实，
    **不影响**留存哈希、人读依据、结构化依据与事件集合指纹；
  - 同一批事件按任意顺序单线程灌入，与几十个线程并发灌入，得到的评分结果
    （在被接受投递相同的前提下）逐字段一致；
  - eventId（投递编号）本身仍是哪一次投递被接受的事实记录：换编号重投被拒后
    留存的仍是首次接受那条的 eventId，不会改写。
- 两级检查与写入在同一把锁内原子完成，几十个线程同时灌入重复数据时，
  同一笔交易也只会被接受一次（`TransactionDedupTest` 覆盖 32 线程 × 640 份重复投递，
  `TxHashCaseDedupTest` 专门覆盖 32 线程 × 192/224 份不同大小写、不同编号的重投）。
- 地址在入库前统一归一化为小写，因此大小写混写不影响去重与归集。
- 哈希**确实不同**（不止大小写差异，如 `0xabcdef1` 与 `0xabcdef2`）仍是不同交易，
  各自计价，不会被误判成一笔（`TxHashCaseDedupTest` 对照组覆盖）。
- 写入前先经 `EventValidator` 校验。被拒绝的事件不会进入统计，且返回**可区分**的拒绝原因
  （`RejectionReason`）：

  | 原因 | 含义 |
  | --- | --- |
  | DUPLICATE_EVENT | eventId 已接受过（同一次投递的重复） |
  | DUPLICATE_TRANSACTION | txHash 已被其他 eventId 计价过（同一笔链上交易换了投递编号） |
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
| GET  | `/api/scores/{address}?version=N` | 按历史规则版本复算评分；版本不存在返回 404 |
| GET  | `/api/alerts` | 查询达到告警线的全部钱包 |
| GET  | `/api/alerts?version=N` | 按历史规则版本复算告警；版本不存在返回 404 |
| GET  | `/api/rules` | 当前生效规则集 |
| GET  | `/api/rules/versions` | 全部已发布版本号（升序） |
| GET  | `/api/rules/{version}` | 取回历史版本规则集；版本不存在返回 404 |
| PUT  | `/api/rules` | 发布新版本规则集（非法参数 400；版本未前进 400；版本号已发布 409） |

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

## 4.1 兼容性说明（本次新增能力对旧调用方的影响）

**保持不变的部分：**

- 所有既有端点的路径、请求方式、请求体格式不变；
- `RiskScoreResult` 的既有字段 `address / score / ruleSetVersion / evidence`
  含义与格式不变，人读依据链 `evidence` 照常返回；
- 摄入响应 `{accepted, rejectionReason, detail}` 结构不变，
  既有拒绝原因（含 `DUPLICATE_EVENT`）的语义不变；
- 不带 `version` 参数的评分/告警查询行为与之前一致（使用当前生效规则集）。

**新增（纯追加，不影响旧字段消费方）：**

- 评分结果追加 `ruleHits`（结构化判定依据）与 `eventSetFingerprint`（事件集合指纹）
  两个字段；只读旧字段的调用方无需修改；
- 新增拒绝原因 `DUPLICATE_TRANSACTION`：**行为变化点**——同一 txHash 换 eventId
  重复推送，以前会被当作新交易计价，现在会被拒绝。上游若依赖"换投递编号即可重新计价"
  的旧行为，需要先为那笔交易构造新的 txHash（即业务上确实是另一笔交易）；
- **交易哈希留存口径修复（本次）**：txHash 以前按首达投递的原样大小写留存，
  导致大写版先到时 `evidence` / `ruleHits` / 指纹里全是大写、小写版先到又全是小写，
  同一批事件换个到达顺序指纹就对不上。现在统一留存小写规范形式，
  所有对外字段（含既有 `evidence` 文本中的 `tx=...`）都只出现小写哈希。
  接口路径、请求体、响应结构与各字段含义均未改变，仅规范化了既有文本字段的大小写；
- `PUT /api/rules` 新增发布约束：版本号必须大于当前版本且未发布过，
  重复发布返回 409（以前是同版本号静默覆盖）；
- 新增只读端点 `/api/rules/versions`、`/api/rules/{version}` 及查询参数 `version`。

**旧数据升级处理：** 本引擎为内存存储，重启即清空，不存在持久化数据的迁移问题；
运行中升级后，已接受事件的统计口径不变，只是此后同一 txHash 的换号重投会被拒绝。
规则版本历史从默认 v1 开始累积，升级前调用方自行保存的旧规则集仍可通过
`scoreOnSnapshot(address, snapshot, oldRules)` 复算（该纯函数接口保留）。

## 5. 本地验证方法

### 5.1 运行测试（推荐先看日志）

```bash
mvn test
```

测试覆盖：

- `EventIngestionServiceTest`：合法/非法输入、每种可区分拒绝原因、地址大小写归一化；
- `ConcurrentIngestionTest`：8 线程并发摄入 200 个唯一事件 × 3 份乱序副本，
  断言接受数、重复拒绝数、存储大小，并对 5 种不同摄入顺序断言评分与依据链完全一致；
- `TransactionDedupTest`：同一 txHash 换投递编号重复推送（含 32 线程 × 640 份并发灌入）
  只计价一次；`DUPLICATE_EVENT` 与 `DUPLICATE_TRANSACTION` 两种拒因可区分；
- `TxHashCaseDedupTest`（本次新增，稳定复现对账问题）：同一笔交易仅换哈希大小写
  写法（`0XABCDEF1` / `0xabcdef1` / `0xAbCdEf1`），重投有的换投递编号、有的不换——
  - 按大写先到 / 小写先到 / 混写先到三种顺序单线程灌入：断言只接受一条、
    留存哈希与 `evidence` 中只剩小写规范形式、`ruleHits` 与 `eventSetFingerprint`
    同基准逐字段一致、两种拒因都出现且可区分、拒绝说明指向首次接受的 eventId；
  - 32 线程无预埋并发灌入：恰好接受一条，再用胜出投递编号单线程等价重放，
    评分结果逐字段一致；预埋一条后再并发重灌：并发期 0 接受，结果同单线程基准；
  - 对照组：哈希末位不同（非大小写差异）仍是两笔交易，各自计价；
- `RuleUpdateAndAlertTest`：规则更新后旧版本结论按版本复现、告警查询与
  规则更新并发时版本自洽、非法规则被拒、告警排序稳定；
- `RuleVersionHistoryTest`：每个发布版本留存、同版本号/回退发布被拒、
  只凭版本号复现历史结论、300 个版本累积后当前查询与任意历史复算正常；
- `StructuredEvidenceTest`：结构化依据（规则/方向/对手方/金额/关联交易）内容正确，
  指纹与结构化依据在乱序 + 重复投递下逐字段一致，事件集合增删改后指纹变化；
- `ConcurrentPublishQueryTest`：50 个版本连续发布与评分/告警查询并发执行，
  每次查询都落在某一个完整已发布版本上（可按该版本原样重算），无新旧混用；
- `ApiSmokeTest`：REST 全链路（摄入、重复拒绝、评分、告警、规则发布、
  重复发布 409、历史版本复算、未知版本 404、非法地址 400、新旧返回字段共存）。

单测日志中会打印**输入事件**（`ingest input event: ...`）与**判定依据**
（`score ... evidence=...`、`ingest rejected ... reason=...`）。查看完整日志：

```bash
mvn test -Dtest=ConcurrentIngestionTest
mvn test -Dtest=TransactionDedupTest
mvn test -Dtest=TxHashCaseDedupTest
mvn test -Dtest=RuleVersionHistoryTest
mvn test -Dtest=ConcurrentPublishQueryTest
```

### 5.1.1 本地复现"哈希大小写导致对不上账"及验证修复

单测（推荐，确定性、可重复）：

```bash
mvn test -Dtest=TxHashCaseDedupTest
```

日志中可见每种到达顺序下的业务含义与结果，例如：

```
顺序#1 (首达写法=0XABCDEF1) 首达轮 accepted=1/3; 不换编号重投拒因=[DUPLICATE_EVENT]; 换编号重投拒因=[DUPLICATE_TRANSACTION] (说明均指向 evt-1)
顺序#1 评分: score=100 fingerprint=0b0d... 与基准逐字段一致=true
并发(无预埋): total=192 accepted=1 duplicateEvent(同编号)=3 duplicateTransaction(换编号)=188 storeSize=6
```

也可以用 curl 手工复现：先以大写哈希投一笔，再以小写哈希换编号重投（被拒），
随后评分，观察返回中的 `tx=...`、`ruleHits[].txHashes` 与 `eventSetFingerprint`
始终是小写；换过来先投小写再投大写，三次结果（分数/证据/指纹）完全一致：

```bash
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-1","txHash":"0XABCDEF1",
  "fromAddress":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "toAddress":"0x0101010101010101010101010101010101010101",
  "amount":50000,"asset":"ETH","timestamp":"2026-09-01T00:01:00Z"}'
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-2","txHash":"0xabcdef1",
  "fromAddress":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "toAddress":"0x0101010101010101010101010101010101010101",
  "amount":50000,"asset":"ETH","timestamp":"2026-09-01T00:01:00Z"}'   # DUPLICATE_TRANSACTION
curl -s localhost:8080/api/scores/0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
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
