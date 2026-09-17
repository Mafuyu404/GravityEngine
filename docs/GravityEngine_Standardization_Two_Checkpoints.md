> Historical execution plan: CP1 and CP2 are implemented. Current contracts live
> in [API_BOUNDARY.md](API_BOUNDARY.md), [ARCHITECTURE.md](ARCHITECTURE.md) and
> [DEVELOPMENT.md](DEVELOPMENT.md); actual acceptance evidence lives in
> [REFACTOR_STATUS.md](REFACTOR_STATUS.md). The prompts, baseline links and
> proposed paths below are retained as historical context, not current commands.

# GravityEngine 标准化重构：两个 Checkpoint

> **用途**：修复清单、实现约束和可直接交给 Codex 的执行提示词。本文是工作规范，不是已经完成重构或通过性能测试的证明。
>
> **基线**：`Mafuyu404/GravityEngine`，`dev`，提交 `186f70fa765e1565c428768a2a5d5af178b0d8fc`。本次重新读取分支信息，仍指向该提交。[S01]
>
> **实施主目标**：`common` + `targets/neoforge-1.21.1`。该 target 是基线 README 指定的权威实现；其他 target 不默认具有功能同等性。[S02]
>
> **建议落盘位置**：`docs/REFACTOR_PLAN.md`。第 7、8 节分别是两个 checkpoint 的完整执行提示词。

## 1. 范围与交付边界

本轮只设两个 checkpoint。内部可以分多个可回滚修改，不要求把每个 checkpoint 压成一个巨型提交。

| Checkpoint | 名称 | 必须交付 | 不在此阶段做的事 |
| --- | --- | --- | --- |
| **CP1** | **性能与过度建模重构** | 修复影响重构的正确性边界；统一生产求值路径；消除可证明的重复工作；收敛状态所有权；建立可复现的前后对比 | 不宣布 API 稳定；不为目录整齐重写全部模块；不新增通用插件框架 |
| **CP2** | **API 稳定与文档** | 以 CP1 实现为准稳定公开契约；补必要的消费者接入能力；验证成品依赖；修复 CI／构建入口；整理 README、AGENTS 和维护文档 | 不重新设计物理核心；不全面移植其他版本；不自动把内部 public 类变成受支持 API |

**正确性修复归 CP1，而不是留到 CP2 用文档解释。** CP1 可以调整完成修复所必需的内部接口和最小公开接缝；这些变更到 CP2 才确定最终兼容性承诺。CP2 的新门面必须复用 CP1 的权威实现，不另造一条运行路径。

本轮不新增星球、武器、世界生成等玩法；不重写完整碰撞求解器；不自动恢复历史存档格式；不增加原本未承诺的全实体／全 loader 支持。通用性的目标是：**合法扩展有明确入口，未知情况有明确处置，而不是假装支持所有情况。**

### 1.1 证据与判断分级

以下“静态确认”只表示控制流、数据结构或文档链接可从基线源码确认，不表示游戏内后果已经实测。

| 项目 | 当前证据 | 本轮处理 |
| --- | --- | --- |
| 两条场组合路径、不同 evaluator 调用时机 | 静态确认。[S04] | CP1-01 必须统一 |
| publication-backed provider 重复访问全局 registry | 静态确认调用结构；实际成本待测。[S03] | CP1-02 修重复工作并测量 |
| 下落方块发现依赖 publication；revision 变化重置 discovery | 静态确认；饥饿及玩法后果需重放验证。[S05] | CP1-03 必须修复 |
| 未加载区块被跳过，scene 只检查几何包络 | 静态确认表达缺口；不据此宣称已实测穿墙。[S06][S07] | CP1-04 必须修复 |
| 捕获 scene 多次线性过滤、定点查找及排序 | 静态确认；索引收益待测。[S07] | CP1-05 以负载决定实现 |
| `entityGravity()` 无有效 evaluation 时回退 assignment | 静态确认；getter 不应为消除回退而主动求值。[S08] | CP2-02 补返回语义 |
| CI 路径过滤未包含普通 target 源码；根构建使用 Windows 命令 | 静态确认。[S09][S10] | CP2-05 修复 |
| README 文档入口与基线目录树不一致 | README 有 docs 链接，基线根树无 docs。[S02][S11] | CP2-04 恢复真实入口 |
| 外部碰撞 provider 负路径、特殊实体能力、预算大小、架构测试白名单 | 前轮指出的定向复核项；修改前需检查实际调用者和测量 | CP1-06～08、CP2-03；不得无证据扩大重写范围 |

**纠正一种可能的误读**：基线并非“完全没有错误处理／预算／测试”。`GravityFieldRuntime` 已有重入保护和失败清理；普通 target `build` 已接入 JVM controls，完整服务器行为验证是独立任务。[S03][S12] 本轮补缺口，不把已有机制全部重建。

## 2. 两阶段共同遵守的长期不变量

