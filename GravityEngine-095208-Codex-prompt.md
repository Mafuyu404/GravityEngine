# GravityEngine correctness 修复与 Sable 兼容实施提示词

以下内容可整体交给有 GravityEngine 仓库访问权限的 Codex 执行。基线为 `GravityEngine-sources(20260919-095208).zip`；若工作区已有后续改动，先核对等价路径并保留用户改动，不要回退到该快照。

---

请直接检查和修改代码，修复 GravityEngine 本体剩余 correctness 问题，并完成 NeoForge 1.21.1 / Sable 2.0.5 的兼容目标。不要只给设计建议、源码字符串断言或兼容性口号。先修纯引擎与运行时契约，再接 Sable，并使用生产路径的行为测试证明结果。

## 1. 工作边界

1. 先读 `AGENTS.md`、`docs/API_BOUNDARY.md`（若工作区提供）、构建配置以及现有碰撞、支撑、运行时、Sable 适配代码。此次 sources 快照没有包含该 API 文档；若当前仓库也缺失，报告缺失并依据实际 API/Javadoc 核对，不假装读过。版本事实以工作区实际文件为准。
2. `common` 保持 Java 17、独立编译，不引入 Minecraft、loader、Mixin、Sable、Create 类型或依赖；NeoForge 1.21.1 target 使用 Java 21。其它版本目录不能因本次修改自动宣称兼容。
3. 通用算法与不可变契约放在 common；版本、线程、生命周期和坐标转换放在 target。不得在 Sable adapter 复制另一套重力或碰撞求解器。
4. 现有外部刚体 provider 是引擎内部扩展点，不因 `public` 自动变成受支持 API。同仓引擎适配器使用内部实现时保持内部定位；若要让独立内容模组接入，先定义最小受支持 API，并同步 Javadoc、API_BOUNDARY、示例和测试，不向公共签名泄漏内部/平台类型。
5. 主世界自动高度重力场已从本体移除，保持该结果。通用高度场工厂和卸载清理保留。主世界的高度阈值、默认注册与玩法配置由 StarminerR 通过受支持 API 持有；不得在本体增加“检测到 Starminer 就自动注册”的分支。若没有 StarminerR 仓库，说明其侧迁移未验证，不伪称已经完成。
6. 本任务允许修复错误的内部契约，但不允许删测试、改低阈值、禁用动态碰撞或吞掉异常来制造通过。不要进行无关重构。

## 2. 已完成的基线，不要反复重做

095208 已合入上一轮修复：释放速度按当前合格 publication 重新获取；区分已消费同 revision 的末端速度与新 publication 的起始速度；接受跳跃后清理支撑状态；packet 候选/primitive/narrow-phase 计费与接收后路由登记；`dynamicsCoreVerification` 显式依赖 common 测试；删除主世界自动场加载。

现有 common 251 项测试已在审查环境用 Java 17 离线运行通过。审查使用本地 JUnit Console 1.10.3 / JUnit Jupiter 5.10.3，未通过仓库请求的完整 Gradle/JUnit 5.11.4 构建链；因此这不是正式 target 构建或游戏验收。Gradle wrapper 下载在该审查环境受 `services.gradle.org` DNS/网络限制。你应在当前环境重新运行可用的正式验证，不照抄审查结果。

上一轮修复的是释放速度的采样时序，尚不能据此宣称速度所有权与双计数问题已经闭环。

## 3. 优先修复引擎 correctness

### A. 已消费的旋转 transport 仍被后续请求使用（已复现）

检查：

- target 的 `gravity/integration/EntityMovementIntegration.java`
- target 的 `gravity/integration/collision/GravityCollisionEngine.java`
- common 的 `gravity/runtime/GravityOperationState.java`
- common 的 `gravity/collision/SupportedCharacterRoute.java`

当前流程：integration 仅在 pending 时把 transport displacement 加入请求，然后在 solve 之前 consume；但 solver 无条件读取 `engineSupportTransport()`。后者在 consume 后仍存在，因此同一 outer operation 的后续独立请求仍会选中旋转轨迹路径。该路径假设 request 已包含 transport 弦位移，先减弦再分段加圆弧；不含 transport 的请求会走出错误轨迹。

