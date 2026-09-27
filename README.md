# cc-peer-review

管理投稿、评审人资格和评审分配。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 业务规则

### 评审人与投稿

- 评审人包含唯一编号、所属机构、擅长主题集合、最大同时评审数和启用状态。
- 投稿包含唯一稿件号、主题集合、作者（作者唯一编号及所属机构）以及要求的评审人数。

### 自动分配候选资格

进入评审后执行自动分配，候选评审人必须同时满足：

1. 处于启用状态；
2. 擅长主题至少匹配一个投稿主题；
3. 与任一作者不是同一人（评审人编号与作者编号不同）；
4. 不属于任一作者所属机构（机构与任一作者机构不同）；
5. 当前有效任务数小于其最大同时评审数；
6. 同一投稿此前从未被分配给该评审人（已拒绝也计入，防止重复分配）。

### 选择排序

候选评审人按以下优先级稳定排序后取前 N 名：

1. 主题匹配数从多到少；
2. 当前有效任务数从少到多；
3. 评审人 ID 从小到大（稳定兜底）。

### 原子性与失败处理

- 自动分配是单事务操作：若符合条件的评审人不足要求人数，整笔分配失败回滚，**任何人的工作量都不增加**，稿件保持 `PENDING`。
- 自动分配仅对尚无任何分配记录的稿件开放，重复调用返回冲突错误；`(submission_id, reviewer_id)` 数据库唯一约束兜底。

### 拒绝与替补

- 评审人拒绝任务时，系统在**同一事务内**将任务标记为 `DECLINED` 并按上述相同规则补选一名**从未分配过该稿件**的新候选（拒绝记录保留，所以被拒评审人不会再次入选）。
- 若无可用替补，稿件进入 `SHORT`（缺员）状态，已有有效分配全部保留。
- 拒绝操作幂等：对非 `ACTIVE` 任务重复拒绝不产生任何副作用。
- 对不存在分配关系的评审人执行拒绝属于非法操作，事务回滚。

### 截止期计算

- 每个评审任务创建（自动分配、拒绝替补、逾期替补）时即计算截止时间 `dueAt`，并把**计算依据**持久化在任务的 `deadlineBasis` 字段中，格式示例：
  `round=2, roundDays=14, reviewerDays=10, effectiveDays=10, assignedAt=2026-09-27T...`。
- 轮次基础天数：第 1 轮 `first-round-days` 天，每进入下一轮减少 `round-step-days` 天，但不低于 `min-days`。
- 评审人规则：评审人可配置个人评审周期 `reviewDays`（0 表示不设个人规则）；生效天数 = `min(轮次天数, reviewDays)`（无个人规则时取轮次天数）。
- 相关配置（`review.deadline.*`，默认值见 `ReviewDeadlineProperties`）：
  - `first-round-days=21`、`round-step-days=7`、`min-days=7`
  - `extension-max-days=14`：单次延期批准的天数上限
  - `scan-batch-size=100`：逾期扫描单批任务数
  - `scan-enabled=true`、`scan-interval-ms=60000`：定时扫描开关与间隔

### 延期

- 评审人可对 `ACTIVE` 任务申请延期（天数不得超过 `extension-max-days`），编辑在上限内批准或拒绝；**每个任务至多批准一次**，批准后截止时间在当前基础上顺延，并在 `deadlineBasis` 上追加记录。
- 延期申请、批准、拒绝都会写入不可变事件（`assignment_event` 表，只插不改不删）。
- 已有待审批申请时不可重复申请；被拒绝后可重新申请。

### 评审意见提交

- 仅 `ACTIVE` 任务可提交评审意见，提交后进入终态 `SUBMITTED`（不再占用工作量）；重复提交幂等。
- 已逾期/已拒绝任务上的**迟到意见不得恢复任务**：接口返回 409，同时以独立事务写入 `LATE_SUBMITTED` 事件留痕（主事务回滚也不丢失）。

### 逾期扫描与自动改派

- 定时扫描（可关）或 `POST /api/admin/overdue-scan` 手动触发：找出 `ACTIVE` 且已过 `dueAt` 的任务，逐条在**独立事务**中处理。
- 处理动作在同一事务内原子完成：任务转为终态 `OVERDUE` 并记录逾期原因 → 释放评审人活动负载（仅 `ACTIVE` 占负载）→ 按既有冲突/主题/工作量规则补选替补（原评审人含逾期记录，不会再次入选）→ 写入 `OVERDUE`/`ASSIGNED`/`REASSIGNED` 事件。无替补时稿件进入 `SHORT`。
- **幂等与重启恢复**：扫描查询只读当前状态，每条任务处理前在行锁内复检状态与截止时间，已处理的任务自然跳过；单批限量、单条失败不中断批次，服务重启后继续扫描即可从断点恢复，重复扫描不产生重复事件或重复改派。