| 不变量 | 重构要求 |
| --- | --- |
| 一个事实只有一个可变权威 | 明确 producer、owner、consumer、lifetime、invalidation；派生快照可复制，不能成为独立写入源 |
| 已赋值不等于已安装 | assignment、操作求值、committed application、installed geometry 不得因数值相等而合并 |
| 未知不等于不存在 | `INCOMPLETE`、完整空场、有贡献但零合力分别处理；碰撞未覆盖不等于空气 |
| 操作内一致 | 捕获结束后，求解、支撑、材质、姿态合法性查询不重新读取实时世界／第三方 provider |
| 物理所有权一致 | 已安装精确碰撞体时，不能仅因预算或覆盖问题把本次移动交给原生 AABB；切换必须经过合法提交 |
| 生命周期可证明 | 旧 publication handle 不能删除新发布；卸载和关闭回调不能重建已关闭 runtime |
| 时间与坐标显式 | 位置、速度、加速度、时间窗口、平台表面速度、姿态和表现参考系不得隐式混用 |
| 有界且不饥饿 | 单次工作预算、跨 tick 推进公平性、总负载成本分别验证 |
| 失败不能伪装成成功 | 非法参数、错误回调、预算耗尽、缺少数据和能力不支持可辨识；不统一返回“空结果” |
| 平台边界清楚 | common 保持平台无关；target 负责实时世界访问、生命周期、Mixin、网络和表现 |

这些约束描述语义，不指定必须新增多少个 enum、record、manager 或包。既有 `AGENTS.md` 已承载多项相关所有权约束，应保留有效语义，删除重复叙述，而不是直接清空。[S13]

**禁止的捷径**：Level 全局 readiness；只用 publication revision 判断所有缓存有效；捕获失败后实时补读世界；用无限扫描弥补缺少来源发现协议；通过吞异常、跳过难场景或关闭原有功能得到“性能提升”；为了排列整齐复制 common 算法到 target。

## 3. CP1：性能与过度建模重构

### CP1-01　统一场求值与组合权威

**定位**：`GravityFieldRuntime.evaluate`、`GravityFieldService`、`GravityFieldRegistry.evaluate/query` 及所有生产调用者。[S03][S04]

**问题**：旧 `compose()` 在选出有效组后调用 evaluator；provider 路径先取得贡献，再选择有效组。被 OVERRIDE 排除的 ADDITIVE evaluator 是否执行、是否抛错，存在两套可观察语义。

**修复要求**：以现有 provider 聚合模型为统一方向：每次完整 query 收集所有 expected provider 的结果，聚合 coverage，再按稳定结构顺序组合有效贡献。OVERRIDE 只排除 ADDITIVE 的数值参与，不自动免除其他 provider 的 coverage／输入合法性要求。多个 OVERRIDE 按现有规则求和，不偷偷改成“优先级最高者胜出”。

旧 registry 入口若仍需保留，只做薄适配；无法提供 provider 完整性语义的纯 registry 采样必须明确为内部局部操作，不能冒充完整世界求值。删除重复组合实现，不保留两套生产算法用于“相互校验”。

明确 contribution ID 的有效格式、全局唯一性与稳定顺序。重复 ID 不得静默覆盖／去重。检查单项有限但合计溢出、零合力、无效 interval 等边界；不得将非法数值钳成正常零重力。

**完成证据**：生产调用链指向单一组合权威；输入排列不改变规定结果；两条历史入口不再具有独立的组合／错误语义。旧测试与修正契约冲突时修改测试，并说明原因。

### CP1-02　消除重力查询的重复工作

**定位**：`GravityFieldRuntime.samplePublications` → registry 查询 → influence → contributions → composition。[S03][S04]

先测量 candidates、精确 `contains`、evaluator 调用和排序次数。选择最小改动：按 provider 分区查询，或在同一次等价 query 内只发现一次候选并按所有权分组。不要为了这个问题建立跨 tick 全局缓存。

同一 query、同一 publication 的精确范围检查／求值不应因 provider 数量机械重复。只在仍有语义价值的位置排序；最终累加顺序继续稳定。大范围和无界场必须保守可发现，不得因索引容量阈值漏查。

不得规定“每实体每 tick 只采样一次”：位置、速度、时间窗口或 provider 捕获上下文不同，属于不同 query。provider 的 coverage 也不是 publication revision 的派生值。

**完成证据**：固定候选总量增加 provider 数时，无关 publication 不再被每个 provider 重复精确检查；记录去重前后计数和实际耗时。仍存在的大范围候选成本应单独报告。

### CP1-03　统一场影响发现，并修复下落方块调度饥饿

**定位**：`FallingBlockRechecks`、`FallingBlockStartIntegration`、`GravityEvents`、runtime 的方块／区块生命周期接入。[S05]

这里有两个问题，必须分别解决：**能采样不等于能发现需要重检查的方块；有工作上限不等于最终会轮到所有任务。**

**发现契约**：引擎需要知道哪些已加载区域可能受场影响，以及变化后哪些区域需要重新检查。发现提示只负责保守候选，不是 coverage 证明。精确贡献仍由同一 query 的 provider 求值决定。

采用最小的“影响范围／失效”接缝，优先复用 publication 的 bounds。非 publication provider 也必须有接入这条路径的方式，不能强迫其伪造一个仅用于扫描的数学场。有限范围源变化覆盖旧、新范围；源移除保留清理既有受影响位置所需的信息。连续时间相关源需要有界周期重检查，不能只依赖 publication revision。

明确三种能力：有限范围自动发现；显式无界影响下的预算化已加载区域遍历；只支持即时采样。无界自动行为必须有明确成本，纯 sampling provider 不能被宣传为会自动唤醒任意既有方块。**本轮至少实现非 publication 的有限范围自动发现，不能只补一段“不支持”的文档结案。**

**调度修复**：publication 连续变化不能每 tick 从头重建 discovery。保留可推进的游标／工作队列，合并同源脏区域，卸载时撤销任务；不长期持有会失效的实时迭代器。记录 backlog、最老任务年龄和实际推进量。