审查反例：消费 transport 后 `pending=false`，但 `selectableTrajectory=true`。对零位移请求，普通路径结果是零；复用旧旋转轨迹并在虚假弧段放置静态障碍后，得到约 `(-0.0606111258, 0, 0)` 的非零侧移，`blocked=true` 且 `indeterminate=false`。这是 common 路径与运行时选择条件的复现，尚非游戏端到端测试。

修复要求：

- 用 solve-local 的 transport/运动来源信息证明“这一请求确实拥有这一段 transport”；不要由全局残留状态推导。
- 不要只把 solver 改读 pending：当前消费发生于第一次 solve 之前，这会误伤合法首个旋转求解。
- 第一次请求应用一次 carry/arc，后续独立请求不重放；重入复用仅限同一个物理请求，身份应包含所属 operation 与必要的 body/time/request 语义，不能只比较输入向量。
- 检查 `preResolvedTranslation`、相同数值但不同来源的第二次移动、不同向量的嵌套移动、SUPPORT_TRANSPORT 辅助请求、活塞/外部移动、packet 路径。不要全局缓存一次结果后拒绝一切后续有效请求。
- 增加回归测试，至少覆盖“consume 后第二次零移动遇到虚假弧段障碍仍为零”和“第一次真实旋转 carry 仍被障碍正确约束”。

### B. 外部刚体 broadphase 把相交错误当成完全包含（已复现）

检查 common 的：

- `gravity/collision/DynamicEntityBroadphasePolicy.validateProviderPublication`
- `gravity/collision/provider/RigidCollisionPublicationRegistry`
- `ExternalRigidCollisionQuery`、scene builder 与 packet collector。

当前要求 primitive 的完整 `initialBounds` 包含在 `query.dynamicBounds()` 内。这是 actor 的发现查询范围，不是发布者声明的几何容器。与查询相交的大地板、长墙、船体 primitive 可以合法地超出该范围，不能因此视为无效。

审查反例：corridor 为 `[-1,1]^3`，按现有常量扩展查询；原点处 halfExtent=100、零线速度、零角速度、tick/interval 合格的刚体确实相交且 material-point reach=0，却抛出 `rigid provider publication outside discovery bounds`。

修复要求：

- 明确区分 query relevance、发布者自有 discovery bounds、几何包含关系与 material-point motion reach。
- 使用保守 swept bounds 做相关性判断；允许超出查询边界但能相交的完整 primitive。无关几何在明确的筛选层处理。
- 保留有限值、tick/interval、provider/source/epoch/revision、primitive 唯一性和最大点位移验证。旋转位移界不能仅由质心速度决定。
- 不通过裁剪原几何、重置局部锚点或每次查询生成不稳定 primitive ID 来规避校验。
- 保留 budget 的有界失败语义。预算失败、无法证明覆盖或不合格 publication 不能伪装成“无障碍”。不得声称引擎预算能约束任意 provider 内部耗时或 Minecraft 空间索引内部遍历。
- 测试大静态地板、跨查询边界长墙、合法旋转的大 primitive、超运动界、无关几何、重复 identity 与 budget exhaustion；普通移动和 packet capture 必须保持一致语义。

### C. 世界速度、支撑携带与释放继承可能双计数（高优先级待生产链验证）

检查 target 的 `ContactVelocityIntegration`、`GroundAirGravityMovementHandler`、`LivingEntityTravelMixin`、`EntityMovementIntegration`、`LivingGravityIntegration.commitJump`、`GravityOperation.close`，以及 common 的 `CurrentContactConstraintBuilder` 和 `SupportTransportResolver`。

源码存在需要闭环证明的组合：

- persistent `deltaMovement` 被声明为世界空间物理速度；
- 移动表面接触约束 `n·v >= n·surfaceVelocity` 会把平台法向速度写入该速度；
- 下一移动又加完整支撑 displacement；
- 跳跃/走离支撑时又可能把完整释放速度加到已有世界速度上。

最小数学反例：升降平台每 tick 上升 0.1，接触投影把角色世界速度设为 `(0,0.1,0)`，若下一请求把它直接作为 actor movement 再加 carry `(0,0.1,0)`，请求变成 0.2。该算式及 projector 结果已复现，但尚未用完整生产 travel 跨 tick 链证明触发条件。先增加生产链行为测试定位，若已有扣除机制能避免，应给出调用路径与行为证据，而非按审查推测盲改。

