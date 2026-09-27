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

- 评审人包含唯一编号、所属机构、擅长主题集合、最大同时评审数、周转天数（`reviewDays`，默认 14）和启用状态。
- 投稿包含唯一稿件号、主题集合、作者（作者唯一编号及所属机构）、要求的评审人数以及当前评审轮次（`reviewRound`，默认 1）。

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

### 截止期与计算依据

- 每条任务创建时按 **max(轮次基础天数, 评审人周转天数)** 计算截止时间：轮次基础天数由配置 `review.deadline.round-days[轮次]` 给出（未配置轮次用 `review.deadline.default-round-days`），评审人周转天数为评审人档案上的 `reviewDays`（默认 14）。评审人规则只能延长、不能压缩轮次要求。
- 计算依据随任务快照保存：`reviewRound`、`roundDays`、`reviewerDays`、`extensionDays` 四个输入分量及可读的 `deadlineBasis` 描述；延期批准后依据同步重算，历史变化通过延期事件追溯。

### 延期（申请 / 批准 / 拒绝）

- 评审人可对 `ACTIVE` 任务申请延期；编辑在上限 `review.deadline.max-extension-days` 内批准（可改批天数），**每个任务最多批准一次**；批准后截止期顺延。
- 申请、批准、拒绝全部落为 `extension_event` **不可变事件**（只追加、无更新/删除入口），任务的延期状态由事件序列推导。
- 幂等约定：已有待审批申请时重复申请、已批准后重复批准、已决定后重复拒绝，均返回当前事件时间线而不产生新事件。

### 逾期扫描与自动改派

- `POST /api/overdue-scan` 扫描所有 `ACTIVE` 且截止期已到的任务，适合由外部调度器周期触发。
- 每条任务在**独立事务**中处理：先以条件更新（CAS，要求仍为 `ACTIVE` 且 `dueAt` 确已过期）原子转为 `OVERDUE` 终态并释放负载，再按既有利益冲突/主题能力/工作量规则补选替补（新任务记录 `replacesAssignmentId` 形成改派链）；无替补则稿件进入 `SHORT`。
- 单条失败（如锁等待超时）不阻塞批次，留待下一周期重试；服务重启后再次扫描即可续跑，已转终态的任务不会被重复处理（批次幂等）。

### 评审意见提交与并发终态

- `POST .../assignments/{reviewerCode}/review` 提交意见：通过单语句条件更新把任务从 `ACTIVE` 原子转为 `COMPLETED`。与逾期扫描并发时**全系统只产生一个终态**——提交获胜则任务完成；扫描获胜则意见按 `late=true` 存档，**不得恢复任务**，旧任务也不会再次占用负载。
- 已 `COMPLETED` 的任务不会被扫描改派；同一任务重复提交幂等返回原意见（每任务至多一条意见，数据库唯一约束兜底）。

### 并发控制

- 投稿行先加悲观写锁，串行化同一稿件的分配与拒绝/替补。
- 候选评审人按 ID 升序统一加悲观写锁，所有事务加锁顺序一致，杜绝死锁；工作量判定在锁内完成，多个稿件并发分配不会突破任何评审人的上限。
- 任务终态转换（提交完成、逾期改派）使用数据库条件更新实现 CAS；延期审批对任务行加悲观写锁并复查状态，逾期 CAS 同时校验 `dueAt` 未顺延，三方并发只产生一个终态。
- 实体带 `@Version` 乐观锁版本，数据库层设有评审人编号、稿件号、集合成员及稿件-评审人关系等唯一约束。

## 主要接口

### 评审人

- `POST /api/reviewers`：创建评审人（请求体：`code`、`affiliation`、`topics`、`maxAssignments`、`enabled`，可选 `reviewDays`）
- `PATCH /api/reviewers/{code}`：更新机构、主题、上限、周转天数或启用状态
- `GET /api/reviewers` / `GET /api/reviewers/{code}`：列表 / 详情
- `GET /api/reviewers/loads`：所有评审人的当前负载（有效任务数、剩余容量）

### 投稿

- `POST /api/submissions`：创建投稿（请求体：`manuscriptNo`、`topics`、`authors[{code,affiliation}]`、`requiredReviewers`，可选 `reviewRound`，默认 1）
- `GET /api/submissions` / `GET /api/submissions/{manuscriptNo}`：列表 / 详情

### 分配

- `POST /api/submissions/{manuscriptNo}/assignments/auto`：自动分配评审人（同时计算并保存截止期及依据）
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/decline`：评审人拒绝并自动替补
- `GET /api/submissions/{manuscriptNo}/assignments`：投稿分配详情——当前有效评审人、逐条任务的截止期/计算依据/延期事件/逾期原因，以及 `reassignments` 历次改派链

### 延期

- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/extension/requests`：评审人申请延期（请求体：`days`、`reason`）
- `POST .../extension/approve`：编辑批准（请求体：`editor`，可选 `days`、`reason`；每任务限一次且不超过上限）
- `POST .../extension/reject`：编辑拒绝（请求体：`editor`，可选 `reason`）
- `GET .../extension/events`：该任务的延期事件时间线

### 评审意见与逾期扫描

- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/review`：提交评审意见（请求体：`content`；迟到意见以 `late=true` 存档）
- `GET /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/review`：查询该任务的评审意见
- `POST /api/overdue-scan`：逾期扫描并自动改派（幂等，可断点续扫）

稿件状态取值：`PENDING`（未凑齐/尚未分配）、`ASSIGNED`（有效人数达标）、`SHORT`（拒绝或逾期后缺员）。
任务状态取值：`ACTIVE`（进行中，占用负载）、`DECLINED`（已拒绝）、`COMPLETED`（已提交，终态）、`OVERDUE`（已逾期改派，终态）。

## 自动化测试

- `AssignmentServiceTest`：候选过滤（停用/同人/同机构/满负载）、三级排序、整笔失败零副作用、拒绝替补、缺员状态、重复拒绝幂等。
- `AssignmentConcurrencyTest`：多稿件并行分配不突破上限；容量竞争时失败事务整体回滚。
- `DeadlineExtensionServiceTest`：截止期按轮次与评审人规则计算并保存依据；延期批准顺延截止期且限一次、事件不可变；拒绝后可再申请；重复申请/批准/拒绝幂等；上限与状态校验。
- `OverdueScanServiceTest`：逾期释放负载并原子改派、无替补进入缺员、扫描幂等、迟到意见不恢复任务、已提交任务不被扫描改派。
- `TerminalStateConcurrencyTest`：提交与扫描并发只产生一个终态；多扫描实例并发只改派一次、负载只释放一次。
- `AssignmentApiTest`：通过 MockMvc 覆盖创建、自动分配、负载查询、拒绝替补、缺员、422 校验失败等端到端场景。
- `DeadlineApiTest`：端到端覆盖截止期展示、延期申请/批准、逾期扫描改派、改派链与逾期原因展示、迟到意见存档、校验错误。