保留原生 scheduled tick 去重、同 tick 已执行记录、区块可执行性检查和 `INCOMPLETE` 下不作权威判断的规则。不为追赶积压强加载区块或扫描空世界。

**完成证据**：超过单次扫描额度的既有已加载区块，在来源持续更新时仍能被依次发现；相同有界源通过 publication 与非 publication 接入时，承诺的方块行为一致。对持续超出处理能力的无限新工作只承诺有界退化，不声称有限延迟。

### CP1-04　补全碰撞捕获覆盖语义

**定位**：`MinecraftCollisionSceneCapture.captureBlocks`、`CollisionCaptureDomain`、`CapturedCollisionScene` 及 coverage 失败的上层处理。[S06][S07]

捕获结果必须区分“请求的包络”与“已取得数据的覆盖区域”。未加载／不可用区块不能只 `continue` 后仍被当成完整场景。优先在操作快照中记录覆盖缺口，或在确实无法给出任何安全结果时明确拒绝捕获；不增加全局 ready 状态。

检查全部查询：移动、支撑、姿态拟合、材质和按 cell／identity 查找。缺记录只有在对应查询依赖确实已覆盖时才可解释为空。范围判定还要包括碰撞形状可能越出所属 block 的保守余量及平台实际需要的邻域依赖，不能只检查身体中心是否进入缺失区块。

采用局部保守策略：只有查询的实际依赖范围触及缺口时才失去确定性；远处无关缺口不应无差别冻结所有移动。失败处理必须保留合法的 committed geometry 和碰撞所有权，不能报告畅通，也不能静默切原生 AABB。

**完成证据**：区块边缘缺口、material lookup、越出捕获域、形状跨 cell 边界分别可辨识；捕获结束后任何求解查询都不补读实时世界；既有已加载区域内的正常移动不被无关缺口误伤。

### CP1-05　优化不可变碰撞场景内部查询

**定位**：`CapturedCollisionScene.queryFiltered`、`finish`、`blockObstacleAt/blockObstaclesAt/blockObstacle`、`dynamicObstacle`。[S07]

优先为已知热点添加操作局部直接查找：cell 到 primitive 集、完整 rigid identity 到 snapshot。一个 block 可有多个 primitive；物理连续性身份不能退化为仅实体数字 ID。

对重复 broadphase 查询，比较小列表线性扫描与轻量索引的总成本。可复用预排序数据，必要时建立局部空间索引，但必须计入构建时间、分配量、内存及大 primitive 的复制成本。保守 broadphase 不得产生假阴性，动态障碍仍按查询时间窗口做精确过滤。

预算计数及结果顺序继续有效；部分遍历结果不能因换了索引而被当作完整结果。不得把可变候选列表共享给嵌套查询导致互相覆盖。

**完成证据**：定点查找不再反复扫描全表；大场景反复查询减少候选访问；小场景不存在未经解释的额外成本。索引是否启用由测量决定，不能以“必须有 BVH”作为验收。

### CP1-06　让“无影响路径”足够便宜，但不制造假阴性

**定位**：搜索 `hasExternalCollisionProviders`、`MovementExecutionPlan.operationRequired`、操作创建与外部 rigid provider 捕获链。此项修改前须重新核对调用者。

区分“注册了 provider”和“本次操作附近可能有其内容”。能够给出保守范围的 provider 应支持廉价相关性判断；不能证明无关的 provider 继续按保守路径处理。判定必须覆盖 swept 区域、时间窗口和必要的姿态／支撑操作，不只是当前位置。

无来源、完整空来源、已注册但远处无关来源，分别测试。不得用 publication registry 为空、上个 tick 为空、或 optional mod 未显示几何来推断所有 provider 无影响。

**完成证据**：经证明无关的来源不触发无必要的大场景捕获；未知来源不被跳过；无 GE 影响时保持原生语义。optional integration 缺席时不因类加载而失败。

### CP1-07　收敛状态所有权与过度建模

审查 `GravityOperationState`、assignment／application 协调、publication session、持久化与网络边界。先画出事实的写入者和失效事件，再决定删除、合并或保留。

必须保留独立事实：assignment 与 installed application；coverage 与 contribution presence；revision 与 publication 所有权 token；持久化修订与同步修订在语义不同时的区别；操作证据与跨操作支撑连续性。不能为减少字段把它们混为一个状态。

优先删除：重复组合权威、可直接派生的镜像可变状态、无独立策略的层层转发、已无消费者的兼容分支、为了测试布局存在的生产接口。共用生命周期不是建立万能 `Provider<TContext, TResult, TPolicy, ...>` 的充分理由。

不得仅因边缘矩阵提到实体 ID 复用就新增网络 incarnation；不得绕过原生 attachment／clone 生命周期另造复制协议。新增状态必须说明无法由哪个现有 owner 推导，以及其独立失效事件。

架构测试优先验证依赖方向、公开签名和实际所有权。精确文件路径／类名白名单若只固化布局，改为真实边界检查；若确实对应特定平台 seam，则保留并说明理由。禁止为降低失败数量而删行为测试。

**完成证据**：简短的“删除／合并／保留”说明；所有保留或新增可变状态均有唯一 owner；没有第二套求值／提交／来源管理系统。类数和行数不是通过指标。

### CP1-08　补失败归属、工作量边界和低成本诊断