修复/证明要求：

- 明确定义持久速度、相对支撑的 locomotion、显式 carry、接触响应与 release impulse 的所有权、单位和采样时刻。
- 一次 support contribution 只能计入一次；不能无条件减去完整 surfaceVelocity，因为不同世界速度可能包含外部冲量、主动输入或不同来源的平台贡献。
- 静止乘客正确跟随平移/旋转平台，不平白加速；平台加速、反向与突然停止也成立。
- 跳跃保持合法切向动量，释放速度继承一次；走离、支撑删除、epoch 改变与 teleport 不复用失效速度。
- 以至少连续数 tick 的生产 movement/contact/travel/close 顺序验证，记录请求位移、已应用 carry、最终位移、world velocity、release contribution；不要只测试向量加法辅助函数。
- 覆盖上下升降、水平运动、加速、旋转、斜面、外部冲量、主动走动、跳跃取消与接受。分别说明正常物理离开和来源失效的处理策略。

### D. 默认向下重力的碰撞路由不能遗漏外部几何（Sable 接入前置缺口）

当前 `GravityInfluencePolicy.collisionRoute` 对默认参考几何通常选择 VANILLA，server packet 的自定义占用校验也受 EXACT_BODY 门控。仅注册 external provider 并不保证这些角色会查询它发布的几何。

需要把“是否需要参考旋转”与“当前移动是否由引擎负责外部几何碰撞”分开：

- 根据明确的 capability/actor/operation 所有权选择并冻结路由，不要依赖有没有非默认 down。
- Sable 存在但与当前实体/移动无关时保持原版行为，不要仅因全局安装该模组就接管全部实体。
- 避免为选择路由重复捕获 publication；若需要发现阶段，设计其与唯一权威快照的关系。
- 默认向下、倾斜重力、任意方向、zero gravity 场景均应进入符合实际所有权的路由；参考几何切换、server 校验与客户端预测必须一致。
- 先用 fake provider 做默认重力的真实 target 行为测试，再用 Sable 测试。仅证明 registry 能返回几何不等于实体已经使用它。

## 4. 实现 Sable 2.0.5 兼容目标

### 4.1 先核对真实接口和运行时插入点

- 从 Gradle 配置、缓存、依赖源包/实际 jar 核实 Sable 2.0.5 的 sublevel 几何、刚体 pose/velocity、碰撞入口、重力入口、tick/thread、客户端同步及渲染接口，记录对应版本和位置。
- 不能凭类型名猜测反射方法签名，也不能用宽泛 catch 后返回零把不兼容隐藏成成功。
- 如果依赖无法取得，完成不依赖 Sable 的 A–D 工作、adapter 契约和 mock 测试，列出精确阻塞接口；不要伪称真实 Sable 已接通或要求用户提供全部项目才能开始。

### 4.2 引擎拥有角色的一次权威碰撞求解

- 实现并接入生产 `ExternalRigidCollisionProvider`：稳定 provider namespace、sublevel source ID、连续性 epoch、primitive ID、revision、不可变局部形状和同一物理区间的刚体运动 publication。
- 通过空间索引发现相关 sublevel/primitive，不全世界扫描；resolve 按 identity 重新获取当前 publication，不把空间重搜当作身份恢复。
- 证明起始/末端 pose、角速度参考系、旋转中心、材质点局部锚点及时间单位的转换。单位换算只在边界执行；blocks/s 与 blocks/tick 的换算需验证，不能推广到加速度时仍只除以 20。
- 对加速或非恒定角速度，明确当前求解器支持的区间轨迹模型；用与实际采样/推进一致的 publication 或保守分段方案，不把任意末端 pose 与不一致的恒定速度拼成“精确”轨迹。
- 普通世界方块、原生动态物体、Sable 几何进入同一 operation 的权威场景；actor 请求由一个 solver 决定最终位移。
- 对 GE 接管的 actor，Sable 的原生 collision/clipping/inherited-motion 不得再改变同一次移动。GE 未接管的 actor 继续原生行为。定位并调整真实版本的最小 Mixin seam，证明没有双求解或遗漏回调。
- 审查现有 `SableMovementAdapter`、`SableMovementCompatibility`、`SableSubLevelEntityCollisionMixin`、`SubLevelMovementPolicy`：明确保留用途或移除已被替代的接管分支，不让旧 post-solve evidence 与新 provider 同时提供 carry。
- GE actor 的 OBB/capsule 必须保持实际碰撞形状；不得用 AABB 或另一类 body 替代窄相位权威结果。

