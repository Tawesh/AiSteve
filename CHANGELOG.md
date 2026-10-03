# AiSteve — 变更记录 / Changelog

[English](README.md) · [简体中文](README.zh-CN.md) · **变更记录**

> 本文件按阶段记录本仓库相对上游 [YuvDwi/Steve](https://github.com/YuvDwi/Steve) 的全部改动。
>
> 每个 bug 修复都写了**根因**，不只是"改了什么"。这些推理过程比 diff 本身更有价值，
> 已经不止一次帮后来的人避免重复踩坑。
>
> **命名说明**：模组最初叫 "Steve AI Mod"、指令为 `/tai`，后统一改名为 **AiSteve**、
> 指令为 **`/as`**。本文档已按**当前名称**统一表述；改名本身见第四十二阶段。
>
> **版本对照**：第十一、十二阶段对应 `v1.0.0` 的分层 Agent 重构与体验修复。
> 当前已知缺口见 [docs/STATUS.md](docs/STATUS.md)。

---

## 第一阶段：接入 DeepSeek API

### 1. 新增 `[deepseek]` 配置段
**文件**：`config/SteveConfig.java`

- `[ai].provider` 增加 `deepseek` 选项。
- 新增字段 `DEEPSEEK_API_KEY` / `DEEPSEEK_MODEL` / `DEEPSEEK_BASE_URL`。
- 默认模型 `deepseek-flash`，基址 `https://api.deepseek.com`。
- API Key 留空时回退使用 `[openai].apiKey`。

### 2. 新增异步 DeepSeek 客户端（主路径）
**文件**：`llm/async/AsyncDeepSeekClient.java`（新增）

- 实现 `AsyncLLMClient`，`providerId = "deepseek"`。
- OpenAI 兼容协议：`POST {baseUrl}/chat/completions`，`Authorization: Bearer <key>`。
- 响应取 `choices[0].message.content`、`usage.total_tokens`。
- 错误映射到 `LLMException.ErrorType`；Key 为空时返回失败的 Future 交给容错层。

### 3. 新增同步 DeepSeek 客户端（legacy 路径）
**文件**：`llm/DeepSeekClient.java`（新增）
- 供已废弃的同步调用路径使用，含 3 次指数退避重试。

### 4. `TaskPlanner` 注册并分发 DeepSeek
**文件**：`llm/TaskPlanner.java`
- 新增 `deepSeekClient` / `asyncDeepSeekClient`。
- `getAIResponse` / `getAsyncClient` 增加 `case "deepseek"`。
- `planTasksAsync` 按 provider 选择模型（deepseek 用 `DEEPSEEK_MODEL`）。

### 5. `LLMExecutorService` 增加 deepseek 线程池
**文件**：`llm/async/LLMExecutorService.java`
- 新增 `deepseekExecutor` 与 `getExecutor` / `shutdown` 分支。

### 6. 修复：空 Key 不再导致构造期崩溃
**文件**：`AsyncOpenAIClient.java` / `AsyncGroqClient.java` / `AsyncGeminiClient.java`
- 原先构造时 Key 为空会抛异常，导致「只配了 DeepSeek Key」时整个 `TaskPlanner` 初始化失败。
- 改为记录 WARN，延迟到请求时报错。

### 7. 配置示例更新
**文件**：`config/aisteve-common.toml.example` —— 加入 deepseek 段。

---

## 第二阶段：构建与打包修复

### 8. 新增文档
`AiSteve-本地构建与使用指南.md`、`AiSteve-使用说明.md`。

### 9. 新增 `fatJar` 任务（修复打包缺陷）
**文件**：`build.gradle`

- 问题：上游 `jar`/`reobfJar` **只打包模组自身 class**，运行期依赖
  （resilience4j / caffeine）未打包 → 装进游戏后一旦规划任务就 `NoClassDefFoundError`。
- 修复：新增 `fatJar`，合并 reobf 后的模组类 + 运行期依赖 →
  `aisteve-1.0.0-all.jar`；并挂到 `assemble`。
- 刻意**不打包 GraalVM**：`CodeExecutionEngine` 全项目从未被实例化（死代码），
  且 JS 引擎体积大、易与其他模组冲突。

### 10. DeepSeek 模型默认值更新
实测账号可用模型为 `deepseek-flash`（V4.1-Flash）与 `deepseek-v4-pro`（V4-Pro）；
旧的 `deepseek-chat` / `deepseek-reasoner` 已不在可用列表。默认值相应更新。

### 11. 【崩溃修复】fatJar 不再打包 commons-codec
**文件**：`build.gradle`

- 现象：装进 Forge 1.20.1 后**启动即崩溃**：
  `java.lang.module.ResolutionException: Modules steve and org.apache.commons.codec
  export package org.apache.commons.codec.language to module minecraft`
- 原因：`commons-codec` 是 **Forge 自带**（1.20.1 → 1.15），重复提供导致 JPMS 模块冲突。
- 修复：`bundledLibPrefixes` 只保留 `resilience4j-` / `caffeine-`，
  并加防御性排除 `org/apache/commons/codec/**`。运行期使用 Forge 自带版本。

> 教训：打包第三方库前必须确认 **Forge 是否已提供**该库。

---

## 第三阶段：Bug 修复

### 12. 【行为修复】降级处理器（LLMFallbackHandler）重写
- 原降级 JSON **格式不符合 `ResponseParser` 约定**（参数未嵌在 `parameters` 内），
  且默认动作用了**未注册的 `wait`**，日志报 `Unknown action type: wait`，表现为「AI 完全无反应」。
- 修复：格式纠正、只用已注册动作、默认改为空任务列表；
  **补充中文关键词**（建/盖/造、房子、挖矿、跟随、攻击、砍树…）；
  日志明确提示「这是规则降级，不是真实 LLM 回复」。

### 13. 【可诊断性】缺少 API Key 时输出明确错误
**文件**：`llm/TaskPlanner.java`
- 新增 `warnIfApiKeyMissing()` / `hasApiKeyFor()`，无 key 时直接打印可操作提示：
  `No API key configured for provider 'X'. Set [deepseek].apiKey ... and RESTART the game.`

### 14. 【严重】建造功能完全失效（注释吞掉了赋值语句）
**文件**：`action/actions/BuildStructureAction.java`（第 148 行）

- 程序化回退的赋值被写在 `//` 注释**同一行**，成为注释的一部分：
  ```java
  if (buildPlan == null) {
      // Fall back to procedural generation            buildPlan = generateBuildPlan(...);  // ← 整行是注释
  }
  ```
  → `buildPlan` 恒为 `null` → `Cannot generate build plan for: house`。
  由于 `house` 依赖（仓库未附带的）NBT 模板，必然走此回退分支，**建房 100% 失败**。
- 修复：拆成两行，恢复赋值执行。
- 全仓库扫描同类模式，确认仅此一处（`SteveCommands.java` 那处以是有意注释的调试输出）。

### 15. 【协作建造】单/少 Steve 也能建完整 + 防重复注册与残留
**文件**：`action/CollaborativeBuildManager.java`、`action/actions/BuildStructureAction.java`

- **问题 A**：结构被切成 4 个象限，每个 Steve 只建自己那块，建完即停；
  原「补取」代码是死代码（重新从**同一个已完成象限**取，必然 `null`）。
  → 单个 Steve 时**房子只有 1/4，永远不完整**。
  - 修复：自己象限完成后**接管任意其他未完成象限**，并打日志说明。
- **问题 B**：取消建造时不注销 → 废弃 build 残留，下次建房会「加入」死项目。
  - 修复：新增 `leaveBuild()`，在 `onCancel()` 调用；无人时移除项目。
- **问题 C**：`findActiveBuild` + `registerBuild` 非原子 → 同 tick 多个 Steve 各建各的、互相覆盖。
  - 修复：`registerBuild` 改为 `synchronized` 并在内部二次检查复用。

---

## 第四阶段：架构重构（本次）

### 16. 移除 GUI 面板，改为 `/as` 指令 + 单一 AI 玩家

**删除的文件**
- `client/SteveGUI.java`、`client/SteveOverlayScreen.java`、`client/KeyBindings.java`、
  `client/ClientEventHandler.java` —— K 键面板与输入处理全部移除。
- `command/SteveCommands.java`（旧 `/steve` 指令）→ 由 `TaiCommands` 取代。

**新增 `command/TaiCommands.java`**

| 子指令 | 作用 |
| --- | --- |
| `create <名字>` | 创建 AI 玩家 |
| `remove` | 移除 AI 玩家 |
| `info` | 位置 / 生命 / 背包 / 当前目标 |
| `stop` | 停止当前任务 |
| `say <内容>` / 直接 `<内容>` | 下达任务或闲聊 |
| `give [物品] [数量]` | 把手上的物品（或指定物品）交给 AI |
| `take` | 收回 AI 身上所有物品 |

**单一 AI 玩家**
- `SteveManager.spawnSteve` 拒绝创建第二个；新增 `getSingleSteve()` / `hasSteve()` /
  `removeSingleSteve()` / `registerExisting()`。
- `ServerEventHandler`：**不再自动生成 4 个 Steve**（旧代码登录时硬生成
  `Steve/Alex/Bob/Charlie`）。改为登录时**接管**存档中已有的 AI 并清理多余个体。

**`ActionExecutor`**
- 移除对 `SteveGUI` 的依赖；AI 反馈改为**在聊天框说话**。
- `SteveMod` 不再注册已删除的 client 事件类。

### 17. 新能力：背包系统 + 拟人化物品交互

- **新增 `entity/SteveInventory.java`**：AI 自己的背包（堆叠合并、增删查、掉落、NBT 存档）。
- **新增动作**（已注册到 `CoreActionsPlugin` 与 legacy 分发）：

| 动作 | 类 | 说明 |
| --- | --- | --- |
| `pickup` | `PickupItemsAction` | 走到附近掉落物并捡起 |
| `give` | `GiveItemAction` | 走回玩家身边把物品交给他 |
| `use_item` | `UseItemAction` | **对任意实体/方块/自己使用任意物品** |
| `say` | `SayAction` | 在聊天框说话（索要物品/说明情况） |

- **`CombatAction` 重写**：支持按**具体生物名**猎杀（绵羊/牛/猪…，不再只打敌对生物），
  支持 `quantity`（杀几只）与 `collect`（自动拾取掉落物）。
- **`GatherResourceAction` 重写**：原本是空壳（直接返回失败），现在按资源类型
  路由到猎杀或挖掘（肉 → 对应动物，木头/矿石 → 挖掘）。
- **`ActionUtils.parseItem`**：物品名解析 + 中文别名（打火石/羊排/牛肉…）。

> **设计取向：不写死场景。** 羊排任务不是特判出来的，而是 AI 组合
> `use_item`(打火石→羊) + `attack`(羊) + `pickup`(熟羊排) + `give`(玩家) 得到的。
> 同一套基础动作也能覆盖点燃传送门、吃东西回血、放置玩家给的方块等等。

### 18. 理解能力：区分闲聊与任务 + 失败自纠 + 情境感知

- **`PromptBuilder` 全面重写**：
  - 要求模型先做**意图判断**：`chat`（闲聊/提问）还是 `task`（交办任务）。
  - 输出两种格式：
    - 闲聊 `{"type":"chat","reply":"…"}`
    - 任务 `{"type":"task","reasoning":"…","plan":"…","tasks":[…]}`
  - 给出**动作手册**（每个动作的参数与用途）与**大量示例**（含羊排场景的正反例）。
  - 用户提示词包含：位置、群系、**背包内容**、附近玩家/生物/方块、生命、最近动作。
- **`ResponseParser` 重写**：解析 `type` / `reply`，缺少 `type` 时按有无 `tasks` 兼容推断；
  **闲聊绝不产生任务**。
- **`ActionExecutor.handlePlanningResult()`**：区分处理闲聊（只回话）与任务（入队执行）。
- **失败自纠 `maybeReplan()`**：动作失败时把失败原因 + 原指令回灌 LLM，
  让它换做法或向玩家求助；最多 3 次，避免死循环。
- **`WorldKnowledge`**：附近生物改用干净名（`sheep` 而非 `entity.minecraft.sheep`），
  便于模型直接填进 `target` 参数。

### 19. 行为：未完成任务时像"驯服的狼"一样跟随玩家

**文件**：`action/ActionExecutor.java`

- **`maintainCompanionFollow()`**：只要任务尚未完成（`hasActiveTask()`），
  且处于**步骤间隙**（无正在执行的动作、队列为空），AI 就保持在玩家附近：
  超过 6 格小跑跟上，3 格内停下。
  执行具体步骤时会暂时离开，但**步骤之间会自动回到玩家身边**，不会走丢。
- **`clearGoalIfFinished()`**：所有步骤跑完即判定完成，停止跟随，回到空闲。
- 只在"没有动作在跑"时介入，**不会与正在执行的动作争抢寻路控制权**。

**附带修复**：`SteveMod.commonSetup` 现在真正调用 `PluginManager.loadPlugins(...)`。
此前插件系统**从未被初始化**，所有动作一直靠 `ActionExecutor` 的 legacy switch 兜底运行。

---


---

## 第五阶段：修复"一发消息就回『没理解』"

### 20. 【严重】`SteveEntity` 构造函数丢失 `inventory` 初始化 → AI 完全失灵
**文件**：`entity/SteveEntity.java`

- **现象**：任何指令都立刻回复"抱歉，我没理解，能再说一次吗？"，AI 完全不工作。
- **根因**（日志实证）：
  ```
  NullPointerException: Cannot invoke "SteveInventory.isEmpty()" because
  the return value of "SteveEntity.getInventory()" is null
  ```
  构造函数里 `this.inventory = new SteveInventory(this);` 这一行**在编辑过程中丢失**，
  于是 `getInventory()` 恒为 null。
- **连锁反应**：新写的 `PromptBuilder.buildUserPrompt()` 会读取背包内容
  （`steve.getInventory().isEmpty()`）来给模型提供情境 → 抛 NPE →
  `TaskPlanner.planTasksAsync` 的 catch 分支返回 `null` →
  `ActionExecutor` 判定"没理解"。
  **根因是构造函数漏初始化，但表现出来却像"模型听不懂话"**，极具误导性。
- **同时修复**：`super(entityType, level)` 与 `setPersistenceRequired()` 也在编辑中丢失，一并补回。

### 21. 【健壮性】提示词构建不再因单个字段异常而整体失效
**文件**：`llm/PromptBuilder.java`

- `describeInventory()` 加空值保护：即便 `getInventory()` 为 null 也只输出
  "[空] 你手上没有任何物品"，而**不会**抛异常打断整个规划流程。
- 原则：**构造提示词这样的辅助环节，绝不应该成为让 AI 彻底失能的单点。**

### 22. 【可诊断性】"没理解"提示改为可操作信息
**文件**：`action/ActionExecutor.java`

- 原文案 `抱歉，我没太理解，能再说一次吗？` 会误导玩家以为是自己的措辞问题。
- 但该分支的真实含义是"请求失败或回复解析失败"。
- 改为：`我这边没处理成功（解析或接口问题），看下日志再试一次吧。`

### 23. 【行为修复】同伴跟随 + 待机溜达（承接第 19 条，扩大适用面）
**文件**：`action/ActionExecutor.java`

- 第 19 条只在"任务未完成"时跟随，任务结束后 AI 又变回木头。现在改为**统一行为**：
  | 情况 | 行为 |
  | --- | --- |
  | 距玩家 > 6 格 | 小跑跟上（**空闲时同样生效**） |
  | 距玩家 < 3 格 | 停下 |
  | 距玩家 > 48 格 | 瞬移回到玩家身边（防走丢） |
  | 想回来却 5 秒无进展（卡矿洞等） | 瞬移回到玩家身边 |
  | 附近且空闲 | 随机在玩家周围溜达几步（更像真人） |
- 仅在**没有动作执行时**介入，不会与任务争抢寻路控制权。
- 删除已无用的 `IdleFollowAction`（功能被完全取代）。
- 新增 `/as come` 指令，可一键把 AI 叫到身边。

---


---

## 第六阶段：动作系统重构（"砍树却挖泥土"根因）

### 24. 【严重】`MineBlockAction` 重写：从"隧道挖掘机"改为"资源采集器"
**文件**：`action/actions/MineBlockAction.java`

**现象**：让 AI 砍树，它却拿着镐子朝一个方向挖泥土，**永不停止**。

**根因**（三个缺陷叠加）：
1. 该动作的原始设计是**挖矿道**：`onStart` 按玩家朝向算一个固定方向，
   把 Steve **瞬移**到视线前方，然后沿该方向逐格破坏——
   `mineNearbyBlock()` 会拆掉**前方碰到的任何方块**（泥土照挖）。
2. `minedCount` 只在挖到**目标方块**时才 +1。挖泥土不算 ⇒ 计数永远为 0 ⇒
   永远达不到 `targetQuantity`。
3. 唯一的退出条件是 `MAX_TICKS = 24000`（**20 分钟**）。
   而 `findNextBlock()` 只沿**一条 20 格直线**搜索、上下各 1 格，
   地表的树只要不在那条线上就永远找不到 ⇒ 退化成"无限挖泥土"。
4. `equipIronPickaxe()` **无条件空手变出铁镐** ⇒ 砍树也拿镐子。

**重写后的行为**：
| 环节 | 现在 |
| --- | --- |
| 寻找目标 | **3D 体积搜索**（半径 24，纵向 -12~+16），取最近的**真实**目标方块 |
| 移动 | **寻路走过去**（不再瞬移） |
| 破坏范围 | **只拆目标方块**；目标是原木时，自动把**相连的原木**一并砍下（整棵树） |
| 掉落物 | 用 `Block.getDrops()` 取真实掉落并**放进背包** |
| 工具 | 用**背包里实际拥有的**合适工具；没有就空手（原版允许），**不再凭空生成** |
| 找不到目标 | **立即失败并说明原因**（供 LLM 重新规划），而不是硬挖 |
| 超时 | 5 分钟硬上限 + **30 秒无进展即放弃** |
| 够不到 | 水平 ≤4.5 且垂直 ≤5（树干很高，不能只用 3D 距离判断） |

**统一别名解析**：`wood` / `木头` / `log` / `原木` → `oak_log`；`iron` / `铁` → `iron_ore` …

### 25. 【系统性】方块/物品名解析统一到 `ActionUtils`
**文件**：`util/ActionUtils.java`、`MineBlockAction`、`PlaceBlockAction`

**问题**：每个动作各写一份 `parseBlock`，别名支持**不一致**——`wood` 在有的动作里能用、
有的直接报 `Invalid block type`。同类问题反复出现。

**修复**：解析逻辑集中在 `ActionUtils`（含中英文别名表），并新增
`blockName(Block)` / `isLog(Block)` / `findBestTool(inventory, block)` / `toolPreferenceFor(block)`，
所有动作共用。

### 26. 【系统性】`PlaceBlockAction` 不再凭空造方块
**文件**：`action/actions/PlaceBlockAction.java`

- 旧实现直接 `level.setBlock(...)`，**完全不碰背包** ⇒ 材料无中生有。
- 现在：必须背包里真的有该方块，放置时**消耗 1 个**，不足则失败并提示
  （"I have no X to place..."），供 LLM 决定去采集还是向玩家索要。
- 同时改用统一的 `ActionUtils.parseBlock`，并保留寻路（不瞬移）。

### 27. 【一致性】`CraftItemAction` 诚实化，提示词不再承诺合成
**文件**：`action/actions/CraftItemAction.java`、`llm/PromptBuilder.java`

- `craft` 一直是**空壳**（"Crafting not yet implemented"），但提示词却把它列在可用动作里——
  AI 会规划出做不到的步骤。
- 现在：该动作**明确说明不会合成**（并在聊天框告知玩家），
  提示词中**移除 craft**，并新增两条边界说明：
  > ※ 你不会合成物品，也没有工作台。需要工具或材料时，用 say 直接向玩家索要。
  > ※ 背包里没有的东西不能使用、放置或建造。

### 28. 【同类隐患】`BuildStructureAction` 增加进度检测
**文件**：`action/actions/BuildStructureAction.java`

- 与挖掘动作同样的风险：原来只有 `MAX_TICKS = 120000`（**100 分钟**）兜底，
  中途卡住会长时间空转。
- 现在：硬上限降到 **10 分钟**，并新增 **30 秒无进展即中止**（放置成功才重置计时）。

### 29. 提示词同步更新（动作语义 + 遇阻处理规则）
**文件**：`llm/PromptBuilder.java`

- `mine` 的描述改为"**采集**：自动走到最近的这种方块旁挖掉并收进背包"，并说明砍树会砍整棵。
- 移除 `craft`，动作编号顺延。
- 新增规则 R10–R12：
  - R10：索要物品时只输出 `say` 那一步就停，不要同一次里既索要又执行需要该物品的动作。
  - R11：附近确实没有目标时，**不要反复重试**，用 `say` 说明并停止。
  - R12：只处理任务真正需要的方块/生物，不顺手破坏其他东西。

---


---

## 第七阶段：幽灵 AI 修复 + 探索式解题能力

### 30. 【严重】AI 实体同步：从"登录时扫一次"改为"持续同步"
**文件**：`entity/SteveManager.java`、`event/ServerEventHandler.java`、`command/TaiCommands.java`

**现象**：升级模组后，存档里的旧 AI 依然存在但**不被新模组识别**。
玩家被告知"还没有 AI"，于是 `/as create` 又建一个 ⇒ **两个 AI，只有新建的听话**。

**根因**：实体只在**所属区块加载时**才存在于内存中。而原来只在 `PlayerLoggedInEvent`
里扫一次 `level.getAllEntities()`：

```
登录 → 扫描 → 旧 AI 在未加载区块 → 漏检（注册表为空）
     → 玩家 /as create → 又建一个
     → 后来走到旧 AI 处 → 区块加载 → 旧 AI 冒出来，但没人注册它 → 幽灵
```

**修复**：
- `SteveManager.syncFromWorld(levels)`：与**所有维度**的已加载实体对账。
  - 注册表中已失效的条目先剔除；
  - 若当前没有注册实体，则**接管**第一个已加载的 AI（哪怕它是旧版本建的）；
  - 若已有注册实体，其余一律作为**重复**丢弃。
- `ServerEventHandler` 现在**持续同步**：
  - 服务器启动时同步一次；
  - **每 5 秒**同步一次（`ServerTickEvent`，100 ticks）——这样无论区块何时加载都能接管；
  - 发现重复时**在聊天里明确提示**玩家。
- `/as create` 与 `/as remove` 执行前**先同步**，避免基于过期状态做决定。
- 新增 `SteveManager.purgeAll(levels)`：**强制清除**世界上所有 AI 实体（含未注册的），
  并提供 `/as cleanup` 指令，用于清理旧版本遗留。

### 31. 【新能力】`loot_container`：像真人一样翻箱子
**文件**：`action/actions/LootContainerAction.java`（新增）

**背景**：此前 AI 找食物**只想到打猎**。附近明明有村庄和箱子，它却回答"附近没有动物"。
真人不会这样——村庄的箱子里常有面包、胡萝卜、马铃薯。

**实现**：
- 在 28 格内搜索**容器**（箱子/陷阱箱/木桶/潜影盒/漏斗/发射器/熔炉），
  先用方块类型快筛，再确认 `BlockEntity instanceof Container`（避免昂贵的无效查询）。
- 走到容器旁（**寻路**，不瞬移），打开并取走物品，含开箱音效与挥手动画。
- 参数：`item`（只取某种，`food`/`all`/`any` 表示全拿）、`limit`（最多取几个）、
  `x/y/z`（指定容器）。
- 拿到后报告清单，例如 `Looted 5 item(s) (3x bread, 2x carrot)`。
- 同样具备 5 分钟硬上限 + 30 秒无进展中止。

### 32. 【新能力】`explore`：主动走远去探索
**文件**：`action/actions/ExploreAction.java`（新增）

**背景**：AI 只能使用"扫描半径内恰好存在"的东西，所以两百格外的村庄等于不存在。
真人会说"我去那边看看"。

**实现**：
- 朝指定或随机方向**分段寻路**前进（每段 12 格，比一次走 40 格更适应地形），
  默认 40 格，范围 16~200。
- 可选 `target`（实体或方块名）：途中每 20 ticks 扫描一次，发现就**提前停下并报告**。
- 检测到**前进受阻**（连续无位移）就停下并说明，不会无限打转。
- 关键用法：与既有原语组合。`explore` → `loot_container` / `attack` / `mine`，
  移动之后那些动作会在**新环境**里搜索，于是能真正找到东西。

### 33. 【感知 + 提示词】让 AI"看得见"村庄，并学会探索式解题
**文件**：`memory/WorldKnowledge.java`、`llm/PromptBuilder.java`

**感知增强**（`WorldKnowledge`）：
- 新增**精确容器扫描**（步长 1，避免漏掉单格箱子）：报告容器数量、最近距离与坐标。
- 新增**结构线索识别**：`bell` / `composter` / `hay_block` / 各类工作站 /
  `bee_nest` 等 ⇒ 推断"这里有村庄"，并暴露 `looksLikeVillage()`。
- 新增 `getNearbyPointsOfInterest()`，输出形如
  `2 container(s) (nearest 8m away) at [x,y,z] | notable blocks: bell, composter`。

**提示词**：
- 用户提示词新增一行"附近的容器/建筑"，并在识别到村庄时额外提示
  `★ 这里看起来是村庄！箱子里、农田里很可能有现成的食物和材料。`
- 动作表加入 `loot_container` 与 `explore`。
- 新增规则：
  - **R13 找食物别只会打猎**：优先翻箱子 → 村庄 → 才考虑打猎；都没有才求助。
  - **R14 附近没有目标不要立刻放弃**：先用 `explore` 移动，再依据新环境判断。
- 新增两条示例：有村庄时用 `loot_container`；什么都没有时用 `explore + loot_container + gather`。

---


---

## 第八阶段：自给自足能力（挖矿/合成/钓鱼/种田/记忆）

> 本阶段把 AI 从"能用现有资源"推进到"能自己创造资源"，
> 对应玩家提出的四个方向：深层挖矿、真实合成、可再生食物、持久记忆。

### 34. 【新能力】深层挖矿：下到目标 Y 层找矿
**文件**：`action/actions/MineBlockAction.java`（扩展为双模式）

**问题**：`mine` 只在**可见范围**内搜索。钻石在 Y=-59，地表当然找不到 ⇒
请求"挖点钻石"必然失败。

**实现**：`MineBlockAction` 现在有两种模式，自动切换：
| 模式 | 触发 | 行为 |
| --- | --- | --- |
| **Surface** | 目标就在附近可见 | 走过去挖掉（树/石头/露头矿） |
| **Underground** | 目标在 24 格内找不到，且是矿石（或显式要求 `deep=true`） | **楼梯式挖到目标层 → 分支挖矿** |

- 每种矿石有**天然深度表**（`diamond_ore=-59`、`iron_ore=16`、`coal_ore=96`…）。
- 楼梯式下降：每步挖前下方 1 格 + 头部空间，交替方向形成**阶梯**（不是直井），
  不会把自己困住；到 Y 层后切换分支挖矿。
- 分支挖矿：主巷道 + 每 4 格向两侧开 14 格的支巷，**每切一格都检查相邻矿石**。
- 沿途**火把照明**（只消耗背包里真实拥有的火把）、采样掉落物进背包。
- 下限保护：不会挖到 Y=-60 以下（基岩层）。

### 35. 【新能力】真实合成 `craft`
**文件**：`action/actions/CraftItemAction.java`（由空壳改为完整实现）

**问题**：此前 `craft` 是空壳（"not yet implemented"），AI 完全无法自制工具。

**实现**（走**真实配方系统**，不是硬编码）：
1. 用 `RecipeManager.getAllRecipesFor(CRAFTING)` **查配方**。
2. 用 `Ingredient.test()` 在**背包**里匹配材料（正确支持**标签材料**，
   例如任意木板都能满足"planks"）。
3. 判断配方是否**需要工作台**（`canCraftInDimensions(2,2)`）：
   - 不需要 → 直接合成（如木板、木棍）。
   - 需要 → `ensureCraftingTableAvailable()`：
     a. 附近有工作台 → 走过去；
     b. 身上有工作台 → 就地放一个；
     c. 都没有 → **用 4 个木板自己造一个工作台**（这是 2×2 配方），再放下来。
4. 扣材料 → 产物进背包 → 支持批量（`quantity`）。
5. 材料不足时，报告**具体缺什么**（供 LLM 决定先采集还是向玩家索要）。

> 于是"砍树 → 木板 → 工作台 → 木镐 → 去挖矿"这条玩家式链条可以自动跑通。

### 36. 【新能力】钓鱼与种田（可再生食物）
**文件**：`action/actions/FishingAction.java`、`action/actions/FarmAction.java`（新增）

**问题**：食物来源只有"打猎"和"翻箱子"，前者靠运气、后者要碰运气找村庄。

**FishingAction**：
- 需要背包里有**鱼竿**（没有则明确失败并提示 `/as give fishing_rod`，不假装）。
- 在 24 格内找水面 → 走到岸边 → 抛竿 → 等待（1~3 秒随机）→ 收竿。
- 掉落按**原版钓鱼概率**：60% 鳕鱼 / 25% 鲑鱼 / 10% 热带鱼 / 5% 河豚；
  另有小概率**垃圾**（睡莲、碗、线…）与**宝藏**（附魔书、鞍、命名牌…）。

**FarmAction**：
- 扫描成熟作物（`CropBlock.isMaxAge`）：小麦 / 胡萝卜 / 马铃薯 / 甜菜根。
- 收割 → 产物进背包 → **自动补种**（消耗对应种子；没有种子则留空）。
- 只补种在**耕地**上，避免浪费种子。

### 37. 【新能力】持久化世界记忆
**文件**：`memory/WorldMemory.java`（新增）、`memory/WorldKnowledge.java`、`entity/SteveEntity.java`

**问题**：AI 每次重启都"失忆"，同一座村庄要反复重新发现，
也无法利用"我记得那边有水/有村庄"来做决策。

**实现**：
- 新增 `WorldMemory`：按类别（`village` / `water` / `farm` / `forest` / `cave` / `ore`）
  记录地标，含**去重**（24 格内不重复记）与**每类上限 12 条**（防膨胀）。
- **随实体写入 NBT**（`WorldMemory` 标签），因此**跨会话保留**。
- `WorldKnowledge` 每次扫描时**自动记录**发现：
  识别到村庄/容器 → 记 `village`；看到水 → `water`；看到作物 → `farm`；看到原木 → `forest`。
- 提示词新增一行 `我记得的地点: village at [x,y,z] (95m away) | water at ...`，
  让 LLM 能像玩家一样"我记得那边有什么"。

### 38. 提示词与感知同步更新
**文件**：`llm/PromptBuilder.java`、`memory/WorldKnowledge.java`

- 动作表新增 `craft` / `fish` / `farm`，并更新 `mine` 的说明（会自动下矿）。
- 移除"你不会合成"的旧说明，改为"你可以合成，会自己找配方、扣材料、必要时造工作台"。
- 感知新增：`★ 附近有农田/作物，可以 farm 收割食物。`
  `★ 附近有水域，如果有鱼竿可以 fish 钓鱼。`
- 新增规则：
  - **R15 缺工具就自己做**：优先 `craft`，材料也不够才求助。给出典型链条。
  - **R16 长期食物来源**：水域 → `fish`；农田 → `farm`；比反复找动物稳定。
  - **R17 挖矿找矿**：矿石目标附近看不到时直接 `mine`，会自动挖到对应深度，无需给坐标。

---


---

## 第九阶段：修正"任务已完成却提示失败/停止"

### 39. 【严重】重规划逻辑重写：区分「部分完成」「可重试失败」「不可重试失败」
**文件**：`action/ActionResult.java`、`action/ActionExecutor.java`

**现象**：任务实际已经完成（或已经拿到了能拿的东西），AI 却仍然提示失败、
最终说出"我试了几次都没成功，先停一下"，让人以为它什么都没做成。

**四个叠加缺陷**：

1. **`requiresReplanning` 标记形同虚设**（最根本）
   `ActionExecutor` 里的判定是：
   ```java
   if (!result.isSuccess()) { maybeReplan(result.getMessage()); }
   ```
   **完全没有读取 `result.requiresReplanning()`**。许多动作明确用
   `ActionResult.failure(msg, false)` 表示"别重试了"，但没人理会 —— 一律触发重规划。

2. **部分成功被当成失败**
   `MineBlockAction.finish()`：
   ```java
   } else if (collectedCount > 0) {
       result = ActionResult.failure(problem + " (got N/M)", true);   // ← 明明是拿到了
   }
   ```
   "挖8个铁，只找到3个"这种**正常结局**被标记为失败 → 触发重规划 → 3 次后公开宣布放弃。

3. **重规划会丢弃未执行的步骤**
   `maybeReplan` 无条件 `taskQueue.clear()`。若失败发生在计划的中途，
   后面本来能成功的步骤被一并丢掉。

4. **放弃时没有收尾**
   give-up 分支只发一条消息就 `return`，**没有清理目标状态**，
   目标残留会让 AI 一直以为"任务还在进行"。

**修复**：

- **`ActionResult` 语义显式化**（新增工厂方法 + 文档）：
  | 方法 | success | 可重试 | 用途 |
  | --- | --- | --- | --- |
  | `success(msg)` | ✅ | ❌ | 完全达成 |
  | `partial(msg)` | ✅ | ❌ | 能做的都做了，没有可再尝试的了（如配额只够 3/8） |
  | `failure(msg)` | ❌ | ✅ | 可恢复（无路径、缺工具、目标消失、超出范围） |
  | `giveUp(msg)` | ❌ | ❌ | 不可恢复（参数错误、永远做不到） |

- **判定改为尊重标记**：
  ```java
  if (!result.isSuccess()) {
      if (result.requiresReplanning()) maybeReplan(result);
      else { /* 非重试失败：记录并收尾，不再循环 */ }
  }
  ```

- **`maybeReplan` 重写**：
  1. 已有重规划在飞行中 → 直接返回（避免并发）。
  2. **若还有未执行步骤 → 继续执行计划**，不重规划（保住有效工作）。
  3. 计划已耗尽且仍未完成 → 最多重规划 `MAX_REPLAN_ATTEMPTS` 次。
  4. 仍失败 → **诚实说明**（"这个我暂时做不了：<原因>"）并**收尾**，不再死循环。

- **新增 `closeGoal(reason)`**：统一的"关闭目标"路径（清 goal + 写记忆 + 清队列），
  成功与放弃都走它，避免两套逻辑分叉。
  `clearGoalIfFinished()` 现在直接委托给它。

- **`MineBlockAction` 部分成功改为 `ActionResult.partial(...)`**，
  于是"只挖到 3/8"会正常收尾并如实汇报，不再谎报失败。

---


---

## 第十阶段：项目更名为 AiSteve

### 40. 全部更名为 AiSteve（含内部 modId）

**目标**：模组显示名与内部标识统一改为 `AiSteve`，指令由 `/tai` 改为 `/as`。

**改动清单**：

| 项目 | 改动前 | 改动后 |
| --- | --- | --- |
| 游戏内显示名 | `Steve AI Mod` | **`AiSteve`** |
| **内部 modId** | `steve` | **`aisteve`** |
| **配置文件** | `config/steve-common.toml` | **`config/aisteve-common.toml`** |
| 构建产物 | `steve-ai-mod-1.0.0-all.jar` | **`aisteve-1.0.0-all.jar`** |
| 指令根 | `/tai` | **`/as`** |
| 命令类 | `TaiCommands` | **`AsCommands`** |
| 资源目录 | `assets/steve/` | **`assets/aisteve/`** |
| 语言键前缀 | `key.categories.steve` | **`key.categories.aisteve`** |
| 实体类型 id | `steve:steve` | **`aisteve:steve`**（实体名仍为 `steve`） |

**涉及文件**：
- `src/main/resources/META-INF/mods.toml`：`modId` / `displayName` / `description` / `authors` / `displayURL` / 依赖段
- `src/main/resources/pack.mcmeta`：资源包描述
- `src/main/resources/assets/steve/` → `assets/aisteve/`（目录重命名 + `en_us.json` 键）
- `SteveMod.java`：`MODID = "aisteve"`
- `command/TaiCommands.java` → `command/AsCommands.java`（新增 `ROOT = "as"` 常量，
  所有提示语改为拼接 `/as`，避免硬编码散落）
- `event/ServerEventHandler.java`：聊天前缀 `[Steve AI]` → `[AiSteve]`，提示语改 `/as`
- `entity/SteveManager.java`、`llm/TaskPlanner.java`、`action/actions/FishingAction.java`：
  文案中的 `/tai` → `/as`、`steve-common.toml` → `aisteve-common.toml`
- `structure/StructureTemplateLoader.java`：结构命名空间 `steve` → `aisteve`
- `build.gradle`：`archivesName = 'aisteve'`、jar 清单、`runs.mods { aisteve { ... } }`
- `config/steve-common.toml.example` → `config/aisteve-common.toml.example`

**注**：Java 包名 `com.steve.ai` 与类名（`SteveEntity` / `SteveMod` 等）**保持不变** ——
它们是内部实现，改名会导致大量无意义的连锁改动，且不影响玩家可见行为。

### 41. 新增迁移脚本 `migrate-to-aisteve.ps1`
**文件**：`migrate-to-aisteve.ps1`（工作区根目录）

因为 modId 变了，配置文件路径也随之改变，玩家原有的 DeepSeek Key 需要搬移。
脚本自动完成：

1. **检查游戏是否已关闭**（配置会被运行中的游戏覆盖）。
2. 把 `config\steve-common.toml` 复制为 **`config\aisteve-common.toml`**
   （若新文件已存在则先备份为 `.bak`）。
3. **校验迁移结果**：确认 `provider` 与 `apiKey` 已就位（Key 打码显示）。
4. **检查 mods 目录**：列出仍需删除的旧版 jar，并给出可复制的删除命令；
   确认新版 `aisteve-*.jar` 是否已放入。
5. 打印后续步骤（`/as cleanup` → `/as create`）。

> 旧配置文件**不会被删除**，确认无误后可自行清理。

### 42. 文档同步更新
**文件**：`AiSteve-使用说明.md`、`AiSteve-本地构建与使用指南.md`、`AiSteve-变更记录.md`

- 所有 `/tai` → `/as`；jar 名、配置文件名、品牌名全部同步。
- 使用说明标题改为「AiSteve — 使用说明」。
- 变更记录顶部加**命名说明**，避免早期条目与当前名称混淆。

### 43. 清理过时文档，以现状为准
**删除的文件**
- `Steve/TECHNICAL_DEEP_DIVE.md`（79 KB / 2198 行）——上游技术文档，通篇描述**旧架构**
  （K 键 GUI 面板、`/steve spawn`、多智能体协作、动作列表与实际不符），
  与当前实现差距过大，保留只会误导。
- `fix-steve-config.ps1` —— 早期用于写入旧配置路径的一次性脚本，
  已被 `migrate-to-aisteve.ps1` 完全取代。

**重写的文件**
- `Steve/README.md`：按**现状**重写。原文仍是旧版描述（K 面板、`/steve spawn`、
  "crafting not implemented"、"Memory resets on restart" 等），且此前编辑留有重复段落。
  新 README 覆盖：安装、全部 `/as` 指令、能力清单、配置、构建（含 `fatJar` 说明与
  两个产物的区别）、项目结构、**升级迁移步骤**、已知限制。

**重命名的文件**（与模组名统一）
- `Steve-使用说明.md` → **`AiSteve-使用说明.md`**
- `Steve-变更记录.md` → **`AiSteve-变更记录.md`**
- `Steve-本地构建与使用指南.md` → **`AiSteve-本地构建与使用指南.md`**

**同步修正**
- `Steve-本地构建与使用指南.md`：修正第 1 / 5 / 8 / 9 / 10 节中残留的旧交互描述
  （K 面板、`/steve spawn`、多智能体），第 7 节改为指向变更记录的**阶段索引**，
  避免维护两份容易失同步的清单。同时修正对已删除文档的引用。
- `Steve/.gitignore`：忽略项更新为 `config/aisteve-common.toml`。
- `Steve/scripts/run_steve.sh`：品牌与配置路径更新。
- `Steve/config/steve-common.toml.example` → `config/aisteve-common.toml.example`，
  并移除示例中的真实 API Key（改为占位符）。

---

## 兼容性说明
## 兼容性说明

- **配置键完全不变**（`[ai]` / `[deepseek]` / `[openai]` / `[behavior]`）。

**升级到 AiSteve 时的破坏性变更（第 40 条）**：
- **内部 modId 由 `steve` 改为 `aisteve`** ⇒ 配置文件变为 **`config/aisteve-common.toml`**。
  用 `migrate-to-aisteve.ps1` 自动搬迁你的 API Key。
- **指令由 `/tai` 改为 `/as`**。
- **构建产物改名**为 `aisteve-1.0.0-all.jar`；旧的 `steve-ai-mod-*.jar` 必须从 `mods/` 删除。
- 存档中旧 modId 创建的 AI 实体不再匹配注册表 ⇒ 进入世界后执行
  **`/as cleanup`** 清除遗留，再 **`/as create <名字>`** 重建。
- K 键面板已移除；`[behavior].maxActiveSteves` 不再有意义（只允许 1 个 AI）。

**其他修复类条目**：
- 第 6 条是"空 Key 不再构造期崩溃"，属于修复。
- 第 9 / 11 条涉及 fatJar 打包范围（含 Forge 自带库会崩溃）。
---

## 构建验证状态

✅ **已在本地成功构建**

- `.\gradlew.bat compileJava` → `BUILD SUCCESSFUL`
- `.\gradlew.bat fatJar` → `BUILD SUCCESSFUL`
- 产物：`build/libs/aisteve-1.0.0-all.jar`（约 **1.34 MB**）
- 产物校验（最新一次）：
  - 新增类齐全：`TaiCommands`、`SteveInventory`、`UseItemAction`、
    `PickupItemsAction`、`GiveItemAction`、`SayAction` ✓
  - 已删除类为 0：`SteveGUI`、`SteveOverlayScreen`、`KeyBindings`、
    `ClientEventHandler`、`SteveCommands`、`IgniteAction` ✓
  - `commons-codec = 0`（使用 Forge 自带版本）✓
  - 字节码 major version = **61（Java 17）** ✓
- DeepSeek 接口实测：`GET /models` 200、`POST /chat/completions` 200。
- 编译仅有 deprecation 警告，无错误。

> 注：构建环境依赖本地代理 `127.0.0.1:7890` 才能下载依赖（见构建指南 4.4 节）。

---

# 第十一阶段：按《方案文档》重构为分层 Agent 架构

> **本阶段是整个项目最大的一次架构改动。** 目标不再是"指令 → 动作列表"的翻译器，
> 而是《方案文档》描述的 **Minecraft Agent Runtime**：
> 感知 → 理解 → 记忆 → 产生需求 → 形成目标 → 制定计划 → 调用技能 → 执行 → 观察 → 修正。
>
> 设计细节见 `Steve/ARCHITECTURE.md`（已全文重写）。

## 44. 设计文档：`ARCHITECTURE.md` 重写

- 原文描述的是旧的"LLM Planning → Decomposition → Context → Execution"四层，
  与本次目标架构不符，已重写为分层 Agent 架构设计：
  核心原则、目标架构图、新包结构、**新旧差距对照表**、关键数据流、时间尺度表、
  分阶段迁移路线、**权限边界表**。

## 45. 【新】`protocol` 层 —— Agent Protocol

Agent 与 LLM 之间的结构化契约，与任何模型厂商解耦。

| 类 | 作用 |
| --- | --- |
| `Observation` | 结构化观察：自身 / 背包 / 玩家 / 敌对 / 动物 / 资源 / 容器 / 最近事件，附 `toPromptText()` |
| `ToolSpec` | 工具元数据：name / description / category / parameters / permission / risk |
| `ToolCall` | `{tool, arguments, reason}`，含宽容的类型化取值（模型参数类型很乱） |
| `ToolResult` | `{success, status, message, data}`，`status` 是 Reflection 的 switch 依据 |
| `AgentDecision` | LLM 的唯一输出形式：`intent / thought / goal_update / actions / reply` |
| `Permission` | MOVEMENT / WORLD_READ / WORLD_WRITE / INVENTORY / COMBAT / SOCIAL / ADMIN |
| `RiskLevel` | LOW / MEDIUM / HIGH / FORBIDDEN |

**关键设计**：`Observation` 每类信息都**限流**（玩家 5、敌对 6、背包 12、资源 8…），
token 成本与世界的繁忙程度无关 —— 落实方案文档的"千万不要把所有世界信息都塞给 LLM"。

## 46. 【新】`perception` 层 —— 把世界压缩成 Observation

- `SelfObserver`：生命 / 坐标 / 维度 / 群系 / 昼夜 / 天气 / 手持物。
- `InventoryObserver`：背包（上限 24 格）。
- `EntityObserver`：按**玩家 / 敌对(`Enemy`) / 动物(`Animal`)** 分桶，按距离排序并封顶。
- `WorldObserver`：资源方块（矿石/原木/水/作物/地标）与容器，步长 2 扫描。
- `PerceptionService`：**快慢双频调度**。

**为何偏离文档给出的 5~10 Hz**：全量方块扫描在 5 Hz 下代价过大，而模型并不需要。
因此快频（2 Hz）只做廉价的字段读取（自身/背包/实体），慢频（3 秒）才扫方块并缓存。
—— 与文档"不要让 LLM 进入 tick 循环"是同一个理由：**成本要匹配价值**。

## 47. 【新】`memory` 层 —— 四层记忆 + 统一门面

| 层 | 类 | 生命周期 | 持久化 |
| --- | --- | --- | --- |
| Working | `WorkingMemory` | 分钟级 | 否（会话内） |
| Episodic | `EpisodicMemory` | 永久 | 是（NBT） |
| Semantic | `SemanticMemory` | 永久 | 是（NBT） |
| Social | `SocialMemory` | 永久 | 是（NBT） |

- `SemanticMemory` 内置 Minecraft 常识（钻石要铁镐、村庄箱子找食物、夜晚刷怪…），
  并提供**关键词检索（≈ 小型 RAG）**：拉丁词按整词、中日韩按字匹配后再打分。
  只为把 2~4 条相关规则塞进 prompt，而不是把知识库全灌进去。
- `SocialMemory` 记录 **信任度 / 互动次数 / 事实**，并给出"好友/熟人/陌生人"的粗粒度标签。
- `MemoryManager` 是唯一门面，负责把三层持久化记忆编解码到实体 NBT 的 `AgentMemory`。
- **保留** `SteveMemory`（会话对话）与 `WorldMemory`（地标坐标）不动，避免回退。

## 48. 【新】`brain` 层 —— 目标 / 欲望 / 人格 / 规划 / 反思 / 社交

- `Goal` + `GoalType` + `GoalManager`：目标栈（类型、优先级、来源、状态）。
  **优先级是动态的**：每轮由 `Needs` + `Observation` 重新推导 ——
  血量 ≤35% 或附近 8 格有敌对生物时，`SURVIVE/PROTECT` 直接抬到 100，
  同时把 `EXPLORE/BUILD/RESOURCE` 压到 5（即文档所说的"探索归零"）。
- `Needs`：饥饿 / 安全 / 社交 / 探索 / 成就 / 资源 / 好奇 七种**压力值（0~100）**，
  超过阈值且距上次触发 >30 秒才生成目标（防刷屏）。
  **速率按"分钟级"调校**：需求应该在几分钟后开始抱怨，而不是几秒。
- `Persona`：好奇/勇气/幽默/热心/怕危险 五项权重 + 说话风格 + 原型标签。
- `Planner`：**Skill 优先，LLM 兜底**。命中的目标不花一个 token。
- `Plan`：显式携带 `needsLlm` 标记 —— 把"得问模型"建模成一种计划，而不是异常。
- `Reflection`：按 `ToolResult.status` 查表得出「原因 / 下一步 / 是否升级给 LLM」，
  少数模糊情形才升级到模型。
- `SocialSystem`：解析玩家发言 → 更新 `SocialMemory` + 建议目标（跟随/采集/建造/战斗…），
  并识别"喜欢/不喜欢"这类值得长期记住的偏好。

## 49. 【新】`skill` 层 —— 复杂能力 = 多个 Tool 的组合

`MiningSkill` / `CombatSkill` / `BuildingSkill` / `ExplorationSkill` / `SurvivalSkill` / `SocialSkill`，
搭配 `SkillRequest` / `SkillPlan` / `SkillContext` / `SkillRegistry`。

**存在意义**：把最高频的请求（挖铁、砍树、跟着我、建房子、找吃的）做成**确定性计划**，
不花 LLM 一分钱；模型只服务于真正开放的任务。

- `CombatSkill` 区分"打怪"与"打猎"：后者会额外产出 `pickup_item`，
  因为玩家要的是肉，不是地上的一堆掉落物。
- `SurvivalSkill` 先吃背包里的、没有才去翻箱子，并**如实告知玩家**。
- `SocialSkill` 在 `follow_player` 之后追加一句 `send_chat`（"好，我跟着你。"）——
  会回应的队友和"默默移动的脚本"是两回事。

## 50. 【新】`tool` 层 —— 六大类原子能力 + 权限闸门

| 类别 | 工具 |
| --- | --- |
| `MovementTools` | `move_to` `follow_player` `look_at` `jump` `stop` |
| `InteractionTools` | `break_block` `place_block` `open_container` `pickup_item` `use_item` `build` `explore` `fish` `farm` |
| `InventoryTools` | `get_inventory` `craft_item` `drop_item` `equip_item` `give_item` |
| `CombatTools` | `attack_entity` `use_weapon` `flee` |
| `WorldTools` | `scan_area` `find_block` `find_entity` `get_time` `get_weather` |
| `SocialTools` | `send_chat` `ask_player` `remember_player` |

- **工具只描述"要做什么"，执行仍交给既有的 16 个 Action** —— 这是本次重构最重要的
  兼容性决策：上层换成 Agent，下层那些踩过无数坑的挖掘/合成/建造逻辑原封不动。
- `ToolDispatcher` 是唯一入口，三道校验：**存在性 → FORBIDDEN 拦截 → Permission 校验**。
  落实"AI 的能力边界由 Runtime 决定，而不是由 Prompt 决定"。
- **绝不造假**：`equip_item` 明确返回"不需要手动装备"（工具会自动选择）；
  `fish` 没鱼竿直接返回 `missing_tool`；`give_item` 身上没有就**不入队**而是立即失败。
  静默空转比失败更糟 —— 它会让大脑以为任务在进行。
- `attack_entity` / `use_weapon` 内置护栏：**永不攻击玩家**。

## 51. 【新】`execution/MovementController` —— 唯一合法的位移入口

落实文档里最关键的一条硬规则：`move_to(200,64,-300)` **不能**变成 `setPos(...)`。
该类的 API 里**刻意没有 teleport 方法**；一切位移都走 vanilla 寻路 → 物理。

## 52. 【新】`event` 层扩展 —— Agent Event Bus

`AgentEventType`（PLAYER_CHAT / LOW_HEALTH / PLAYER_HURT / ENTITY_DETECTED /
GOAL_COMPLETED / PLAYER_DIED / …）+ `AgentEvent`。

- 只有**真正紧急**的事件（低血/受伤/死亡）会唤醒一次思考；普通事件只写入工作记忆。
  否则一个热闹的 tick 就能烧掉一堆 token。
- 新增 `ServerEventHandler.onServerChat`：**普通聊天也能被听见**（48 格内）。
  在此之前只有 `/as say` 能触达 AI —— 现在"真人玩家是 AI 世界里的另一个玩家"才成立。

## 53. 【新】`agent` 层 —— AgentRuntime + AgentLoop

- `AgentRuntime`：一个 AI 一个实例，把上表所有层装配起来，持有事件队列与懒加载的 LLM 网关。
- `AgentLoop`：`observe → think → act → reflect` 主循环，每 tick 调用但几乎不做事。
  - 感知 2 Hz；思考 **最少间隔 1 秒**且事件驱动；执行交给 ActionExecutor（20 TPS）。
  - **计划优先级**：先问 Skill（确定性、零成本），未命中才返回 `needsLlm`。
  - **动作结果关联**：Tool 返回 `scheduled` 时记录"是哪一步、属于哪个目标"，
    等 ActionExecutor 通过执行监听器回报真实结果后再触发反思 —— 否则只能看到"已入队"。
  - **有界重规划**：同一目标最多 3 次，超过就如实说"这个我暂时做不了：<原因>"。

## 54. 【新】`llm` 层 —— Agent 协议 Prompt 与解析

- `AgentPromptBuilder`：按文档要求组织 `System + Persona + 目标 + 记忆 + 观察 + 事件 + 工具清单`。
  系统提示里明确 8 条硬约束（不能瞬移、不能凭空造物、不知道视野之外、只能用手册里的工具、
  只决定"下一步"而不决定按键、不攻击玩家、缺东西就如实说、只输出 JSON）。
- `AgentDecisionParser`：宽容解析（markdown 围栏、裸换行、缺逗号、顶层数组、
  OpenAI 风格嵌套 `function.arguments`），并**兼容旧动作名**
  （`mine→break_block`、`say→send_chat`、`loot_container→open_container`…），
  于是旧词表也不会变成解析失败。
- `TaskPlanner.decideAsync(...)`：复用既有的 provider 选择 / 缓存 / 熔断 / 限流 / 降级栈。

## 55. 接入现有实现

- `SteveEntity`：增加 `AgentRuntime`，`tick()` 里驱动 `AgentLoop`；
  新增 `hearPlayer(speaker, text)`（`/as say` 与普通聊天共用）、`postAgentEvent(...)`；
  四层记忆随实体写入 NBT 的 `AgentMemory`；`setSteveName` 同步人格名字。
- `ActionExecutor`：
  - 新增 `enqueueTask` / `getQueueSize` / `isBusy`；
  - 新增 `ExecutionListener`（`onActionStarted` / `onActionFinished`）供 Agent 关联真实结果；
  - 新增 `setExternalPlanningEnabled(true)` —— **分层运行时接管时会关闭旧的重规划**，
    否则旧 `maybeReplan` 会和新的反思层抢同一个队列。
- `SteveConfig`：新增 `[agent]` 段 —— `enabled`（总开关）、`autonomy`（自主行为开关）。
- `AsCommands`：
  - `/as say` 改为走 `SteveEntity.hearPlayer`（即 Agent 路径）；`agent.enabled=false` 时仍走旧路径；
  - 新增 **`/as agent`**（人格/需求/记忆/循环状态）、**`/as goals`**（目标栈）、
    **`/as memory`**（学会的玩家关系与经历）。

## 56. 【排错记录】1.20.1 中 Mob 没有饥饿值

`SelfObserver` 最初用 `steve.getFoodData().getFoodLevel()` 读取饥饿 —— **编译失败**。
核对官方映射后确认：**1.20.1 的 `FoodData` 挂在 `Player` 上，`LivingEntity` 并没有它**，
而 `SteveEntity` 是 `PathfinderMob`。

处理方式不是绕过去，而是按"**感知不得编造观测不到的东西**"这条原则改写：

- `SelfObserver` 返回 `food = -1` 表示"不适用"，并在 prompt 里显示"饥饿 不适用"；
- 饱食度改由 `Needs` 自行维护：随时间缓慢增长，**真正吃到东西时**（`use_item` + `self=true`）
  由 `AgentLoop` 调用 `needs.onAte()` 回落。
- 于是"饥饿 → 找吃的 → 吃 → 不饿"这条链依然是**真实闭环**，而不是一个只会涨的数字。

## 57. 文档同步

- `Steve/ARCHITECTURE.md`：全文重写（见第 44 条）。
- `Steve/CLAUDE.md`：补充分层架构、新包结构、Agent 执行流、新指令、新配置、新关键文件。
- `Steve/README.md`：补充智能体能力（自主行为 / 记忆 / 社交）、新指令、`[agent]` 配置。
- 本变更记录：新增第十一阶段。

## 58. 构建验证

```
.\gradlew.bat compileJava            → BUILD SUCCESSFUL
.\gradlew.bat build -x test fatJar   → BUILD SUCCESSFUL
产物：build/libs/aisteve-1.0.0-all.jar（约 1.62 MB）
      build/libs/aisteve-1.0.0.jar    （约 0.51 MB）
```

编译仅剩 deprecation 提示（沿用既有 API），无错误、无警告级问题。

---

# 第十二阶段：体验反馈修复（交互、反馈、范围、保护）

> 玩家实测后反馈了 8 个问题，本阶段逐条修复。
> 其中两条是**真正的逻辑缺陷**（合成交付的时序、完成反馈的自相矛盾），
> 三条是**缺失的能力**（主动说话、范围约束、保护意识），一条是 GUI 布局问题。

## 59. 【严重·逻辑】"已经完成了却提示暂时无法完成"

**现象**：AI 明明把东西交给玩家了，聊天里却又冒出"这个我暂时做不了：我身上没有 X"。

**根因（时序误报）**：`InventoryTools.giveItem` 在**派发阶段**用 `observation` 预检背包：

```java
if (ctx.observation().countItem(item) <= 0) {
    return ToolResult.missingTool("我身上没有 " + item);
}
```

而 `observation` 是 **2 Hz 的缓存快照**。计划里的 `craft_item → give_item` 是连续两步，
合成刚完成时快照还没刷新，于是预检误判"没有这个物品" → 触发反思 → 提交新目标 →
最终播报"做不了"。**紧接着 `GiveItemAction` 真正执行时物品已经在包里，于是又交了出去。**

**修复**：删掉派发期的背包预检。是否给得出来，交给 `GiveItemAction` 在**执行那一刻**
用最新背包判断 —— 那里才是唯一有准确信息的地方。

## 60. 【严重·逻辑】"我没想好怎么做"和"搞定了"同时出现

**现象**：AI 说了想不出办法，下一秒又报告"搞定了"，让人分不清到底做没做成。

**根因**：`AgentLoop.collectDecision` 在模型没给出任何动作时只发了一句提示就返回，
**没有清掉 `activeGoalId`**。于是下一轮 `advance()` 发现 `stepQueue` 是空的，
按"计划已跑完"处理 → `finishGoal()` → 播报"搞定了"。两个互斥的结论同时成立。

**修复**：
- 模型没给可执行动作时，把该目标标记为失败并 `clearFocus()`，不再留成"待办"。
- 纯闲聊（`intent=chat`）同样解除目标绑定，避免被误判成"任务完成"。

## 61. 【严重·逻辑】合成数量与"我已经有了"重复合成

**现象**：玩家只要 1 个，AI 却做出一堆；重试时又再合成一批。

**根因**：`batchesWanted` 只按"想要多少"算，**完全没看背包里已经有多少**。
模型重试"要 1 个木棍"时，每次都按新的一批（产出 4 个）重做一遍。

另外完成汇报用的是**批次数**而不是物品数：
```java
"Crafted " + craftedNow + "x ..."   // craftedNow 是批次，实际物品是批次×每批产量
```
所以它自己说的"合成了 4 个"和玩家背包里出现的数量对不上。

**修复**：
- 先算差额：`stillNeeded = desiredCount - 已经拥有的数量`；已经有了就直接收工。
- 汇报改为**实际产出物品数**（`批次 × 每批产量`），并改用本地化物品名。
- 材料不够导致只做出一部分时用 `ActionResult.partial(...)`（成功但不触发重规划）。

## 62. 【严重·逻辑】`give_item` 把"给我一句话"当成"把身上所有东西都给我"

**现象**：只要了一句话，AI 却把一堆多余的东西塞过来。

**根因**：`SocialSkill.canHandle` 见到"给我/交给/送我"就一律接管，而 `plan()` 在**认不出
具体物品时默认 `item = "all"`** —— 于是"给我"被解释成"全部交出去"。

**修复**：只有能识别出具体物品，或者玩家明确说了"全部/所有/东西"时才由技能接管，
否则交回 LLM 判断。另外 `GiveItemAction` 现在会在物品名不认识时明确报错，
而不是"静默地什么都没给"。

## 63. 【新能力】进度播报 —— 先说做什么，做完汇报

在此之前，AI 只有两种时刻会说话：玩家问它、或者它需要求助。中间过程完全静默。

现在补齐了队友会开口的三个时刻：

| 时刻 | 播报内容 |
| --- | --- |
| 开始 | "我先去砍 8 个 oak_log，弄好了跟你说。" |
| 完成 | "搞定了：合成 4 个木板。" |
| 失败 | "「XX」我暂时做不了：需要铁镐。" |

设计要点：
- **直接发聊天**，不排进动作队列 —— 正在挖矿时队列会堵住，而"我要开始挖矿了"晚 20 秒说就没意义了。
- **节流 30 tick**，被压下的那句会在下一次机会补发，避免多步计划刷屏。
- 关闭方式：设置界面里的"进度播报"开关，或 `[agent].progressNarration = false`。

## 64. 【新能力】主动说话

空闲或执行中，每 90 秒最多主动搭一句话，内容由**规则**根据处境生成（不调模型，省钱且无延迟）：

- 附近有敌对生物 → "附近有 zombie，我盯着点。"
- 天黑了 → "天黑了，注意点周围。"
- 背包空 → "我背包还空着，要不要我去弄点木头？"
- 附近有动物 → "那边有 sheep，要打点肉吗？"
- 没事 → "我就在旁边，有需要喊我。"

之前空闲搭话只在"完全没有目标"时才会触发，而现在有探索目标时也会说 ——
一个沉默着挖三分钟矿的同伴，和不在场没什么区别。

## 65. 【新能力】活动范围以玩家为中心

**问题**：AI 会一路走远，玩家再见到它时已经是几百格外，看起来像"跑了"。

**解决**：新增 `[agent].roamRadius`（默认 48 格），并在三处同时生效：

1. **GoalManager**：超出半径时插入高优先级"回到 X 身边"目标（优先级 90，
   高于采集/建造/探索，低于紧急生存），并把其它目标压到 10 以下。
   走的是正常的目标→计划→工具链路，最终由 `follow_player` 完成 —— **仍然是走回去，不是瞬移**。
2. **ExploreAction**：探索距离被夹到半径以内；一旦快要超出就主动收手，
   而不是继续往外跑然后被判走丢。
3. **ExplorationSkill**：规划出的探索距离同样受半径限制。

紧急情况（血量过低 / 附近有怪）下**不触发**回位：先活下来比先归队重要。

## 66. 【新能力】保护玩家

**新增**：`LivingHurtEvent` → 玩家被打 → AI 立刻生成"保护玩家：消灭 X"目标。

- 刻意**不经过 LLM**：看到队友被打是条件反射，不是需要思考三十秒的事。
  目标随后走正常的 `CombatSkill` + `attack_entity` 流程。
- 只在 32 格内生效，避免被远处的战斗反复唤醒。
- `CombatSkill` 现在能从描述里解析出被点名的目标（"消灭 zombie" → `zombie`），
  并在动手前喊一句"别怕，我来对付 zombie！"。

**关于 PvP**：新增 `[agent].defendAgainstPlayers`（默认开启）。
这是整个 Agent 里**唯一**能让 AI 把玩家当目标的路径，并且：
- 只针对刚刚动手的那一个玩家（临时白名单）；
- 目标完成后立刻撤销白名单（反击只在当场有效）；
- 关掉它就彻底没有 PvP 能力。

## 67. 【严重·健壮性】指令处理移出非服务器线程

**问题**：`/as say` 和聊天事件都在**另一个线程**上直接调用 Agent 的
`social().analyze()`、`goals().submit()`、写记忆 —— 这些都会碰实体和世界状态。
属于"大多数时候能跑、偶尔出怪事"的并发隐患。

**修复**：
- `AgentLoop.requestInstruction` 改为**纯入队**（`ConcurrentLinkedQueue`）。
- 新增 `processInbox()`，在 `tick()` 里于**服务器线程**上消费指令。
- 调用方（`AsCommands`、`ServerEventHandler`）不再需要自己包线程。

## 68. 【界面】设置页面底部按钮被遮挡

**问题**：权限与能力页面用硬编码 Y 坐标排控件，窗口一矮（或 GUI 缩放一大），
底部的"保存/返回"就掉到屏幕外，而且**没有任何滚动方式**，玩家只能卡在那一页。

**修复**：新增可复用基类 `ScrollableSettingsScreen`：
- 内容坐标与屏幕坐标分离，子类只管顺序往下排；
- 窗口不够高时自动启用滚轮滚动，并在右侧画滚动条；
- 用 scissor 裁剪内容区，滚动时不会盖住标题；
- 完全移出可视区的控件置为不可见，因此也**点不到**（避免看不见却能误点）。

`PermissionsScreen`、`CapabilitiesScreen`、`ConfigScreen`、`MainSettingsScreen` 全部改为继承它。
其中 `ConfigScreen` 的字段标签改用控件自身的 `getY()` 定位，滚动时会自动跟随
（原来是写死的屏幕坐标，一滚就错位）。

## 69. 【界面/配置】设置改动立即生效

**问题**：配置改动需要重启游戏才生效。

**修复**：新增 `RuntimeSettings` —— 行为类设置的运行时快照。
启动时和每次点"保存"后各刷新一次，其余时间读普通字段（也让每 tick 的热路径
不必去查 config spec）。

同时在权限页新增了三个开关：**分层 Agent**、**自主行为**、**主动说话**、**进度播报**，
以及**跟随距离**循环选择。

## 70. 【体验】工具使用与提示词

- `AgentDecisionParser` 的旧动作名映射扩充（`dig`/`chop`/`equip`/`drop`/`chat`…），
  模型即使沿用旧词表也能正确映射到工具。
- 系统提示新增规则 R9–R13：以玩家为中心活动、挖矿不要指定坐标也不要先走远、
  玩家被打要直接反击、不确定就待在附近、**优先使用背包里已有的东西**。

## 71. 构建验证

```
.\gradlew.bat compileJava            → BUILD SUCCESSFUL
.\gradlew.bat build -x test fatJar   → BUILD SUCCESSFUL
产物：build/libs/aisteve-1.0.0-all.jar
```

## 72. 新增/变更配置项一览

```toml
[agent]
enabled            = true    # 分层 Agent 总开关（界面可改，立即生效）
autonomy           = true    # 自主行为
roamRadius         = 48      # 以玩家为中心的活动半径（16–256）
idleChat           = true    # 主动说话
progressNarration  = true    # 进度播报（先说要做啥，做完汇报）
defendAgainstPlayers = true  # 允许在 PvP 中反击攻击队友的人
```

---

# 第十三阶段：仓库整理与工程规范

> 目标：把项目整理成一个**自包含、可协作、可发布**的标准仓库，
> 并明确"现在能做什么、不能做什么"。
> 本阶段**没有改动任何游戏内行为**，只动工程与文档。

## 73. 文档归档与目录规范

原来项目文档散落在工作区根目录，仓库并不自包含（克隆下来看不到使用说明与变更记录）。
现在全部归入仓库并统一命名：

| 原位置 | 新位置 |
| --- | --- |
| `AiSteve-变更记录.md` | `CHANGELOG.md` |
| `AiSteve-使用说明.md` | `docs/USAGE.zh-CN.md` |
| `AiSteve-本地构建与使用指南.md` | `docs/BUILD.zh-CN.md` |
| `方案文档.md` | `docs/DESIGN-BRIEF.zh-CN.md` |
| `migrate-to-aisteve.ps1` | `scripts/migrate-to-aisteve.ps1` |

命名规则：**中文文档一律带 `.zh-CN` 后缀**，与英文文档成对出现，便于识别与互链。

## 74. 新增 `docs/STATUS.md` —— 现状与不足

这是本阶段最重要的一份文档。它按可验证的事实，而不是按愿望，写清三件事：

1. **已实现**：分层运行时、22 个工具、16 个动作、四层记忆、6 个技能、动态目标栈、反思、
   播报、保护玩家、活动范围、可滚动设置界面、多服务商容错。
2. **未实现**，并标注影响与严重度 —— 其中两条是 🔴：
   - **没有熔炼 → 铁器时代到不了**：1.20.1 挖铁矿掉粗铁，粗铁要烧成铁锭才能做铁镐。
     于是 AI 走到铁就停住，**钻石也随之不可能**（需要铁镐）。
   - **AI 目前完全无敌**：`hurt()` 恒返回 `false`。所以"生存"那一半
     （`SAFETY` 需求、`SURVIVE` 目标、`flee` 工具）**永远不会被真实受伤触发**。
3. **已知不足**：无敌带来的语义不一致、铁链断裂、关键词匹配偏脆、
   方块感知节流 3 秒、`equip_item` 是刻意空操作、语义记忆是词法非向量、
   **单元测试全是空壳**、模型质量依赖等。

同时给出按投入产出比排序的路线图（P0 修语义 → P1 提升表现 → P2 扩内容 → P3 工程化 → P4 多智能体）。

> 写这份文档时的取舍：**宁可暴露短板，也不写一份看起来完美但会误导人的说明。**
> 玩家照着文档试、结果发现对不上，比一开始就知道缺什么要糟糕得多。

## 75. 中英文 README

新增 `README.zh-CN.md`，并把 `README.md` 重写为英文主版本（GitHub 默认展示）。
两份内容对等，顶部互相链接。

覆盖：项目定位、架构思想（含"为什么不是又一个 LLM→动作 外壳"）、功能清单、
环境要求、安装、指令表、配置、**现状摘要（链接到 STATUS）**、构建、目录结构、
升级步骤、排错、贡献、许可。

## 76. 仓库规范文件

| 文件 | 作用 |
| --- | --- |
| `LICENSE` | MIT；同时署名上游 `YuvDwi/Steve`（本仓库是衍生作品） |
| `.gitattributes` | 行尾统一：源码 `eol=lf`，`*.ps1`/`*.bat` 保持 CRLF，`gradle-wrapper.jar` 标记为 binary 并**保留版本控制** |
| `.gitignore` | 重写，分区注释；显式排除 `build/`、`.gradle/`、`run/`、`logs/`、`mcmodsrepo/`、`*.log`、媒体文件与 `*.nbt` |
| `CONTRIBUTING.md` | 开发环境、**8 条不能破坏的架构铁律**（每条都注明了它对应哪个真实 bug）、逐层扩展指南、Conventional Commits 规范、PR 检查清单 |

`.gitignore` 特意加了**显式例外**，保证这些文件仍被跟踪：

```
!gradle/wrapper/gradle-wrapper.jar   # 没有它 ./gradlew 就跑不起来
!config/aisteve-common.toml.example  # 模板里没有密钥，应当入库
!build.gradle
```

## 77. 排除编译产物与依赖

明确确认**以下内容不入库**：`build/`、`.gradle/`、`run/`、`logs/`、`.idea/`、
真实 API Key 配置、`*.nbt` 生成物、媒体文件。

`gradle/wrapper/gradle-wrapper.jar` 是**唯一保留的二进制**，因为它是 Gradle Wrapper
能在没装 Gradle 的机器上工作的前提，属于标准做法（见 `.gitattributes` 中的说明）。

## 78. 脚本修正

- `scripts/run_steve.sh`：原来硬编码 `JAVA_HOME="$PWD/jdk-17.0.2.jdk/Contents/Home"`，
  在任何没有该目录的机器上必然失败。重写为：检测 `java` 是否存在、校验**是否恰好为 17**、
  提前警告缺少 API Key（否则客户端能启动但 AI 静默无响应，看起来像模组坏了），再启动。
- `scripts/migrate-to-aisteve.ps1` 与 `TROUBLESHOOTING.md`：把写死的本机路径
  （`D:\agnet-work\...`）和占位仓库地址换成相对路径与真实仓库地址。

## 79. 绑定远程仓库

远程由上游 `YuvDwi/Steve` 改为本项目仓库：

```
git remote set-url origin https://github.com/Tawesh/AiSteve.git
```

## 80. 本阶段验证

- ✅ 未改动任何 Java 源码，因此沿用第十二阶段的构建结果
  （`compileJava` / `build -x test fatJar` 均 BUILD SUCCESSFUL）。
- ✅ `git status` 确认无 `build/`、`.gradle/`、`run/` 等产物进入暂存区。
- ✅ 文档内链检查：README ↔ README.zh-CN ↔ CHANGELOG ↔ STATUS 相互可达。