复用已有 runtime phase、重入保护、`CollisionWorkBudget/Tracker` 与任务预算。核对 provider 的 factory、onOpen、evaluate、publication、close 各阶段的合法操作；非法重入／跨 owner 操作要明确失败。

在最外层能准确归属的回调边界补 provider ID、Level／阶段和必要 query 上下文，保留原始 cause。预期数据缺失按契约报告 `INCOMPLETE`；程序错误、null、重复身份或非法数值不得变成 complete-empty。不要 broad catch 后继续假装正常，也不要重复多层包装。

检查候选访问、scene 查询、primitive 输出、窄相位和队列推进是否具有真实工作量边界。结果数量限制不能限制任意 Java 回调内部的执行时间；计时诊断不等于可安全抢占第三方代码。不得引入不安全线程中断“解决”这一点。

诊断默认轻量、聚合／按需启用，不在每实体每 tick 生成大字符串或完整 trace。预算耗尽、覆盖缺失和程序错误要分别统计，阈值依据实际场景调整。

**完成证据**：故障能定位到来源；旧 handle／卸载／初始化失败语义未退化；预算拒绝不产生混合物理状态；默认运行不因诊断显著增负。

### 3.1 CP1 的性能对比与通过条件

使用同一套参数化场景，不为每个修复增加独立 benchmark 框架。优先复用现有 controls／调试入口；现有计数不够时，只补确实会长期使用的低成本指标。

| 场景 | 控制变量 | 主要观察 |
| --- | --- | --- |
| 空世界负路径 | 相同实体数、无来源 | 操作创建、捕获、额外分配 |
| 有 provider 但为空／远处无关 | 固定实体数，改变 provider 数 | 是否仍大规模捕获／重复检查 |
| 场聚合 | 固定候选总量，改变 provider 数；再反向固定 provider 数 | candidates、contains、evaluator、排序 |
| 大范围／无界场 | 局部场数量不变，增加大范围来源 | 索引退化和确定性顺序 |
| 密集碰撞场景 | 相同捕获区域，改变 primitive 数和内部查询次数 | 捕获／索引构建／查询／窄相位分别计时 |
| 高频来源变化 | 已加载区块超过单 tick discovery 额度 | 最老任务年龄、推进、积压、去重 |
| 复杂正确性路径 | 区块边缘、传送、姿态变化、旋转平台 | 尾部成本和失败语义，不只平均 TPS |

报告基线 SHA、修改版 SHA／dirty 状态、JDK、平台版本、JVM 参数、硬件、种子、样本量、预热方式、配置和输入规模。前后使用相同预算；记录中位数、p95／样本充足时的 p99、分配及关键工作计数，不单看平均 TPS。不要在计时循环中打印日志。

若修复缺漏扫描后工作量增加，分别说明“恢复应有工作”的成本与“消除重复工作”的收益；不得让错误基线的跳过行为成为不可回归的性能目标。

CP1 通过需要同时满足：已确认正确性缺陷关闭；重复组合权威消失；重点负载的重复工作有计数或剖析证据；无未解释的显著回归；相关长期不变量和服务器行为验证通过。**不设置凭空的提升百分比。** 未运行性能测量只能标记实现完成、性能未验证，不能宣布性能 checkpoint 通过。

## 4. CP2：API 稳定与文档

### CP2-01　确定受支持面与最小扩展点

将公开能力分为“稳定消费者 API”“明确标记的实验能力”“内部实现”。common API 不泄露 Minecraft、JOML、内部 runtime／碰撞／协议类型；target API 可使用其版本的正常 Minecraft 类型。[S08][S13]

本轮稳定的核心范围：provider 注册／session；query、coverage、contribution；publication 定义和 lease；组合采样；实体只读观察；CP1 所需的有界来源发现接缝；必要的实体能力适配。

DIRECT 实体写入、任意姿态事务、任意碰撞求解器替换和 wire format 不因内部功能已经存在就自动转正。确有消费者需要时列为明确后续范围，而非临时包一个万能控制门面。

每个受支持操作写清：线程和逻辑侧、是否可能创建 session、生命周期、输入验证、返回值所有权／可变性、异常、单位、顺序及兼容性。稳定的是行为契约，不只是包名和方法名。

### CP2-02　让实体观察和采样结果可正确解释

**定位**：`GravityEngineApi.entityGravity`、`EntityGravitySnapshot`、`GravityEvaluationContexts`。[S08]

把 assigned、有效 evaluation、applied 的角色讲清，并在返回值中让消费者识别 evaluation 来源／不可用。可采用可选 evaluation 值或由当前 getter 分支直接构造的来源字段；不要再持久化一份 freshness 状态。

需要能区分活动操作快照、确实仍有效的 tick 求值、assignment／bootstrap 回退。只有实现能够证明上下文有效时才报告有效 tick 求值；不能为了让 getter 看起来实时而放宽 FIELD 缓存校验。

`entityGravity()` 继续纯观察：不创建 session、不调用 provider、不重新采样、不修改 assignment/application。需要实时场结果的消费者显式调用采样入口。`Optional.empty()` 代表没有可用 GE 实体适配／状态，不等于完整空场。

保留或明确迁移 position-only overload 的语义：基线使用零速度、tick 0、interval 0，不是“当前时刻”的简写。不得原名不变却无文档地改成当前世界时间。只在实际使用需要时增加更明确的便利方法。

部分贡献、coverage、零合力和实体 FIELD evidence 不得互相混淆。数值为零时方向／参考系的连续性按既有契约表达，不把它伪装成不存在。