### 4.3 持续支撑与生命周期

- first collision、碰撞前速度和 tracking 信息只能作为路径证据；不能直接制造 terminalSupported、可信 block face 或跨 tick 的 PRESERVE_SUPPORT。
- 终点支撑由统一求解器提供真实 witness。保存 provider/source/epoch/primitive identity、局部接触点及需要的参考信息，再按下一 publication 恢复。
- 旋转 carry 使用真正材质点轨迹，纳入碰撞，不做求解后的无碰撞位置修正。
- 支撑进入/持续/切换/离开、跳跃、走离、刚体删除或替换、维度切换、Level 卸载均有明确清理和失效语义；不得留下旧 provider route、旧 pose 或旧 release impulse。
- 注册与注销分别处理服务端和客户端 Level。检查 WeakHashMap 的 value 间接强引用 key 的循环，不能仅凭 weak key 宣称没有 Level 泄漏。

### 4.4 服务端移动包与客户端一致性

- server 的 old/new occupancy 检查使用同一次、同一 interval/revision 的 scene capture，包含同一批 Sable 动态几何；不得分别读取两次变化中的船体状态。
- 沿用并验证候选、primitive 和 narrow-phase 预算；两端占用各自真实收费，预算失败 fail closed，不接受未经验证的 packet。
- 默认向下与任意重力都覆盖；父世界与 sublevel 同时接触、旋转夹角和移动墙体不能因 Sable 分支绕过碰撞校验。
- 说明服务端权威和客户端预测/插值的关系，禁止为了消除回弹简单禁用 server 校验。

### 4.5 GravityEngine → Sable 重力桥接

- 接通引擎重力场到 sublevel 刚体动力学的版本适配入口，明确谁拥有最终重力加速度/力；不得同时累计 GE 重力与未被替换的 Sable 默认重力。
- 定义采样坐标、参考系、时间、单位与刚体质量参与位置；加速度不是直接当力使用，blocks/tick² 与 blocks/s² 不混用。
- 明确非均匀重力的产品语义：单点/质心加速度与多点产生力矩不同。先核对已有目标；不要把仅质心采样宣称为完整潮汐力矩模拟，也不要未经依据强加多点算法。
- zero sample 是有效贡献，不等于“字段不存在”；absence、ADDITIVE/OVERRIDE 与禁用桥接的恢复语义保持引擎契约。
- 角色自身受场和 sublevel 受场分属各自物理对象，避免 actor 因附着又获得同一份额外重力。

### 4.6 相机、朝向与其他玩法路径

- body attitude、gravity reference、sublevel attachment、camera/view 和 body yaw 的所有权明确；sublevel 旋转不得重复应用到物理朝向或相机。
- 客户端显示使用与物理有明确对应关系的已提交状态/插值，不能让渲染写回服务端物理状态。
- 验证站在平移/旋转 sublevel 的第一人称、第三人称、离开/跳跃、gravity reference 改变与客户端纠正；关注双倍旋转、闪跳和脚点漂移。
- 单独核查流体、攀爬、飞行与 FallingBlock 放置/落地语义。这些不能由 ground/air 通过自动推导为兼容。若本轮无法闭环，在交付中逐项列出确切未支持行为与边界，不宣称“完整 Sable 兼容”。

## 5. 必须执行的验证

先把 A/B 的反例转成失败的回归测试；C 使用跨 tick 的生产链测试；D 使用默认重力、真实 target 路由与 fake provider 的行为测试。再实现修复，最后做 Sable 真实运行矩阵。测试必须执行被修改的生产路径，不能只断言源码里出现某个单词、Mixin annotation 或任务依赖。

最低矩阵：

