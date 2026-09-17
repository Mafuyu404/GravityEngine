# GravityEngine

GravityEngine 是一个面向 Minecraft 的可复用重力与身体姿态运行时。

它的目标不是提供某个特定玩法，而是把任意方向重力、重力场、身体朝向、运动学与相关平台集成整理成可独立复用的基础设施，使内容模组可以在此之上实现星球、空间站、局部重力区域、特殊移动环境等玩法，而不需要重复实现底层重力系统。

GravityEngine 使用 `cc.sighs.gravityengine` 作为 Java 包与 Gradle group，mod id 为 `gravityengine`。

## 项目定位

GravityEngine 负责重力与身体姿态的底层语义、数学模型和运行时集成。

项目重点包括：

- 任意方向和空间变化的重力场；
- 多个重力场之间的组合与覆盖；
- 实体在非世界竖直方向下的运动、碰撞与地面语义；
- 身体姿态、视角与表现层之间的协调；
- 服务端权威状态、客户端同步与表现；
- Minecraft 与不同 loader / 游戏版本之间的适配边界。

GravityEngine 本身不负责定义具体世界观或内容逻辑。诸如星球质量、空间结构、方块功能、玩法规则和世界生成等，应由上层内容模组提供。

StarminerR 是独立的内容模组，也是 GravityEngine 的预期消费者之一；它不属于 GravityEngine 的内部命名空间或运行时身份。

## 架构

仓库分为共享内核和平台 target 两层。

`common` 保存不依赖 Minecraft 与 loader 的共享代码，包括公开 API、重力场数学、几何与运动学基础，以及能够跨版本复用的纯 Java 模型。

`targets` 保存各 Minecraft 版本和 loader 的适配层，包括 Minecraft 类型桥接、Mixin、事件、网络、渲染、持久化以及版本专属集成。

这种结构的目的，是让真正稳定且可复用的重力语义尽量留在共享层，而把 Minecraft 与 loader 的变化限制在对应 target 中。

## 当前状态

`neoforge-1.21.1` 是目前的权威迁移 target，也是现阶段 GravityEngine 完整运行时实现的主要参考。

其他 target 用于多版本与多 loader 迁移，并可能处于脚手架或逐步移植状态。它们不应被默认视为与权威 target 具有完全相同的实现完整度。

GravityEngine 的公开 API 边界是有意保持收敛的。即使某些内部类在 Java 层面具有 `public` 可见性，也不代表它们属于稳定的外部兼容性承诺。

## 文档

更具体的使用方式、开发约束和维护流程放在独立文档中：

- [API 边界与兼容性](docs/API_BOUNDARY.md)
- [发布流程](docs/PUBLISHING.md)
- [多版本维护工作流](docs/MAINTENANCE_WORKFLOW.md)
- [CI target 发现规则](docs/CI_TARGET_DISCOVERY.md)
- [Minecraft 版本迁移差异](docs/version-differences/README.md)

仓库级开发规则见 [AGENTS.md](AGENTS.md)。

## 开发原则

GravityEngine 优先保持以下边界：

- 重力数学与 Minecraft 状态解耦；
- field evaluator 与 field 注册、生命周期和组合策略解耦；
- 共享内核与平台集成解耦；
- 物理状态与客户端表现解耦；
- 内容模组与底层运行时解耦；
- 各 Minecraft 版本之间通过明确的 target 适配，而不是在共享内核中堆叠版本特判。

这些原则用于降低长期迁移成本，并避免上层内容逻辑反向侵入 GravityEngine 的底层 API。

## License

GravityEngine 使用 GNU GPL 3.0 许可证。第三方或历史来源代码的额外许可说明保存在 `docs/licenses/` 中。