**完成证据**：一个仅使用 API 的消费者能分辨上述状态，且普通观察不会增加 provider 调用计数。

### CP2-03　降低接入样板，规范第三方实体与来源能力

为 publication-backed provider 提供最小便利适配，复用 CP1 查询权威；coverage 仍由拥有来源知识的 provider 决定。不得由 registry 非空、chunk load 或第一次 publish 自动推断完整。

将 CP1 的来源发现接缝整理为消费者可使用的契约，至少示范一个 publication 来源与一个非 publication、有界来源。明确 loaded-only 与卸载后仍生效来源的权威差异；不替内容模组擅自选择持久化玩法。

为实体提供最小能力声明／适配入口，复用现有能力模型。已知原版规则作为默认实现；第三方特殊 `travel`、飞行或弹射实体可明确限制／拒绝对应维度的接管。不能把 `LivingEntity` 继承关系当作支持完整 locomotion 的证明，也不能为求安全把所有未知实体的所有 GE 能力一刀切关闭。

解析顺序及重复注册冲突必须确定；尺寸／姿态变化会影响的能力不能被永久缓存为实体类型常量。不要自动推断任意第三方 travel 的语义；未知特殊行为应要求显式适配并返回可解释状态。

**完成证据**：复用同一个消费者 fixture 覆盖简单来源、动态有界来源、只读实体观察和有限能力声明，不需要导入内部包、反射访问或消费者自己的 Mixin 才能完成这些受支持操作。

### CP2-04　整理 README、AGENTS 和文档事实来源

建议维护以下入口，可与已有有效文档合并，不要求机械创建更多文件：

| 文件 | 唯一职责 |
| --- | --- |
| `README.md` | 定位、实际支持矩阵、最短接入／构建入口、真实文档链接 |
| `AGENTS.md` | 长期依赖／所有权规则、修改流程、精简测试原则；链接具体规范 |
| `docs/API_BOUNDARY.md` | API 范围、关键行为、消费者示例入口、兼容性与迁移 |
| `docs/ARCHITECTURE.md` | 权威与生命周期、操作数据流、关键失败语义；不复制每个类的实现 |
| `docs/DEVELOPMENT.md` | 构建、测试、性能复现、CI／发布流程、target 支持状态 |
| `docs/REFACTOR_STATUS.md` | 本轮两个 checkpoint 的状态、证据、遗留项；不成为第二份架构规范 |

本文 `docs/REFACTOR_PLAN.md` 是执行计划；完成后标明已完成／被当前规范替代，不让历史计划继续成为另一套权威。

不照搬其他项目的“全部 docs 移入 legacy”：只归档实际存在且已失效的计划。基线缺失的文档不假装已移动。保留许可证及第三方来源归属，恢复它们时依据真实历史／源码，不编造说明。

AGENTS 中有效的长期契约保留；长篇运行协议移到对应规范并去重。不能为缩短 AGENTS 把遗漏风险全转嫁给“看代码”。具体数值和算法细节优先由实现／Javadoc 表达，不到处复制。

示例必须由可编译 fixture 提取或同步核对，禁止假方法和伪 Maven 坐标。不得把脚手架 target 写成已验证兼容版本。

### CP2-05　修复构建／CI，并验证真正的成品依赖

修复 workflow 的变更路径：common 变化验证受影响的启用 targets；target 的源码、Mixin／资源和构建变化触发自身验证；共享构建脚本和 wrapper 变化不能漏掉。[S09]

收敛 target 发现规则，复用已有元数据，不维护多份手写列表。根聚合入口正确选择平台命令，不再固定 `cmd /c gradlew.bat`；不可用 target 明确说明状态，不靠静默跳过制造全绿。[S10]

普通构建、JVM controls、完整服务器行为、可选兼容验证和性能测量分别说明。复用已有 `controlCheck`、`verifyApiConsumerImports`、`dynamicsCoreVerification` 等任务，不再包装出多层同义的验收任务。[S12]

同时核对 verification 的 run 配置。基线部分开关通过 `gradle.startParameter.taskNames` 判断用户直接请求的任务；新增聚合入口不能因此漏加载 control mod。以明确的任务／运行配置关系传递验证模式，不依赖用户恰好输入某个名字；保留对缺失新鲜结果的失败判断。[S12]

用真实构建产物做一次独立消费者编译／加载验证，避免只有同仓库源码 classpath 才能成功。核对 common 类／资源打包、mod metadata、必要依赖、可选依赖缺席时类加载，以及公开签名的传递类型。仅检查 import 前缀不足以发现全限定内部类型或签名泄漏。

本地发布验证使用隔离临时仓库即可，不自动上传远程 Maven／发布 release。重型游戏验证可在手动／发布门禁运行，不要求每次纯文档修改重跑全部游戏，但 CP2 宣布稳定前必须有对应的新鲜证据。

### CP2-06　冻结兼容性承诺，而不是冻结内部结构

对公开签名、record 构造、异常、校验、单位、默认时间、顺序、线程和生命周期变化逐项判断兼容性。需要破坏性调整时一次说明迁移方式和版本边界，不长期保留两套新旧权威实现。

只对真实支持、已经验证的 target／loader 组合承诺兼容。持久化与网络内部格式不能被无意间宣称为公共 ABI；本轮也不因整理 API 顺手改变已有受支持存档语义。