| 维度 | 必测项 |
|---|---|
| 可选依赖 | 未安装 Sable 的 client/server 正常加载；已安装 Sable 的真实接口执行 |
| 重力 | 默认向下、墙面、天花板、倾斜与零加速度 |
| 刚体运动 | 静止、平移、升降、加速/反向、旋转 |
| 角色行为 | 静止乘坐、走动、跳跃、走离、外部冲量、嵌套/重复移动 |
| 碰撞 | 父世界与 sublevel 同时碰撞、弧段受阻、大几何跨查询边界 |
| 身份和生命周期 | primitive/source 替换、epoch/revision 变化、卸载、删除、维度切换 |
| 网络 | old/new occupancy 同快照、预算失败、客户端预测与 server 纠正 |
| 表现 | body/camera/yaw 单次变换、第一/第三人称、进出支撑 |
| 重力桥 | 非零/零/absent 场、开关恢复、单位与重力只施加一次 |

按仓库实际任务和依赖执行以下验证，先确认任务可用：

```bash
./gradlew -p common clean test --console plain --no-daemon
./gradlew -p targets/neoforge-1.21.1 build
./gradlew -p targets/neoforge-1.21.1 dynamicsCoreVerification
```

该快照的 target 是独立 Gradle project，根项目只 include common；因此使用 `-p` 进入 target，不能杜撰根项目下的 `:targets:...` 任务路径。Windows 可使用相应 `gradlew.bat`；根项目的 `-Ptarget=neoforge-1.21.1 build` 聚合当前调用 `cmd /c gradlew.bat`，Linux 验证应直接调用独立 target。不要把任务名称存在当成已经运行。目标测试、Mixin 应用检查、dedicated server smoke 与真实 client 行为分别记录；JUnit、字节码 contract、mock provider 和 Sable 实机验证不能互相替代。

若环境阻塞构建或运行，记录命令、失败阶段及确切原因；继续完成能独立执行的实现和验证，不虚构通过结果。

## 6. 交付要求

请在工作区实现修改，最终提供：

1. correctness 结论，区分已修复且验证、已实现但未运行验证、仍未完成。
2. 对 A–D 每项给出根因、修复位置、行为变化与回归测试；若审查假设被证伪，给出生产调用链和测试依据。
3. 一张所有权表：角色位移、persistent velocity、support carry、release impulse、sublevel 重力、server 占用校验、body/camera 各由谁产生和提交。
4. Sable 2.0.5 具体接口/插入点与生命周期说明，以及旧兼容分支如何退出。
5. 实际执行的命令、测试数量、运行矩阵结果、环境阻塞和剩余风险。不能只写“build passed”就宣称完整兼容。
6. 修改文件清单。若交付文件内容，提供完整可替换文件或完整方法及精确位置，不只给 unified diff。更新相应文档，清理把已移除的主世界默认场仍描述为本体行为的陈旧说明。

除非确有无法推断的语义选择或访问阻塞，持续完成上述工作，不在给出计划后停止。

---

## 审查反例的原始观测

以下是 095208 上的独立 Java harness 输出，供对照；不是完整游戏验收：

```text
LARGE stationary intersecting primitive rejected: rigid provider publication outside discovery bounds
CONSUMED pending=false, selectableTrajectory=true
ZERO SECOND REQUEST ordinary=Vec3d[x=0.0, y=0.0, z=0.0], stale-trajectory=Vec3d[x=-0.06061112577562154, y=0.0, z=3.5388358909926865E-16], blocked=true, indeterminate=false
ELEVATOR projectedWorldVelocity=Vec3d[x=0.0, y=0.1, z=0.0], nextWorldVelocityPlusCarry=Vec3d[x=0.0, y=0.2, z=0.0]
```

旋转反例构造：在 tick 100、interval 1 内，用原点为旋转中心、角速度 `(0,1,0)`、局部锚点 `(3,0,0)` 构造 trajectory；transport 是整段弦位移。消费后，在锚点末端上方 1 格放置 halfExtent 0.03 的 actor，对零请求求解；在 actor 中心加上 `trajectory.displacement(0,0.5) - 0.5 * trajectory.displacement(0,1)` 处放置同尺寸静态障碍。普通 route 保持零，错误重用 transport 的 route 产生上述侧移。回归还应加入合法首次 carry 与 operation 重入场景，避免仅禁止所有旋转路径。
