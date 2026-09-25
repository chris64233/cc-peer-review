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

### 并发控制

- 投稿行先加悲观写锁，串行化同一稿件的分配与拒绝/替补。
- 候选评审人按 ID 升序统一加悲观写锁，所有事务加锁顺序一致，杜绝死锁；工作量判定在锁内完成，多个稿件并发分配不会突破任何评审人的上限。
- 实体带 `@Version` 乐观锁版本，数据库层设有评审人编号、稿件号、集合成员及稿件-评审人关系等唯一约束。

## 主要接口

### 评审人

- `POST /api/reviewers`：创建评审人（请求体：`code`、`affiliation`、`topics`、`maxAssignments`、`enabled`）
- `PATCH /api/reviewers/{code}`：更新机构、主题、上限或启用状态
- `GET /api/reviewers` / `GET /api/reviewers/{code}`：列表 / 详情
- `GET /api/reviewers/loads`：所有评审人的当前负载（有效任务数、剩余容量）

### 投稿

- `POST /api/submissions`：创建投稿（请求体：`manuscriptNo`、`topics`、`authors[{code,affiliation}]`、`requiredReviewers`）
- `GET /api/submissions` / `GET /api/submissions/{manuscriptNo}`：列表 / 详情

### 分配

- `POST /api/submissions/{manuscriptNo}/assignments/auto`：自动分配评审人
- `POST /api/submissions/{manuscriptNo}/assignments/{reviewerCode}/decline`：评审人拒绝并自动替补
- `GET /api/submissions/{manuscriptNo}/assignments`：投稿分配详情（稿件状态、要求人数、有效人数、逐条任务）

稿件状态取值：`PENDING`（未凑齐/尚未分配）、`ASSIGNED`（有效人数达标）、`SHORT`（拒绝后缺员）。

## 自动化测试

- `AssignmentServiceTest`：候选过滤（停用/同人/同机构/满负载）、三级排序、整笔失败零副作用、拒绝替补、缺员状态、重复拒绝幂等。
- `AssignmentConcurrencyTest`：多稿件并行分配不突破上限；容量竞争时失败事务整体回滚。
- `AssignmentApiTest`：通过 MockMvc 覆盖创建、自动分配、负载查询、拒绝替补、缺员、422 校验失败等端到端场景。