**完成证据**：独立消费者使用文档就能构建；关键示例覆盖完整生命周期；新旧 API 行为差异有迁移说明；README、Javadoc、fixture 和发布产物一致；CP1 未验证项没有被包装成“API 已稳定”。

## 5. 边缘情况矩阵与最小测试策略

矩阵用于选参数和确认契约，不要求每个格子新建测试类。未修改且已有证据覆盖的行为直接复用原测试；未发现缺陷的模块不为填矩阵重写。

| 边界 | 必须区分或保持的语义 | 归属 |
| --- | --- | --- |
| 场结果 | complete-empty、complete-present-zero、incomplete-empty、incomplete-partial | CP1-01／CP2-02 |
| 组合与身份 | 多 OVERRIDE、稳定累加、重复 ID、跨 provider 冲突 | CP1-01 |
| 数值／时间 | NaN、Infinity、极小向量、有限值累加溢出、零／负 interval、180°方向变化 | CP1-01／04；API 明确校验 |
| 范围 | 负坐标、边界相切、大范围、源移动／移除、非固定世界高度 | CP1-02／03／04 |
| 生命周期 | 重复 close、旧 handle、同 ID／revision 重用、onOpen 失败、卸载关闭重入 | CP1-08／CP2-01 |
| 回调 | 错线程、递归采样、求值时 mutation、null、异常、超量输出 | CP1-08 |
| 世界证据 | 未加载区块、捕获缺口、查询越界、邻域依赖、材质缺记录 | CP1-04 |
| 操作预算 | 捕获／查询／求解中耗尽、部分结果、diagnostic 关闭与开启 | CP1-05／08 |
| 实体适配 | 普通角色、特殊 travel、飞行／弹射、无适配、尺寸／姿态变化 | CP2-03 |
| 连续性 | 移动／旋转支撑、离开／消失、传送、重生、跨维度、外部位置改写 | CP1-07；沿用原有验证 |
| 网络／保存 | 当前格式恢复、旧结果顺序、对象替换、FIELD seed 仍未知 | CP1-07／CP2-06；不默认新增协议 |
| 调度 | revision 高频变化、任务合并、区块卸载、有限负载无饥饿、持续超载有界 | CP1-03 |
| 对外使用 | 成品 JAR、内部类型不泄漏、optional mod 缺席、失效链接 | CP2-04／05 |

长期保留五类测试即可组织本轮工作，不强制恰好五个文件：**来源生命周期与所有权；场组合／覆盖语义；操作一致性与提交原子性；未知空间／预算失败；调度公平性与公开消费者边界。**

新增用例必须回答“它保护哪个跨重构仍成立的不变量、现有哪条测试为什么不足”。优先扩展现有参数化测试、性质测试和可重放 control 场景。需要验证完整物理对象替换的场景不能只用不经过真实 seam 的 mock 代替。

不新增按 checkpoint 编号命名的整套测试框架；不把类路径、字段数、文件长度和特定调用次数当成长期行为契约。候选工作计数可以用于性能基线，但不把某个具体算法的偶然常数冻结进 correctness suite。

### 5.1 已核对的构建入口

以下任务来自基线，执行前仍检查当前 checkout 的 `tasks --all` 和运行配置。命令是复现入口，不代表本文已执行这些验证。[S09][S12]

```bash
# POSIX；从仓库根目录。使用 bash 可避免源码归档缺少 executable bit。
bash ./gradlew -p common test --console plain

# 进入 target，使用 target 自己的 wrapper 和构建配置。
(cd targets/neoforge-1.21.1 && bash ./gradlew tasks --all --console plain)
(cd targets/neoforge-1.21.1 && bash ./gradlew build --console plain)

# 包含 common、target、JVM controls 与服务器行为验证。
(cd targets/neoforge-1.21.1 && bash ./gradlew dynamicsCoreVerification --console plain)
```

```powershell
# Windows PowerShell；从仓库根目录。
.\gradlew.bat -p common test --console plain
if ($LASTEXITCODE -ne 0) { throw 'common tests failed' }

Push-Location targets/neoforge-1.21.1
try {
    .\gradlew.bat build --console plain
    if ($LASTEXITCODE -ne 0) { throw 'target build failed' }
    .\gradlew.bat dynamicsCoreVerification --console plain
    if ($LASTEXITCODE -ne 0) { throw 'dynamics verification failed' }
} finally {
    Pop-Location
}
```

若需要控制模组开关或其他运行参数，依据当前 Gradle run 配置补齐，不猜测新 task。涉及 Sable 的变更使用已有严格兼容入口并记录 pinned 版本；缺依赖／未运行不能记作通过。首次游戏启动需要的许可和资源配置遵循用户现有授权，不代用户接受未授权协议。

不要用 `build` 成功替代服务器验证，不读取上一次遗留的 PASS 文件作为本次结果；保留已有清理旧结果和生产者执行顺序。[S12]

## 6. Checkpoint 记录与停止条件

只维护一个 `docs/REFACTOR_STATUS.md`，分 CP1、CP2 两节。每节记录：

| 字段 | 要求 |
| --- | --- |
| 输入与结果 | baseline／实现 SHA、工作树状态、目标版本、修改范围 |
| 问题闭环 | 对应本文 ID、修复位置、行为变化、直接证据；非缺陷项说明复核依据 |
| 验证 | 实际执行命令、环境、退出状态、新鲜日志／结果路径；未执行项及原因 |
| 性能 | 相同场景前后结果、关键工作计数、退化解释；无实测就明确留空 |
| 复杂度 | 删除／合并了什么，为什么保留或新增某个状态／索引 |
| 兼容性 | 公开行为变化、迁移步骤、真正承诺的支持范围 |
| 结论 | `PASS`、`FAIL` 或 `BLOCKED`；分别注明实现完成与验证完成 |

