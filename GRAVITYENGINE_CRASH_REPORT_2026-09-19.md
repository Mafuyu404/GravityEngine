# GravityEngine：实体摔落死亡时嵌套尺寸刷新导致服务器崩溃

## 结论

2026-09-19 18:09:55 的崩溃属于 GravityEngine 1.21.1 目标的实体移动／几何事务集成问题。牛在移动过程中承受致命摔落伤害，原版死亡逻辑切换 `Pose.DYING` 并同步刷新实体尺寸；GravityEngine 此时仍处于移动操作中，且没有几何更新事务承接此次刷新，因此主动抛出异常，导致内置服务器退出。

结论依据是本次日志、崩溃报告、版本匹配的 Minecraft 源码，以及**实际加载的 GravityEngine JAR 字节码**。未修改 GravityEngine 或游戏存档，也未进行游戏内独立复现。下文复现步骤与修复方案是供引擎维护者验证的建议。

## 环境与产物标识

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.249 |
| Java | Oracle 21.0.11 |
| 系统 | Windows 11 amd64 |
| 运行方式 | 开发客户端，内置服务器 |
| StarminerR | 1.0.0 |
| GravityEngine 模组版本 | 0.0.1 |
| Maven 坐标 | `cc.sighs.gravityengine:GravityEngine-neoforge-1.21.1:0.0.1-SNAPSHOT` |
| 引擎网络协议日志 | 18 |
| JAR SHA-256 | `dd059d15c264cca8ae3867a66a54045dbf713155be5af87e0113b64d8d2c54ca` |

实际加载文件由 `run/logs/debug.log:80` 确认：

```text
C:/Users/RNaim/.gradle/caches/modules-2/files-2.1/cc.sighs.gravityengine/GravityEngine-neoforge-1.21.1/0.0.1-SNAPSHOT/2223e5dca91265ceba5b6f9da995ae0b360b5ab9/GravityEngine-neoforge-1.21.1-0.0.1-SNAPSHOT.jar
```

缓存中存在多个同名 SNAPSHOT，排查或验证修复时应以该路径和指纹区分，不能只看版本号。相邻 GravityEngine 工作区的源码行号与运行堆栈不完全一致，因此本报告另行核对了已加载 JAR 的相关分支。

## 日志证据

- 主日志：`run/logs/latest.log:76–131`。
- 调试日志：`run/logs/debug.log:699–755`。
- 崩溃报告：`run/crash-reports/crash-2026-09-19_18.09.55-server.txt`。
- 18:09:31 玩家进入世界；18:09:55 服务端出现 `Ticking entity`，随后停止服务器。
- 故障实体为 `minecraft:cow`，实体 ID `53`，位置 `(-189.66, 99.00, 337.02)`，方块坐标 `(-190, 99, 337)`，动量 `(-0.02, -1.70, -0.15)`。
- 世界为 `minecraft:overworld`，游戏时间 `2006`，加载实体数 `207`。

异常及关键栈帧（省略模块前缀与重复 Mixin 元数据）：

```text
java.lang.IllegalStateException:
  unowned refreshDimensions inside an active gravity operation
Entity.wrapMethod$zzn000$gravityengine$refreshDims(Entity.java:4834)
Entity.refreshDimensions
Entity.onSyncedDataUpdated(Entity.java:2951)
LivingEntity.onSyncedDataUpdated(LivingEntity.java:3209)
AgeableMob.onSyncedDataUpdated(AgeableMob.java:122)
SynchedEntityData.set
Entity.setPose$mixinextras$wrapped$579(Entity.java:366)
Entity.wrapMethod$zzn000$gravityengine$poseDimensionTransaction(Entity.java:4559)
Entity.setPose
LivingEntity.die(LivingEntity.java:1437)
LivingEntity.hurt(LivingEntity.java:1266)
LivingEntity.causeFallDamage(LivingEntity.java:1657)
Block.fallOn(Block.java:434)
Entity.checkFallDamage(Entity.java:1195)
LivingEntity.checkFallDamage(LivingEntity.java:375)
Entity.redirect$zzn000$gravityengine$fallDamage(Entity.java:5226)
Entity.move$mixinextras$wrapped$581(Entity.java:671)
EntityMovementIntegration.runOwnedMove(EntityMovementIntegration.java:397)
EntityMovementIntegration.moveObserved(EntityMovementIntegration.java:356)
EntityMovementIntegration.move(EntityMovementIntegration.java:325)
Entity.wrapMethod$zzn000$gravityengine$moveWithGravity(Entity.java:4597)
...
LivingEntity.wrapMethod$zbk000$gravityengine$travelScope(LivingEntity.java:5426)
LivingEntity.travel
...
ServerLevel.tickNonPassenger
```