### 并发控制与终态唯一

- 全系统统一加锁顺序：稿件行 → 任务行 → 候选评审人（按 ID 升序），杜绝死锁。
- 提交、延期审批、逾期扫描并发时，同一任务只可能从 `ACTIVE` 变迁到**一个**终态（`SUBMITTED` 或 `OVERDUE`）：先到者持锁完成变迁，后到者在锁内看到终态后放弃（扫描跳过）或被拒（409）。
- 已提交的任务不会被改派；已改派的旧任务（`OVERDUE`/`DECLINED`）不再占用负载，也不会因迟到意见恢复。
- 实体带 `@Version` 乐观锁版本，数据库层设有评审人编号、稿件号、集合成员及稿件-评审人关系等唯一约束。

## 主要接口

### 评审人

- `POST /api/reviewers`：创建评审人（请求体：`code`、`affiliation`、`topics`、`maxAssignments`、`enabled`、可选 `reviewDays` 个人评审周期天数）
- `PATCH /api/reviewers/{code}`：更新机构、主题、上限、启用状态或 `reviewDays`
- `GET /api/reviewers` / `GET /api/reviewers/{code}`：列表 / 详情
- `GET /api/reviewers/loads`：所有评审人的当前负载（有效任务数、剩余容量）

### 投稿

- `POST /api/submissions`：创建投稿（请求体：`manuscriptNo`、`topics`、`authors[{code,affiliation}]`、`requiredReviewers`、可选 `round` 评审轮次，默认 1）
- `GET /api/submissions` / `GET /api/submissions/{manuscriptNo}`：列表 / 详情

### 分配

- `POST /api/submissions/{manuscriptNo}/assignments/auto`：自动分配评审人（同时计算截止期并保存计算依据）
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/decline`：评审人拒绝并自动替补
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/review`：提交评审意见（请求体：`comment`；任务已终态时返回 409 且迟到意见留痕）
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/extensions`：申请延期（请求体：`requestedDays`、`reason`、`requestedBy`）
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/extensions/approve`：编辑批准延期（请求体：`approvedDays`、`decidedBy`；每个任务至多一次、不超过上限）
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/extensions/reject`：编辑拒绝延期（请求体：`decidedBy`、`reason`）
- `GET /api/submissions/{manuscriptNo}/assignments`：投稿分配详情——当前有效评审人、每条任务的截止期/计算依据/提交时间/逾期原因，以及全部生命周期事件（历次改派、截止期变化、延期审批）

### 运维

- `POST /api/admin/overdue-scan`：手动触发一轮逾期扫描（幂等，返回 `{scanned, processed}`）

稿件状态取值：`PENDING`（未凑齐/尚未分配）、`ASSIGNED`（有效人数达标）、`SHORT`（拒绝或逾期后缺员）。
任务状态取值：`ACTIVE`（有效，占负载）、`SUBMITTED`（已提交，终态）、`OVERDUE`（逾期未提交，终态）、`DECLINED`（已拒绝）。

## 自动化测试

- `AssignmentServiceTest`：候选过滤（停用/同人/同机构/满负载）、三级排序、整笔失败零副作用、拒绝替补、缺员状态、重复拒绝幂等。
- `AssignmentConcurrencyTest`：多稿件并行分配不突破上限；容量竞争时失败事务整体回滚。
- `DeadlineExtensionOverdueTest`：截止期按轮次+评审人规则计算并留存依据、延期申请/批准/拒绝（上限、仅一次、事件留痕）、逾期扫描改派与负载释放、迟到意见不恢复任务、已提交任务不被扫描改派、重复扫描幂等、无替补缺员、详情展示改派与截止期历史。
- `OverdueConcurrencyTest`：提交与扫描并发、延期批准与扫描并发时同一任务只产生一个终态。
- `AssignmentApiTest`：通过 MockMvc 覆盖创建、自动分配、负载查询、拒绝替补、缺员、422 校验失败等端到端场景。
- `DeadlineApiTest`：端到端覆盖截止期计算依据、延期审批、意见提交、手动扫描改派、重复扫描幂等、迟到提交 409 留痕。