`PASS`：本 checkpoint 的必要修复和门禁都有证据。`FAIL`：存在违反门禁的已知行为。`BLOCKED`：环境／依赖使必要证据无法取得。可以交付已完成代码，但不能把 `BLOCKED` 写成“基本 PASS”。

CP1 后停止，不顺手完成 CP2 的 API 扩张。CP2 不绕过 CP1 的未关闭正确性问题；可以整理尚不依赖这些问题的文档，但不能宣布稳定。两个 checkpoint 都不自动 push、打 tag 或发布 artifact 到外部服务。

## 7. 可直接执行的提示词：CP1

将本文放在 `docs/REFACTOR_PLAN.md`，在实际仓库工作区运行以下提示词。

```text
你在 GravityEngine 仓库中工作。本次只完成 Checkpoint 1：性能与过度建模重构。

依据：docs/REFACTOR_PLAN.md，基线 dev SHA 为
186f70fa765e1565c428768a2a5d5af178b0d8fc。
主实施范围为 common 与 targets/neoforge-1.21.1。

先读实际生效的 AGENTS、本文、相关源码／调用者、现有测试和 Gradle 配置。
检查 git status 和 HEAD；有新增提交时对照基线重新定位问题，保留用户改动，
不要 reset、强行回退或覆盖无关文件。源码和可复现证据优先于此前审查结论。

目标不是提交另一份方案。完成源码修改、必要验证和 checkpoint 记录。
按下列工作顺序执行，内部可分小步骤，但不要增设第三个 checkpoint：

1. 固定可复现基线，确认构建、controls 和现有性能／诊断入口。
   列出待改事实的 producer、唯一 owner、consumer、lifetime、invalidation。
   为定向复核项确认真实调用链；已无问题时提供证据，不人为重造问题。
2. 完成 CP1-01、02：统一 provider 聚合与组合权威；删除第二套生产组合逻辑；
   消除全局 publication 重复查询、重复 contains 和不必要排序。
   coverage 不随 OVERRIDE 被短路；零合力仍可 present；不使用跨 tick 假缓存。
3. 完成 CP1-03：让非 publication 的有界 provider 接入方块影响发现；
   发现提示不等于 coverage。修复连续 revision 更新重置 discovery 的饥饿；
   合并任务、维持游标推进，处理移除／卸载／原生 tick 去重。
   不强加载世界，不用无界扫描掩盖协议缺口。
4. 完成 CP1-04、05：补操作局部的真实捕获覆盖；所有依赖查询不将缺口当空气。
   检查形状越界和邻域依赖；禁止求解阶段回读世界或非法切换原生 AABB。
   优化 scene 定点查找和重复 broadphase，测量索引构建与小场景开销。
5. 完成 CP1-06～08：优化可证明无关的负路径；收敛镜像状态／无策略转发；
   保留独立权威和原生生命周期；补来源明确的异常、工作预算和低成本诊断。
   新增状态必须有独立事实和失效事件，不新增万能 provider／manager 框架。
6. 按本文场景在同环境比较基线与修改版，分开记录正确性补全成本和优化收益。
   复用现有测试与 controls，仅为未覆盖的长期不变量增加最小用例。
   执行 common tests、target build 及相关完整服务器验证；性能不编造数字。
7. 更新 docs/REFACTOR_STATUS.md 的 CP1 节，记录每个 CP1 ID 的处理、命令、
   真实结果、性能计数、删除／保留复杂度的理由和仍未验证项。必要时修正
   与本次明确契约变化冲突的 Javadoc／规则，但不要提前宣布 API 稳定。

禁止：吞异常返回空场；用 registry 空证明世界无场；取消未知空间检查换速度；
为减少类数合并 assignment／application／geometry；新增 readiness generations；
改造无证据相关的网络／存档协议；为验收建立大型新框架；自动 push／tag／发布。

环境阻塞时继续完成不受阻的工作，清楚记录 BLOCKED，绝不将编译成功冒充
服务器或性能通过。不要因为任务较长就停在计划或反复请求确认。

最终输出：改动摘要；CP1-01～08 状态；实际验证与性能结果；残余风险；
CP1 的 PASS／FAIL／BLOCKED。完成本 checkpoint 后停止，不继续 CP2。
```

## 8. 可直接执行的提示词：CP2

在 CP1 结果已审阅后运行以下提示词。若实际代码已前进，使用记录中的真实 CP1 输出作为输入，不退回原始 SHA。