## 根因与责任边界

触发顺序是：

```text
GravityEngine travel / move 操作
  → 原版落地与摔落伤害
  → LivingEntity.die
  → setPose(Pose.DYING)
  → SynchedEntityData.set → onSyncedDataUpdated
  → refreshDimensions
  → GravityEngine 检测到活动移动操作，但没有几何更新所有者
  → IllegalStateException → Ticking entity → 服务器停止
```

版本匹配的 Minecraft `LivingEntity.java:1437` 明确执行 `this.setPose(Pose.DYING)`，因此这不是根据实体类型猜测的死亡路径。

实际 JAR 中 `EntityMixin.gravityengine$refreshDims` 的字节码确认了以下分支顺序：

1. `!GravityInfluencePolicy.usesCustomBody(entity)` 时直接调用原版刷新并返回。
2. `runtime.isApplyingGeometry()` 时允许刷新，并记录尺寸变化。
3. 否则，`runtime.isInMove()` 为真时抛出本次异常；抛出指令位于该方法字节码偏移 `104–114`。
4. 只有通过上述检查，才进入独立尺寸刷新事务。

同一 JAR 的 `gravityengine$poseDimensionTransaction` 仅保存并恢复 `gravityengine$poseBeforeRefresh`，中间调用原版 `setPose`；它没有在此建立几何更新所有权。正常的原版死亡回调因此落入了“移动中发生未声明的尺寸更新”保护分支。

相邻源码中的定位位置（只读核对，可能继续变化）：

- `D:/javaProjects/GravityEngine/targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/EntityMixin.java:151`：姿态包装方法。
- 同文件 `:389`：尺寸刷新包装方法；`:413`：几何事务分支；`:428–430`：移动中直接抛异常。
- `EntityMovementIntegration.runOwnedMove`：需要与嵌套死亡／尺寸更新协调的移动操作所有者；运行 JAR 中栈帧行号为 `397`。

StarminerR 发布了主世界高度重力场，可能使该实体进入自定义身体／移动路径，但本次异常的直接原因是引擎未承接原版移动内的死亡尺寸更新。堆栈中没有 `GravityCoreScreen`、`UpdateGravityCorePayload.handle` 或 `configureManual`；日志也没有记录核心参数编辑，因此不能认定 GUI 保存是触发条件。

## 建议修复方向

修复应位于 GravityEngine 的 `targets/neoforge-1.21.1` 集成层，由引擎为原版移动过程中合法发生的姿态／尺寸变化提供明确的所有权和提交时机。

- 检查移动事务内部的 `die → setPose → refreshDimensions`，选择受控的嵌套几何事务，或在合适的移动提交边界完成几何更新。
- 保证最终姿态、尺寸、眼高、精确碰撞体和代理 AABB 一致；嵌套更新结束后不能被旧移动快照覆盖。
- 保证正常返回和异常退出都释放操作状态，连续性失效与几何提交不会重复发生。
- 不建议仅删除异常检查、吞掉异常，或在 StarminerR 中绕过引擎内部状态：这只能隐藏所有权冲突，可能留下过期碰撞体。

以上是设计建议，尚未形成经过测试的引擎补丁。

## 待验证的复现步骤与回归测试

优先在存档副本中使用上述准确版本，加载事故区域，观察同类牛的落地死亡。存档中的实体状态可能已在退出时变化，不能保证重新进入就再次触发。

可控复现建议：

1. 保持 StarminerR 主世界高度重力场启用，在实体使用 GravityEngine 自定义身体／移动路径的区域准备落地平台。
2. 让牛从足够高度自由落下，确保最终伤害确实经由 `causeFallDamage` 致死。
3. 观察 `move → fallDamage → die → setPose(DYING) → refreshDimensions` 是否再次抛出同一异常。
4. 预期结果：牛正常死亡，服务器继续 tick，移动与几何状态正常收尾。

建议增加真实 Minecraft／Mixin 运行环境中的集成测试：致命摔落、非致命摔落、移动外死亡、其他生物的致命摔落，以及尺寸确实改变的嵌套姿态更新。除了不崩溃，还应验证死亡流程只执行一次、操作作用域清理、碰撞体一致性和后续实体 tick。纯数学或公共 API 的单元测试无法覆盖此次 Mixin 回调嵌套。

## 其他日志项

启动时出现 Sable 可选类缺失、FML 配置纠正、原版声音／着色器警告，但游戏在这些警告之后已成功进入世界。它们没有出现在本次致命异常链中，没有证据将其认定为本次崩溃根因。

本次日志显示配方、进度、8 个行星定义及资源重载完成，未发现 StarminerR 模型或语言加载错误。此结论仅覆盖这次日志，不等同于所有 GUI 操作均已完成实测。
