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

## 主要业务规则

### 数据模型

- **评审人 Reviewer**：唯一编号 `reviewerNo`、姓名、所属机构、擅长主题集合、最大同时评审数 `maxConcurrent`、启用状态 `active`。
- **投稿 Submission**：唯一稿件号 `manuscriptNo`、主题集合、作者列表（姓名 + 所属机构）、所需评审人数 `requiredReviewers`、状态（`PENDING` / `ASSIGNED` / `UNDERSTAFFED`）。
- **分配 Assignment**：稿件与评审人的关联，状态为 `ACTIVE` 或 `DECLINED`；数据库唯一约束 `(submission_id, reviewer_id)` 保证同一评审人对同一稿件至多一条记录（含已拒绝），因此拒绝过的评审人不会被重新分配。

### 候选评审人过滤

候选评审人必须同时满足：

1. 处于启用状态；
2. 擅长主题与投稿主题至少有一个交集；
3. 与任一作者不是同一人（按姓名判断）；
4. 不属于任一作者的所属机构；
5. 当前有效（`ACTIVE`）任务数小于 `maxConcurrent`；
6. 从未被分配过该稿件（含已拒绝的记录）。

### 自动分配

- 按 **主题匹配数降序 → 当前任务数升序 → 评审人 ID 升序** 的稳定顺序选择，直到凑齐所需人数。
- 分配是**整笔原子操作**：若合格候选不足，事务回滚，任何人的工作量都不会增加，稿件保持 `PENDING`。
- 已有有效分配的稿件不允许重复触发自动分配。

### 拒绝与替补

- 评审人拒绝后，在同一事务内按同一规则从未曾分配过该稿件的候选中选择最优替补。
- 无替补时稿件进入 `UNDERSTAFFED` 缺员状态，但保留其余有效分配。
- 重复拒绝是幂等的：已 `DECLINED` 的记录再次拒绝不会产生任何变更。

### 并发控制

- 分配与拒绝/替补在事务内对所有启用的评审人行加悲观写锁（`SELECT ... FOR UPDATE`，按 ID 排序避免死锁），并发分配串行化执行，任何评审人的有效任务数都不会突破上限。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/reviewers` | 创建评审人 |
| GET | `/api/reviewers/{id}/load` | 查询评审人当前负载（有效任务数 / 上限 / 剩余容量） |
| POST | `/api/submissions` | 创建投稿 |
| POST | `/api/submissions/{id}/assign` | 触发自动分配（失败返回 422，整体回滚） |
| POST | `/api/submissions/{id}/assignments/{reviewerId}/decline` | 评审人拒绝并自动替补 |
| GET | `/api/submissions/{id}/assignments` | 查询投稿分配详情 |