```text
你在 GravityEngine 仓库中工作。本次只完成 Checkpoint 2：API 稳定与文档。

读取实际生效的 AGENTS、docs/REFACTOR_PLAN.md、docs/REFACTOR_STATUS.md、
CP1 后的真实代码和构建产物配置。原始审查基线是
186f70fa765e1565c428768a2a5d5af178b0d8fc；不要用它覆盖 CP1 的工作。
检查工作树，保留用户改动。若 CP1 门禁未通过，只能修补阻塞本阶段的必要
缺陷并记回 CP1；不能忽略它们宣布 API 稳定，也不要借机重启核心重写。

本次要交付可使用的公开 API、真实消费者证明与维护文档，不只是文档草稿。
按 docs/REFACTOR_PLAN.md 的 CP2-01～06 完成：

1. 盘点真正受支持的消费者操作，逐项规定线程／侧、生命周期、单位、校验、
   返回值所有权、失败、顺序和兼容性。common 不泄漏平台类型；API 不泄漏
   内部 runtime／碰撞／协议／JOML。内部 public 不自动转正。
2. 修正 entityGravity 的可解释性：消费者可分辨 assignment、有效 evaluation
   与 applied，并知道回退来源。保持 getter 不创建 session、不调用 provider、
   不求值、不改状态。FIELD 缓存有效性不能为便利而放宽。
   position-only sample 的零速度／tick 0／interval 0 语义须保留或明确迁移。
3. 以 CP1 唯一权威路径提供最小 publication-backed 便利适配与有界来源发现
   接缝。coverage 仍由 provider 决定。为第三方实体提供有限能力声明／适配，
   使用现有能力模型、确定的选择顺序和动态失效规则，不暴露任意求解器替换。
4. 复用一个消费者 fixture 覆盖发布／替换／关闭、非 publication 有界来源、
   完整与不完整采样、实体观察及必要能力声明。由实际可编译代码生成文档
   示例，不用不存在的方法或伪 Maven 坐标。
5. 从实际成品 JAR／隔离本地发布产物进行独立消费者编译和必要加载验证，
   检查传递公开签名、common 打包、资源、mod metadata 和 optional 依赖缺席。
   修复 CI 路径遗漏及 Windows-only 根执行入口；统一 target 发现，复用已有
   verification tasks。普通 build、JVM controls、游戏行为、性能和可选兼容
   证据分别说明，不让新包装任务重复执行同一套工作。
6. 更新 README、精简 AGENTS，维护 API_BOUNDARY、ARCHITECTURE、DEVELOPMENT
   和 REFACTOR_STATUS。只保留真实支持矩阵和可用链接；归档实际过时文档，
   不机械清空 docs，不丢失许可证／归属。不复制完整实现细节到多份规范。
7. 对签名、异常、默认时间、校验、返回状态和生命周期变化给出迁移说明。
   稳定范围是已验证的 common + neoforge-1.21.1 API；其他 target 按实际证据
   标状态，不顺便完成全面移植。DIRECT 写入、任意姿态事务、碰撞 provider
   与 wire format 没有新需求和验证时继续保持明确的内部边界。
8. 执行相关既有 tests／controls／服务器验证、独立消费者验证与文档链接检查。
   修改触及 CP1 热路径时重跑受影响性能场景。更新单一 REFACTOR_STATUS 的
   CP2 节，注明版本边界、实际运行命令、结果、未验证项和残余限制。

测试仅维护长期不变量，优先扩展现有 fixture／参数化场景，不为各个新增类
创建验收测试，不用精确文件名／类数量冻结布局。可执行证据缺失则 BLOCKED，
不能用“文档补了”关闭运行时缺陷或将旧 PASS 文件当成本次结果。

不要自动 push、打 release tag 或向远程仓库发布。最终输出 CP2-01～06 的
闭环状态、公开行为迁移、消费者证明、实际支持范围与 PASS／FAIL／BLOCKED。
本 checkpoint 完成后停止，不继续扩展范围。
```

## 9. 基线源码索引

以下链接固定到审查 SHA，用于定位问题，不要求保留当前类名或目录。尚未复核的风险在实现时通过 `rg` 搜索符号及调用者，不根据猜测路径修改。本文未执行 Gradle、游戏内验证或性能基准。

- **[S01]** [基线提交](https://github.com/Mafuyu404/GravityEngine/commit/186f70fa765e1565c428768a2a5d5af178b0d8fc)。
- **[S02]** [README：定位、权威 target 与文档入口](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/README.md)。
- **[S03]** [GravityFieldRuntime：provider 聚合、publication 查询、重入和生命周期](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/field/GravityFieldRuntime.java)。
- **[S04]** [GravityFieldService：两条求值／组合路径](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/common/src/main/java/cc/sighs/gravityengine/gravity/field/GravityFieldService.java)。
- **[S05]** [FallingBlockRechecks：publication 发现与逐 tick 调度](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/FallingBlockRechecks.java)。
- **[S06]** [MinecraftCollisionSceneCapture：世界捕获与未加载区块跳过](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/collision/MinecraftCollisionSceneCapture.java)。
- **[S07]** [CapturedCollisionScene：包络检查、材质及线性查询](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/common/src/main/java/cc/sighs/gravityengine/gravity/collision/CapturedCollisionScene.java)。
- **[S08]** [GravityEngineApi：公开入口、采样默认值与实体观察回退](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/api/GravityEngineApi.java)。
- **[S09]** [verify-common.yml：变更路径和构建矩阵](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/.github/workflows/verify-common.yml)。
- **[S10]** [根 build.gradle：聚合 target 和平台执行命令](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/build.gradle)。
- **[S11]** [基线根目录树](https://github.com/Mafuyu404/GravityEngine/tree/186f70fa765e1565c428768a2a5d5af178b0d8fc)。
- **[S12]** [NeoForge 1.21.1 build.gradle：build、controls、服务器门禁与打包](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/targets/neoforge-1.21.1/build.gradle)。
- **[S13]** [AGENTS：现有 API、权威、持久化和生命周期约束](https://github.com/Mafuyu404/GravityEngine/blob/186f70fa765e1565c428768a2a5d5af178b0d8fc/AGENTS.md)。
