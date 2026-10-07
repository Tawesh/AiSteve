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

---

# 第十四阶段：多语言适配（随客户端 / 随玩家语言）

> **需求**：模组的语言应该跟着玩家的客户端语言走 —— 客户端是英文，模组就显示英文。

## 81. 先厘清一个技术前提：界面能自动适配，AI 的话不能

Minecraft 的语言设置是**纯客户端**的：`LanguageManager` 只存在于客户端，**服务端拿不到任何客户端的语言**。

于是这个问题必须拆成两半，它们的解法完全不同：

| 类型 | 例子 | 能否自动跟随客户端语言 |
| --- | --- | --- |
| **A. 模组固定文本** | 设置界面、`/as` 指令反馈、按键名 | ✅ **能** —— 用 `Component.translatable`，文本在**客户端渲染时**按该客户端语言解析 |
| **B. AI 说出来的话** | 聊天回复、进度播报、"搞定了" | ❌ **不能** —— 这些是服务端拼好的字符串再广播，服务端根本不知道收件人设了什么语言 |

A 类不需要任何配置就是对的：同一个服务器上，中文客户端看到中文、英文客户端看到英文。

B 类只能选一种策略。最终采用 **跟随玩家的说话语言**：你用英文问它，它就用英文回你；
用中文问就用中文回。这是最像真人的做法 —— 你不会去"配置"一个队友用什么语言回你。

## 82. 【新】`i18n` 层

- `ConversationLanguage` —— 对话语言枚举（`zh_cn` / `en_us`），带 `promptName()`（给 LLM 看的语言名）。
- `AgentLang` —— 服务端语言包加载 + 查找 + **语言推断**。

**语言推断用字符集比例而不是语言检测库**：
统计中日韩字符与拉丁字母的占比，`cjk/letters ≥ 15%` 判为中文，否则英文；
字母少于 2 个（`"ok"`、`"?"`）视为信息量不足，**不切换语言**。
零依赖、零成本，且中英混写（"帮我 mine 20 iron"）也能正确判成中文。

**资源放在 `assets/aisteve/agent/strings_<locale>.json`**，刻意**不放**在 `lang/`：
`lang/` 会被客户端资源加载器当作客户端翻译加载，而这两类文本的消费方完全不同。

## 83. A 类：模组固定文本 → `translatable`

- 四个设置界面（主菜单 / 大模型 / 权限与行为 / 能力开关）全部改为 `Component.translatable`。
- `ScrollableSettingsScreen.hintText()` 由 `String` 改为 `Component`，标题与提示也跟着走。
- `/as` 全部指令反馈改为 `translatable`。
- 玩家登录提示、重复实体清理提示改为 `translatable`。
- 语言文件从原来只有 2 个按键绑定，扩到 **92 个键**（中英各一份）。

## 84. B 类：AI 的话 → 跟随玩家语言

- `SteveEntity.hearPlayer()` 在收到玩家发言时调用 `AgentLang.observePlayerSpeech(text)`，
  由此切换 AI 的说话语言。
- AI 的全部输出改走 `AgentLang.t(key, args)`：播报、闲聊、技能叙述、工具结果、反思原因。
- 提示词注入**输出语言指令**，并本地化**示例**（示例里的 `reply` 直接示范了期望的输出语言）：
  > 你现在用 **English** 说话。上面这些说明本身的语言不重要 —— 但你产出的所有自然语言内容必须是 English。
  > 注意：JSON 的**字段名**和**工具名**必须保持英文写法不变，只有内容用 English。
- 新增 `/as lang [zh_cn|en_us]` 手动指定；设置界面也加了语言切换项。
- 新增配置项 `[agent].language`（默认 `zh_cn`）—— 这是**起始语言**和**没人说话时的兜底**。

## 85. 【工具】语言键交叉校验脚本

多语言最容易出的错是**键名打错或漏配**，而它在运行时的表现是聊天里直接冒出
`agent.narrate.plan_one` 这种字符串 —— 玩家看到会以为模组坏了。

新增两个零依赖的 Node 脚本（`scripts/`）：

| 脚本 | 作用 |
| --- | --- |
| `check-lang.js` | 校验四份 JSON 是否合法，并检查中英两份**键位是否完全配对** |
| `check-keys.js` | 从 Java 源码里抽出所有 `AgentLang.t("...")` 与 `Component.translatable("...")`，比对语言包，报告**已引用但未定义**和**已定义但未引用**的键 |

**它当场抓到了一个真实缺陷**：我在插入提示词键时误删了 `agent.narrate.plan_one`，
两边语言包都少了这个键 —— 运行时会直接把键名显示给玩家。已修复。

## 86. 【修复】英文 README 里混入的中文

**问题**：`README.md` 是英文文档，但里面有多处中文残留。

**根因**：它是拿中文版**改写**出来的，表格与示例段落是复制后局部替换的，漏改的地方就留下来了。
这属于流程问题，不是笔误 —— 所以除了修内容，还要加一道自动检查（见第 87 条）。

**修掉的具体内容**：

| 位置 | 原文 | 修正 |
| --- | --- | --- |
| 记忆示例 | `"喜欢建房子"` | `"likes building"` |
| 反思示例 | `"需要铁镐"` | `"I need an iron pickaxe"` |
| 技能匹配表 | `"去挖点铁" … （确定性，0 token）` | 改为英文请求，并去掉中文全角括号 |
| `/as` 示例 | `/as 帮我弄一个羊排` 等 4 条 | 改为英文，并补一句"中英皆可" |
| **设置界面名** | `大模型配置` / `AI 权限与行为` / `AI 能力开关` | 改为英文客户端**实际显示**的名字 |

其中最后一条是**实质性错误**，不只是语言问题：英文客户端的界面显示的是
`Language Model` / `AI Permissions & Behaviour` / `AI Capabilities`（见 `en_us.json`），
而英文 README 写的是中文页名 —— 英文玩家照着找会找不到。

**顺带修正**：`ARCHITECTURE.md`、`docs/STATUS.md`、`CHANGELOG.md` 引用处补上语言标注；
文档索引表新增 **Language** 列，并明确告知英文读者"架构与现状文档目前只有中文"。

## 87. 【工具】新增文档语言校验 `scripts/check-docs-lang.js`

只修一次没有意义 —— 只要还有人照着中文版改英文版，同样的问题就会再犯。所以把它自动化：

- **英文文档**（`README.md` / `CONTRIBUTING.md` / `CLAUDE.md`）不得含中日韩字符，
  仅允许三种例外：语言切换链接、正在讨论中文的句子、以及引用代码里的中文字符串常量。
- **中文文档**（7 份）反过来校验：中文行占比不得低于 10%，防止被误"翻成"英文而破坏 `.zh-CN` 配对。
- 退出码非 0 即为失败，可直接接进 CI。

> 这条检查本身也是踩坑产物：我第一版脚本把 `ARCHITECTURE.md` 列进了"英文文档"，
> 于是刷出一屏假告警。校验脚本的分类错了，比没有脚本更误导人 —— 已按文档**实际语言**重新归类。

## 88. 本阶段验证

```
node scripts/check-lang.js      -> 4 份 JSON 合法，中英键位完全配对
node scripts/check-keys.js      -> 引用 214 键，缺失 0
node scripts/check-docs-lang.js -> 英文文档无中文残留；7 份中文文档语言正确
gradlew build -x test fatJar    -> BUILD SUCCESSFUL
jar 内容校验                     -> lang/*.json 与 agent/*.json 均已打包
```

## 89. 已知边界

- **提示词正文与工具说明仍是中文**：它们是给模型看的内部指令，不是玩家可见文本。
  输出语言由第 84 条的指令控制，主流模型（含 DeepSeek / GPT / Claude / Qwen）都能正确处理
  "指令语言 ≠ 输出语言"。彻底本地化工具说明需要新增约 150 条键，收益有限，暂不做。
- **多人服务器上 AI 只有一种语言**：它跟随**最后跟它说话的人**。中英玩家交替发言时语言会来回切。
  这是"一个 AI 一个语言"的固有限制，要彻底解决需要按玩家分别维护对话状态。
- **架构文档与现状文档目前只有中文**：英文读者暂时只能靠 README / CONTRIBUTING / CLAUDE.md
  里的摘要。补英文版属独立的翻译工作，尚未开始。

---

# 第十五阶段：合成数量与交付反馈

> **玩家反馈原文**："这个模组存在一个 bug：合成的数量无法控制。当用户下达任务：比如我需要一个工作台。
> 它会去找木头合成，但是没有默认合成一个，而是合成了多个，全部给了玩家，然后就会提示无法完成，这很矛盾。"
>
> 顺着这句话查下去，它不是"合成数量"这一个问题，而是**四个互相独立的缺陷**叠在一起：
> 参数被静默丢弃、`give` 漏参数就交出全部、"已经给了却说做不了"、以及"自己造工作台"顺手吃掉本任务的料。
> 前三条正好分别对应反馈里的"数量无法控制 / 全部给了玩家 / 提示无法完成"。
>
> 改动都落在**参数层与动作层**（`Task` / `CraftItemAction` / `GiveItemAction` / `SteveEntity`），
> 因此两条路径都受益：默认的分层 Agent Runtime（`craft_item` / `give_item` → 动作队列）与
> 旧的一次性 planner（`craft` / `give` 动作）走的是同一批动作实现。

## 90. 【严重·逻辑】"数量无法控制"：模型写 `"quantity": "4"` 就等于没写

**文件**：`action/Task.java`

**现象**：玩家要 N 个，它只做 1 个；反过来"我要 1 个它做一堆"是另一条（第 92 条）。
日志里没有任何异常 —— 因为它**根本没报错**。

**根因**：`getIntParameter` 只认 `Number`：

```java
Object value = parameters.get(key);
if (value instanceof Number) return ((Number) value).intValue();
return defaultValue;                 // ← 字符串 "4" 直接落到默认值
```

而 `ResponseParser.parseTask` 会把 JSON 里的原始类型原样塞进参数表，于是模型写
`{"quantity": "4"}`（在中文提示词下模型加引号非常常见）时，数量就被**静默替换成默认值 1**。
参数表里数字在、日志里任务在、动作在跑，只有数量不见了 —— 这种 bug 最难查，
因为它不产生任何错误信号。

**修复**：从"类型判定"改成"强转"（`coerceInt`）：

| 输入 | 结果 |
| --- | --- |
| `4` / `4L` / `4.0` / `4.6` | 4 / 4 / 4 / 5 |
| `"4"` / `" 4 "` / `"4.0"` / `"4,000"` | 4 / 4 / 4 / 4000 |
| `"4 个"` / `"x4"` | 4 / 4 |
| `true` / `false` | 1 / 0 |
| `null` / `""` / `"all"` / `"很多"` | 默认值 |

**影响面**：所有数量参数一起修好 —— `craft`（quantity）、`mine`、`gather`、`farm`、`fish`、
`attack`、`give`（count）、`explore`（distance）、`place`/`pathfind`（坐标）、`loot_container`（limit）。
"参数被吃掉"这类坑不会只发生在合成上。

## 91. 【严重·逻辑】"全部给了玩家"：`give` 漏写物品名 = 交出整个背包

**文件**：`action/actions/GiveItemAction.java`

**根因**：

```java
itemName = task.getStringParameter("item", "all");   // ← 默认 all！
```

模型漏掉 `item` 参数时不是报错，而是"把身上所有东西都给你"。叠加 `give_item` 工具说明里
把 `all` 描述成"全部交出"，就变成了"只说了一句话，背包全倒给你"。

**修复**：`item` 改为**必填**。缺失或空白 → 明确失败并说清原因
（"没有告诉我要给你什么，我不乱塞东西：你想要哪个？"）。真的要全交，必须显式写 `all`
（或 `count <= 0`）。

## 92. 【严重·逻辑】"东西都给我了，它却说做不了"（两条独立原因）

**(a) 重复交付被当成失败**

`GiveItemAction` 在背包里没有目标物品时一律报 `ActionResult.failure("我身上没有 X，给不了玩家")`
—— 可这条消息**在两种完全不同的情况下都会出现**：

1. 我从来没有过 X → 真失败，应该重新想办法；
2. 我刚才已经把 X 交给玩家了 → 目标其实**已经完成**。

模型的计划经常把"合成 → 交付"重复一轮（重试、或 goal_update 之后又走一遍）。第二次交付时背包
已经空了，于是：报失败 → 反思 → 重规划 → 三次之后播报"「XX」我暂时做不了"。
玩家这边的体验就是反馈里的**"全给我了，又说做不了"**。

**修复**：`SteveEntity` 增加一份会话级的交付记录
（`recordGivingToPlayer` / `wasGivenToPlayerRecently`，窗口 2 分钟，最多 32 条）。
再次交付同一物品而背包已空时，如实回答"刚才已经把 X 给你了，我身上现在没有了"，
并按**已完成**（`ActionResult.partial`）处理 —— 不再触发重规划，也不会再播报"做不了"。

> 这份记录刻意**不进 NBT**：它只用于消除"同一秒钟内重复交付"的歧义，重启后还留着反而会误报"已经给过"。

**(b) "自己造一个工作台"吃掉了本任务的材料**

`CraftItemAction.ensureCraftingTableAvailable()` 的老逻辑：3×3 配方附近没有工作台时，
把背包里的木板做成工作台，**并放到世界上**。问题是那批木板很可能正是本任务要用的：

```
手上有 4 个木板，要做木镐（3 木板 + 2 木棍，且需要工作台）
  ├ 老逻辑：4 木板 → 工作台 → 放地上
  └ 真正合成时木板为 0 → "I'm missing materials for wooden_pickaxe"
       → 反思 → 重规划 → 3 次后"我暂时做不了"
```

于是玩家看到的是：AI 一边播报"合成了 4 个木板"，一边宣告失败。这也是**"合成数量不受控"的观感来源**
—— 为了做 1 件东西，额外吃掉了 4 个木板，而玩家完全不知道这 4 个木板去哪了。

**修复**：造工作台之前先算**目标配方自己需要多少材料**
（`targetMaterialsPerBatch()` × `batchesWanted`，按背包里**真实存在**的物品对应材料种类，
因此任意木板/橡木木板这类标签材料都能算准），只有真正的余量才允许拿去做工作台；
没有余量就如实失败："做 XX 需要一个工作台：附近没有现成的，而我手上这些材料得留着做它本身。
先再弄点木头/木板给我吧" —— 交给反思层去要材料，跟真人玩家的顺序一致：**先备料，再打工作台**。

## 93. 【可诊断性】把"配方每批产出"讲清楚

**文件**：`action/actions/CraftItemAction.java`

木板 / 木棍 / 火把这类配方是**一批出 4 个**。玩家要 1 个，聊天里却看到"合成了 4 个"，
在没有任何解释的情况下，最自然的解读就是"数量失控"。

现在：
- 产出与需求不一致时补一句 ——"合成了 4 个 木板（你要 1 个，这个配方每批产出 4 个）"；
- "背包里已经有了"的分支同时报出**已有多少 / 这次要多少**，而不是只说"不用再合成"；
- 日志补上 `asked for N` 字段，排查时能立刻看出"想要"和"做了什么"是否一致。

## 94. 【构建】源码是 UTF-8，编译却用平台默认编码

**文件**：`build.gradle`

**现象**：在中文 Windows（JVM 默认 GBK）上执行文档里的构建命令，
`compileJava` 会刷出一屏 `unmappable character for encoding GBK`，指向几十个
**与本次改动完全无关**的文件（`ActionExecutor`、`ExploreAction`、`AgentPromptBuilder`…），
看起来像代码坏了。实际原因只是 javac 没有指定编码。

**修复**：

```gradle
tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}
```

这条与游戏逻辑无关，但它决定了"**能不能验证**" —— 先修它，后面的验证才有意义。

## 95. 【测试】第一个带断言的单元测试

**文件**：`src/test/java/com/steve/ai/action/TaskTest.java`

在此之前 `src/test` 全是空壳（`docs/STATUS.md` 里如实写着"没有实际断言"）。
新增 6 个断言，专门盯住第 90 条这个回归：`4` / `4L` / `4.0` / `4.6` / `"4"` / `" 4 "` /
`"4.0"` / `"4,000"` / `"4 个"` / `"x4"` / `"all"` / 缺省 / `true` / `false` 各自的取值。

选它做第一个测试是有意的：**纯逻辑、不依赖 Minecraft 运行时**（毫秒级跑完，不需要启动游戏），
而且正好是"数量失控"这个玩家可见问题的入口。

## 96. 本阶段验证

```
gradlew compileJava                     -> BUILD SUCCESSFUL
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
gradlew test --tests *TaskTest          -> 6 tests, 0 failures
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
node scripts/check-keys.js              -> 引用 214 键，缺失 0
node scripts/check-docs-lang.js         -> 7 份中文文档 + 3 份英文文档语言正确
产物                                     -> build/libs/aisteve-1.0.0-all.jar
```

**未验证**：以上都是构建与纯逻辑层面的验证。游戏内的"我需要一个工作台"仍需实测确认，
预期行为是 —— 砍木头 → 合成木板（并说明每批 4 个）→ 合成工作台 → 交给你；
中途材料不够时**如实说需要更多木头**，而不是"先塞给你一堆东西、然后宣布做不了"。

## 97. 已知边界（本阶段留下的）

- **原版配方一批出 4 个时，"要 1 个"在物理上只能得到 4 个**。现在至少把原因说出来了，
  但"按玩家要的数量精确产出"在原版配方体系下不可能做到 —— 这属于游戏规则，不是模组缺陷。
- **交付记录是会话级的**，重启后"刚刚给过"的判断失效（刻意如此，避免误报）。
- **动作层仍有玩家可见的硬编码字符串**：`ActionResult` 的 message 会经
  `agent.narrate.cannot_do` 播报出去，按本仓库的两条本地化轨道，它们本应走 `AgentLang`。
  这次只把新写/改动的那几句写成了中文，全量收敛属独立工作（英文玩家目前会看到中文的动作原因）。
- **目标级去重仍然没做**：本阶段挡掉的是"同一个物品被重复交付"（交付记录）。
  如果模型每轮都把同一件事当成**新目标**提交（`goal_update` 换个说法），理论上仍可能再合成一次，
  只是不会再绕到"做不了"上。要彻底解决属于 `brain` 层的工作（目标去重 / 目标完成判据），
  涉及优先级与自主行为的语义，不适合和这次的 Bug 修复混在一起动。

---

# 第十六阶段：背包可视（空手右击打开 AI 背包）

> **需求原文**："我希望玩家手里没拿东西时，右击 AI 玩家可以打开 AI 玩家的背包，查看内容。"
>
> 在此之前，"它到底有什么"只有两条路：`/as info`（一行压缩文本，物品一多就看不清）或者挨个试。
> 本阶段补上正式的容器界面。**空白手右击 → 只读背包窗口**；手持物品右击 → 仍然是原来的"交给它 1 个"。

## 98. 【新功能】`menu` 层 —— 只读的 AI 背包容器

**文件**：`menu/SteveInventoryMenu.java`、`menu/SteveInventoryContainer.java`（均新增）

用**原版容器体系**实现，而不是自己画一个列表控件或往聊天框里刷文本 —— 这样槽位、悬浮提示、
物品图标渲染、`E` 关闭、走远自动关闭全部由原版负责，模组只需要给出"槽位背后是什么"。

**两侧用不同的 Container，这是本阶段最容易踩的坑：**

| 侧 | 容器 | 为什么 |
| --- | --- | --- |
| 服务端 | `SteveInventoryContainer`（直读 `SteveInventory`） | 槽位每 tick 被 `broadcastChanges()` 轮询，于是**实时同步**：它在合成东西时，你看着物品一格一格出现，不需要任何变更通知机制 |
| 客户端 | 普通 `SimpleContainer` | **必须可写**：`AbstractContainerMenu#initializeContents` 是把服务端发来的物品经 `Slot#set` **写回客户端容器**的。如果客户端也用只读容器，服务端发来的内容会被静默丢弃，窗口永远是空的 |

**只读是刻意的**，并且做了两层保障：
- 槽位重写 `mayPickup` / `mayPlace` 返回 `false`（原版在 `clicked` 与 `moveItemStackTo` 里先查这两项，
  所以点击、Shift 点击都不会搬东西，也不会出现"拿着空气物品"的光标态）；
- 容器本身把所有变更方法实现为**空操作**（而不是抛未实现异常），作为第二道防线。

理由不是"懒得做"，而是它的背包正是它**自己干活时依据的状态**：`CraftItemAction` 会在真正扣材料
之前先算出"这一批要扣哪些"，玩家中途把东西抽走会让这个计划对不上。给它东西、拿回东西各有明确通道
（右键 / `/as give` / `/as take`），而且都会在聊天里回话。

布局：4 行 × 9 格 = 36 格（对齐 `SteveInventory.MAX_SLOTS`），下面接玩家自己的 3 行 + 快捷栏，
这样窗口看起来就是个普通容器。槽位坐标定义在菜单里的**公开常量**，屏幕照着画，视觉与真实槽位不会错位。

## 99. 【新功能】注册 MenuType 与网络附加数据

**文件**：`SteveMod.java`

- 新增 `DeferredRegister<MenuType<?>> MENUS`（与 `ENTITIES` 分开，是另一个注册表），
  在构造函数里一起挂到 mod event bus。
- `STEVE_INVENTORY_MENU` 用 `IForgeMenuType.create(SteveInventoryMenu::fromNetwork)` 注册。

为什么不用 `player.openMenu(provider)`（不带数据）就完事：客户端需要知道**打开的是哪一个实体的背包**。
 `NetworkHooks.openScreen(player, provider, buf -> buf.writeVarInt(this.getId()))` 把实体 id 随窗口一起送过去，
`IForgeMenuType` 的工厂就能在客户端读到它。

**同时留了兜底**：`fromNetwork` 里如果缓冲区为空或读到的实体找不到，会退回"在客户端世界里找那个 AI"
—— 本模组由 `SteveManager` 强制**同一时间只能有一个 AI**，所以"那个 AI"本身没有歧义。
这样一来，即使附加数据的传递方式在某个 Forge 版本上变了，玩家看到的也只是"窗口照常打开"，
而不是一个空白窗口。

## 100. 【新功能】`SteveInventoryScreen` —— 程序化绘制的窗口

**文件**：`client/gui/SteveInventoryScreen.java`（新增）

继承 `AbstractContainerScreen` 拿到全部容器行为，但**不贴图**：背景和槽位用 `fill` 画出来，
与本仓库既有的设置界面（`ScrollableSettingsScreen`）风格一致。原版能给的唯一贴图是"6 行大箱子"，
形状不对（我们只要 4 行），还得把 Mojang 的美术资源重复打包一份进 mod。窗口尺寸也一并写死在
菜单的公开常量里，避免"画的槽位"和"真实槽位"两处各写一套坐标。

细节：
- 背包为空时在格子里居中写一句"背包是空的" —— 空网格没有任何解释时，看起来像窗口坏了；
- 底部一行只读说明（"只读窗口：东西归 AI 自己管"），避免玩家反复点击槽位以为是卡了；
- 标题是 `aisteve.gui.inventory.title` + AI 名字，走 `Component.translatable`，
  因此**每个客户端按自己的语言显示**（符合本仓库第 81-84 条确定的两条本地化轨道）。

## 101. 【接入】空手右击 → 打开背包

**文件**：`entity/SteveEntity.java`

`mobInteract` 分成两支，行为完全由"手上有没有东西"决定：

```
手上有物品 → 交给它 1 个（原有行为，未改动）
手上空着   → 打开只读背包窗口（新增）
```

实体侧新增 `openInventoryView(ServerPlayer)`，是唯一调用 `NetworkHooks.openScreen` 的地方。

顺带确认了一条容易忽略的原版细节：`Minecraft#startUseItem` 会用**客户端**的交互结果
（`consumesAction()`）决定是否继续尝试另一只手，因此客户端在 `mobInteract` 里返回 `SUCCESS` 是必要的，
空手这一下才会就此结束，而不会漏到"使用物品"的分支上去。原代码在最上面就 `return SUCCESS`，
这条行为本阶段保持不变。

## 102. 【构建/文档】注册界面时踩到的一个版本差异

**文件**：`client/ClientSetup.java`

第一版用了 `RegisterMenuScreensEvent` —— 编译直接报"找不到符号"。核对 Forge 1.20.1 的
`forge-1.20.1-47.2.0_mapped_official_1.20.1.jar` 后确认：**这个事件在 1.20.1 里根本不存在**
（它是后续版本才加的），1.20.1 只有 `MenuScreens.register(...)`。最终改为在
`FMLClientSetupEvent#enqueueWork` 里注册，并把这条差异写进了 `CLAUDE.md` 的
"Add a container GUI"一节 —— 下次不必再靠试错发现。

同一轮还顺手用 `javap` 核实了两件事，都不是猜的：
- `MenuType#create(int, Inventory, FriendlyByteBuf)` 的字节码里会判断内部 `MenuSupplier`
  是否为 `IContainerFactory`，是则**把缓冲区透传给工厂** → 附加数据机制成立；
- `ServerPlayer` 里确实存在 `AbstractContainerMenu#broadcastChanges()` 与 `#stillValid(...)` 的调用点
  → 实时同步与"走远自动关闭"都成立。

## 103. 本阶段验证

```
gradlew compileJava                     -> BUILD SUCCESSFUL
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
gradlew test --tests *TaskTest          -> 6 tests, 0 failures
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
node scripts/check-keys.js              -> 引用 217 键，缺失 0
node scripts/check-docs-lang.js         -> 7 份中文文档 + 3 份英文文档语言正确
```

新增语言键（两条轨道里的**模组 UI 轨道**，中英各 3 条，共 95 键/语言）：

| 键 | 中文 | 英文 |
| --- | --- | --- |
| `aisteve.gui.inventory.title` | `%s 的背包` | `%s's Backpack` |
| `aisteve.gui.inventory.empty` | `背包是空的` | `The backpack is empty` |
| `aisteve.gui.inventory.readonly` | `只读窗口：东西归 AI 自己管（空手右击可再次打开）` | `Read-only: the AI manages its own items (right-click empty-handed to reopen)` |

**未验证**：需要在游戏内右键确认的三件事 —— 窗口能否正常打开、物品是否随它的动作实时刷新、
以及走远 8 格是否自动关闭。逻辑链路（容器两侧实现、`broadcastChanges`、`stillValid`）已按上面的
字节码核对过，但**没有实际启动客户端验证过渲染效果**。

## 104. 已知边界（本阶段留下的）

- **只读**：槽位不能取、不能放、Shift 点击不搬运。理由见第 98 条（它的背包是动作计划的前置状态）。
  要拿回来用 `/as take`（一次全取）—— "从窗口里只取出某几格"是有意义的后续需求，
  但它需要先解决"取出的东西和正在进行的合成步骤怎么对账"，不适合顺手加。
- **没有 `/as inv` 之类的远程打开方式**：本阶段只做了需求里说的"右击"。
  服务器上远程查看仍需 `/as info`。
- **窗口不会因它开始做事而自动关闭**，只是内容会实时变化。这是有意的：看着它一路把材料合成出来，
  正是这个窗口的价值。

---

# 第十七阶段：数量对不上（"给了 4 个、背包还是 3 个"）

> **玩家反馈原文**："它并没有说没有。而是我说给我一个工作台。我看了它背包里面有3个。但它给我四个。
> 它的背包里面并没有减少一个。还是三个。"
>
> 这不是"数量算错"，而是**物品总数不守恒**：玩家拿到 4 个，AI 那边一个都没少。
> 本阶段做了三件事：用字节码把能证明的东西证明掉、把**能给出去却不扣背包**的那条路径彻底封死、
> 并把"先用背包里已有的材料"补上。**同时必须说明：我没有复现，这条修复是"封死可疑路径"而不是"锁定凶手"** ——
> 依据见下面第 105 条，需要日志才能最终确认。

## 105. 先排除掉两个嫌疑（都用字节码核实，不靠记忆）

猜是不负责任的，所以直接读 Forge 1.20.1 映射后的字节码：

**(1) 背包窗口是不是根本不刷新？** —— 不是。
`AbstractContainerMenu#broadcastChanges()` 每 tick 遍历所有槽位，调用
`synchronizeSlotToRemote(i, itemstack, supplier)`；后者与 `remoteSlots` 比对，
只有**真的变了**才 `sendSlotChange`。也就是说：物品消失（3 → 2）属于"变了"，一定会推给客户端。
**所以"窗口里还是 3"意味着服务端背包里真的是 3，不是显示没刷新。**

**(2) `GiveItemAction` 会不会把放不下的东西复制一份？** —— 不会。
`Inventory#add(int, ItemStack)` 的字节码里，存入后调用
`ItemStack.setCount(addResource(...))`，即**就地消耗传入的堆**，返回值只表示"是否全部放下"。
所以旧代码 `if (!stored) player.drop(toGive, false);` 掉的是**剩下的那部分**，总数守恒。
（我原本怀疑这里复制了物品，核对后**排除了**这个假设，没有去改一个不是 bug 的地方。）

> ⚠️ **这一条的结论是错的，已在第 109 条更正。** 当时我只问了"它是否复制了一份物品"，答案是"没有"，
> 于是下了"总数守恒"的判断。但我漏掉了同一个特性带来的另一个后果：既然 `add` 会把堆消耗成"剩余量"，
> 那么紧接着读 `toGive.getCount()` 得到的**不是交出去的数量，而是没放下的数量（正常路径上是 0）**。
> 扣除数量与实际交付数量因此脱钩 —— 这正是本次要修的东西。**纠正记录比错误记录本身更有价值**，
> 所以这里保留原文，只标注结论作废。

## 106. 同一轮里另外一条能交付却不扣背包的路径：只读窗口

上一阶段新加的背包窗口也是"能交付物品、又不动 AI 背包"的代码，问题出在两处：

**(a) `getItem()` 返回的是背包里的真实 `ItemStack` 引用。**
`AbstractContainerMenu#doClick` 的某些分支（最典型的是**数字键 / F 键的热键交换**）会
`slot.getItem()` 拿到这个堆，然后**扩大玩家那一侧的堆**，而"从容器里扣掉"是另走
`container.removeItem(...)` 完成的 —— 而我们的只读容器把 `removeItem` 实现成了空操作。
两边一凑：**玩家拿到了东西，AI 背包原封不动**。

**(b) 只靠 `mayPickup` / `mayPlace` 拦不住所有点击类型。**
这两个标志表达的是意图，但它们只在原版"恰好检查了"的路径上生效。

**修复（两层，都是"结构性"而非"我希望它生效"）：**

1. `SteveInventoryMenu#clicked(...)` 直接覆盖：**任何指向 AI 那 36 格的点击，一律原样丢弃**，
   在原版解释它之前就返回。所有点击类型（PICKUP / QUICK_MOVE / SWAP / THROW / DRAG / PICKUP_ALL …）
   都必须经过这一层，所以这是硬保证。玩家自己的格子与"点到窗外"保持原版行为。
2. `SteveInventoryContainer#getItem(...)` 改为**返回副本**。这样即使还有哪条我没读到的原版分支
   拿着它去合并/改写，也碰不到真实背包。（没有额外开销：`broadcastChanges` 本来就要 copy 一次。）

> 顺带纠正上一阶段文档里的一句错话：那里写着"返回引用是有意的，因为反正要 copy"。
> 那个推理只考虑了同步路径，没有考虑**点击路径**会拿这个引用做别的事 —— 现在改成副本。

## 107. 【功能】"先判断背包里有没有"——合成自己补中间材料

反馈里"它不用自己背包里的东西、反而先去搞原料"是同一个问题的另一半，本阶段一并补上
（**这是可以确定做到的部分**）：

`CraftItemAction` 原先只检查**最终配方**的材料。于是"背包里有 4 根原木，要一把木镐"会被判成
"缺 oak_planks / stick"，AI 就跑去砍树 —— 而它本来只要把原木切了就行。

现在 `doWork()` 在材料不足时先调用 `craftMissingIngredients(...)`：

- **只做配方真正需要的**材料（原木 → 木板 → 木棍），不做别的；
- **深度受限**（3 层），保证结束；
- 做 3×3 的中间配方**只在已有工作台时**才做 —— 不为了做中间材料再造一个台子；
- 没有配方的材料（矿石、小麦…）不动手，如实报"缺什么"交给反思层。

**工作台判定同时升级为干跑模拟**：`targetStillMakeableAfterTable(...)` 先模拟"扣掉 4 块木板做台子，
目标还做不做得出来"，并且**会连多级链条一起算**（正好用上第 (1) 条新加的 `canMakeFrom`）。
好处是"4 根原木 → 1 个工作台 + 1 把木镐"这种完全正当的做法不会再被误判成材料不够。
干跑成功时会把消耗**就地**从模拟数量里扣掉，这样多个材料槽位才是真的在争同一批材料。

## 108. 本阶段验证

```
gradlew compileJava                     -> BUILD SUCCESSFUL
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
产物                                     -> build/libs/aisteve-1.0.0-all.jar
```

**我做到的**：用字节码排除了两个假设、论证了"窗口显示 3 就真是 3"、并把
"能不扣背包就给物品"的路径从**结构和数据两层**封死。

**我没做到的（必须说清楚）**：我**没有复现**这个现象，因此不能说"凶手就是它"。
我只证明了两件事 —— 交付物品的旧代码是守恒的（第 105 条），以及窗口确实存在一条不守恒的路径（第 106 条）。
如果你手上还有当时的存档，请把这两行日志发我，就能立刻确认是哪条路径触发的：

```text
# 1) 它到底"给"了多少、走的是哪条路：
grep "gave" logs/latest.log            # Steve 'X' gave Nx minecraft:crafting_table to PLAYER

# 2) 它在那前后有没有自己又合成过一个工作台（3 → 4 就说明是"多做了"，而不是"多给了"）：
grep -E "CraftItemAction|crafted" logs/latest.log
```

---

# 第十八阶段：数量对不上（真正的根因）

> **玩家反馈**（第二次）："ai玩家给真人玩家的物品数量还是没控制住。我让它给它背包里面的一个。
> 它全部给了。我查看它的背包。还是显示四个，并未扣减。"
>
> **这次抓到了，而且能证明。** 根因是我在上一阶段**亲手判错**的那一处（第 105 条）：
> `Inventory#add` 会就地消耗传入的堆，而代码在调用**之后**才去读数量。

## 109. 【严重·逻辑】`Inventory#add` 就地消耗堆 → 扣除数量恒为 0

**文件**：`action/actions/GiveItemAction.java`

**字节码证据**（不是记忆，是 `javap` 读出来的）：

```
public boolean add(ItemStack);          →  add(-1, stack)
public boolean add(int, ItemStack);     →  stack.setCount(addResource(stack))
```

**`add` 会修改你传进去的那个 `ItemStack`**：调用后它只剩"没放下的部分"，而布尔返回值只表示
"是否全部放下"。原代码的写法是：

```java
ItemStack toGive = stack.copy();                    // 4 个
toGive.setCount(Math.min(toGive.getCount(), requestedCount - given));   // 想要 1 个 → 1 个
boolean stored = player.getInventory().add(toGive);  // ← 玩家拿到 1 个，toGive 被消耗成 0 个
if (!stored) player.drop(toGive, false);
steve.getInventory().removeItem(toGive.getItem(), toGive.getCount());   // ← removeItem(item, 0)！
given += toGive.getCount();                                             // ← given += 0
```

**三个症状同时出自这一处**，正好对上玩家的两轮反馈：

| 症状 | 机制 |
| --- | --- |
| **背包没扣减** | `removeItem(item, 0)` 什么都不扣 → 物品在被送给玩家的同时**还留在原地**（复制） |
| **"让它给一个，它全给了"** | `given` 恒为 0，于是 `given >= requestedCount` 这个"够了就停"的条件**永远不成立**，循环把每一种匹配的堆都发一遍 |
| **"然后就会提示无法完成"** | `giveTo` 返回 0 → 调用方判定失败 → 反思 → 重规划 → 模型再给一次 → **每重试一轮再多给一份** |

第三行还解释了最早那条反馈里"全给我了，却说做不了"的自相矛盾：
**它确实在给，同时它认为自己没给成。**

**修复**：在调用之前把数量定下来，并用**实际扣除的数量**记账。

```java
Item item = stack.getItem();
int amount = Math.min(stack.getCount(), requestedCount - given);   // 先定数量
ItemStack toGive = stack.copyWithCount(amount);                    // 给副本
boolean stored = player.getInventory().add(toGive);                // add 会吃掉这个副本
if (!stored) player.drop(toGive, false);                           // 掉的是副本里剩下的那部分
int removed = steve.getInventory().removeItem(item, amount);       // 扣的就是说好的数量
given += removed;                                                  // 记账用实际扣掉的
```

这样"想要几个"与"扣掉几个"、以及"给出去几个"三者永远一致；`given` 也终于会增长，
所以"给够了就停"能正常生效。

## 110. 同类缺陷：`/as take` 会把东西给玩家两遍

**文件**：`command/AsCommands.java`

同一个特性造成的另一个 bug，而且更明显：

```java
if (!player.getInventory().add(stack.copy())) {
    player.drop(stack.copy(), false);        // ← 又是一个全新的整堆
}
```

`add` 返回 false 时，它已经把"放得下的那部分"塞进玩家背包、并把传入的堆消耗成"剩余量"；
这里却又掉了一个**完整的整堆**。结果：玩家背包里拿到 `n - 剩余`，脚下再拿到 `n`，
而 AI 那边只扣了 `n` —— **凭空多出 `n - 剩余` 个**。

**修复**：掉的就是那个已经被消耗过的堆。

```java
ItemStack toTake = stack.copy();
if (!player.getInventory().add(toTake)) {
    player.drop(toTake, false);   // toTake 此时只剩没放下的部分
}
```

## 111. 复盘：上一阶段我为什么判错（以及为什么保留错误记录）

第 105 条写着"核查后**排除**了 `GiveItemAction`"，结论作废。错在**问题问偏了**：

- 我问的是"**它有没有复制一份物品？**" → 按字节码答"没有复制"（`add` 是消耗式的）→ 正确；
- 于是顺势得出"**总数守恒**" → **错误**。因为"消耗式"这个特性还有另一半后果：
  消耗之后再去读 `getCount()`，读到的已经不是交付量了。

**教训**：核查一个 API 的副作用时，要顺着"这个副作用会影响**所有**读它的地方"往下追一遍，
而不是只回答最初那个假设。只验一半，比不验更危险 —— 它会让人放心地去别处找 bug。
这次把原文保留、只标注作废，而不是删掉重写：错误结论和它的错因，对后来的人比一句干净的"已修复"更有用。

## 112. 本阶段验证

```
gradlew compileJava                     -> BUILD SUCCESSFUL
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
产物                                     -> build/libs/aisteve-1.0.0-all.jar
```

**为什么没有加单元测试**：这次的缺陷是"在调用一个会修改参数的 API 之后才读该参数"，
属于**调用顺序**问题，而不是我们自己的算术逻辑。要覆盖它必须跑真实的 `Inventory`/`ItemStack`，
即需要完整的 Minecraft 运行时 —— 现有的纯逻辑测试框架（第 95 条的 `TaskTest`）做不到，
硬凑一个抽象层的测试只会给人"已经被覆盖"的错觉。所以我改为把这个陷阱写进
`CLAUDE.md` 的 **1.20.1 gotchas** 一节（连同容器的活引用陷阱），让规范挡住它，而不是假装测住了。

**可以预期的行为（等你实测确认）**：
- "给我一个" → 聊天里回"给了玩家 1 个 …"，背包 4 → 3，不再有重复交付与"做不了"；
- 给的时候玩家背包满了 → 放不下的落在你脚边，AI 那边仍然按说好的数量扣；
- `/as take` → 拿回来的总数与它背包里减少的总数一致，不会凭空变多。

---

# 第十九阶段：隔山打牛（隔着土直接挖到煤）

> **玩家反馈原文**："现在ai玩家取东西时隔空取物的类似于隔山打牛。比如我让它做火把。
> 煤矿是藏到土里的。它直接跳过挖土，直接挖煤了。"
>
> 这次是**采掘完全没有"看得见才挖得到"这一层判断**：只要距离够近，方块就直接被摧毁，
> 中间的土/石根本不存在。

## 113. 【严重·真实性】`harvest` 只查距离，不查视线

**文件**：`action/actions/MineBlockAction.java`

原代码的判定只有两个数：

```java
if (horizontal > REACH || dy > VERTICAL_REACH) {   // 4.5 格水平 / 5 格垂直
    steve.getNavigation().moveTo(...);
    return;
}
harvest(next);        // ← 直接摧毁，没有"看得见吗"这一步
```

而 `findSomethingToCollect()` 扫的是**以 AI 为中心、半径 24 格、向下 12 格**的整块长方体，
`getBlockState(pos)` 直接读穿地形。于是：

```
煤埋在 AI 旁边 2 格深的土里
  → 扫描直接"看见"了这块煤（因为它读的是方块本身，不是视线）
  → 走过去，距离 ≤ 4.5，通过
  → harvest() 把煤挖掉，Block.getDrops 的掉落直接进背包
  → 土还在原地
```

**这就是"隔山打牛"** —— 从 AI 的视角看，它是"取"了一件东西，而不是"挖"了一件东西。
严格说这不只是观感问题：它绕过了原版"先挖开覆盖层"的物理规则，等价于隔空取物。

同一条缺失也存在于 `harvestAdjacentTargets()`（分支挖矿）：它把 ±2 范围内的目标方块一律收走，
**包括隔着岩壁、隧道还没挖到的那一块**。

## 114. 修复：射线检查 + 先挖开覆盖层

**判定"看得见"用的是原版射线检测**，不是自己写几何：

```java
private boolean canSee(BlockPos pos) {
    Vec3 eye = steve.getEyePosition(1.0F);
    BlockHitResult hit = steve.level().clip(new ClipContext(eye, Vec3.atCenterOf(pos),
        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, steve));
    return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
}
```

问的就是玩家用眼睛回答的那个问题：**我第一眼看到的是不是它**。流体用 `Fluid.NONE`
（水下挖矿本来就很正常，不该算"看不见"）。

**`tickSurface` 的流程变成"看得见才挖，看不见就先挖挡路的那块"**：

```java
if (!canSee(next)) {
    BlockPos cover = firstBlockInSight(next);      // 视线第一个撞上的方块
    if (cover != null && !cover.equals(next) && !cover.equals(stevePos)) {
        if (withinMiningReach(cover)) {
            harvest(cover);                        // 先挖开这层土/石（不计入产出）
        } else {
            // 够不着挡路的那块：先走到目标那一列，别站着干等
            steve.getNavigation().moveTo(next.getX() + 0.5, stevePos.getY(), next.getZ() + 0.5, 1.1);
        }
        return;
    }
    // 射线没命中任何方块（例如视线被自己身体挡住）→ 走常规流程
}
```

于是"煤在土下"会变成一连串**真实**的动作：**挖土 →（下一 tick）煤露出来了 → 挖煤 → 计入产出**。
符合真人顺序，也让日志第一次能讲清楚它到底在挖什么：

```text
Steve 'X' clearing dirt to get at coal_ore
Steve 'X' collected coal_ore at (123,64,-456) (1/8)
```

**计数规则也一并修正**：`harvest(...)` 现在只在被打掉的方块**就是目标方块**时才 `collectedCount++`。
开路挖掉的泥土/石头是"进度"但不是"产出" —— 否则"挖 8 个煤"会被一堆泥土凑数凑满。

**搜索也改了**（`findSomethingToCollect`）：

- 优先**看得见**的目标；
- 看不见但**在 8 格以内**的，作为"挖过去"的候选；
- 每个候选先做一次**极便宜的**"是否挨着空气"检查（`isAir` / `canOcclude`），
  只有通过的才做射线检测，并且单次搜索的射线次数有上限。
  于是 24 格半径的整块扫描依然是廉价的方块查询，不会因为加了视线检查而卡。

**兜底不变**：真的既挖不到也走不过去时，矿石仍然回落到**楼梯式下挖**（`startUnderground`），
非矿石则如实说"附近没有能挖到的 X（都被埋着的话得先挖过去）"，而不是硬挖。

## 115. 顺带说明：这些东西**故意没改**

- **挖方块仍然是瞬时的**（没有原版挖掘耗时）。这是既有的刻意简化，不是这次反馈的问题；
  改成逐 tick 计时会牵动所有动作的节奏，风险远大于收益。
- **`MineBlockAction` 在挖楼梯/分支时仍然用 `teleportTo` 在自挖的隧道里前进**。
  这与 `ARCHITECTURE.md` "位移只能走 `MovementController`" 的规则不一致，是一个**已知的历史遗留**，
  但它是"把自己挪进刚挖出来的那一格"，不是隔山打牛，所以这次没有一并动它 ——
  免得为了合规把最常用的挖矿功能改坏。记录在案，留给专门的一次改动。
- **树仍然会掉几片叶子**：被树叶挡住的树干，现在会先把叶子挖掉（叶子不计入木头产出）。
  相比"穿过树叶取木头"，破坏几片叶子是更接近真人、代价更小的选择。

## 116. 本阶段验证

```
gradlew clean compileJava               -> BUILD SUCCESSFUL（新 API 均非弃用：已用 javap -v 核对 clip / canOcclude）
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
node scripts/check-docs-lang.js         -> 7 份中文文档 + 3 份英文文档语言正确
产物                                     -> build/libs/aisteve-1.0.0-all.jar
```

**没加单元测试，理由同上一阶段**：射线检测需要真实的 `Level`/`BlockGetter`，
纯逻辑测试框架覆盖不了。这次的规则写进了 `CLAUDE.md` 的核心铁律
（"AI 只能影响它看得见、够得着的东西"），并同步了 `docs/STATUS.md` 与
`docs/USAGE.zh-CN.md` 的能力表。

**另外修正了一处文档结构问题**：上一阶段的第十七、第十八两节因为插入锚点相同而顺序颠倒，
已用一次性脚本物理换位（脚本执行后即删除，未留在仓库里）。

---

# 第二十阶段：让它"活着"（生命值、饱食度、以及被打倒而不是无敌）

> **玩家需求原文**："我希望生成的ai玩家是有生命值的，和真人玩家一样，有饱食度，需要进食。
> ai玩家有默认的属性，就是保护真人玩家…然后就是ai玩家应该能使用某些特殊方块，举个例子：船…"
>
> 四件事里，**"保护玩家"其实早就实现了**（`onLivingHurt` → `PROTECT` 目标）；
> 真正的缺口是另外两件：AI 是**完全无敌的**，所以保护是单向的；以及**没有真实饥饿**。
> 本阶段做掉这两件，并如实说明第三件（交通工具）还没做。

## 117. 【严重·真实性】"AI 完全无敌"—— 生存闭环有一半是装饰

`docs/STATUS.md` 一直把这条列为**最大的语义不一致**，本阶段修掉：

```java
// 旧代码
@Override public boolean hurt(DamageSource source, float amount) { return false; }
@Override public boolean isInvulnerableTo(DamageSource source) { return true; }
```

代价不只是"不会死"：`Needs.SAFETY`、`SURVIVE` 目标、`flee` 工具、"血量不足先撤"这些分支
**永远不可能被一次真实的攻击触发**（只能由"附近有敌对生物"间接触发，那是间接的）。
换句话说，代码里有一整块逻辑从来没被真正执行过。

**修复**：`hurt()` 恢复正常伤害流程，并在受伤时做两件事：

- 血量掉到 1/3 以下 → 发 `LOW_HEALTH` 事件（这是唤醒思考循环的信号）；
- 每次受伤 → 发新的 `AGENT_HURT` 事件进工作记忆（**故意不设 `wakesBrain`**：
  真正该唤醒的是 `LOW_HEALTH`，两个唤醒信号只会让同一件事花两次 token）。

建造/飞行期间的临时无敌（`setInvulnerableBuilding`）保留，并额外保证：
当 `[agent].invulnerable = true` 时，这个临时护盾**只会加不会撤**。

## 118. 【新功能】真实饱食度 —— 用原版 `FoodData`，不是自造的计数器

原先只有一个放在 `Needs` 里的 0~100 启发式数字，只会随时间上涨，`SelfObserver` 报告
`food = -1`（"不适用"）。它不会真的饿，更不会因为挨饿掉血。

**关键发现**：`FoodData` 在 1.20.1 里只是**挂在 `Player` 上**，但这个类本身是可以直接
`new` 的普通类。所以 `SteveEntity` 可以自己拥有一个 —— 这就拿到了**真实营养值**：
`eat(item, stack)` 会去读物品的 `FoodProperties`，所有原版/模组食物都自动正确。

唯一不能复用的是 `FoodData#tick(Player, boolean)`（它需要 `Player` 来查创造模式），
于是 `tickHunger()` 用它的公开 API 把同样的规则重述一遍：

| 规则 | 实现 |
| --- | --- |
| 走路消耗 | 寻路中每 tick 累加 exhaustion（原版每米 0.1；自己走路按一半计） |
| 饱食回血 | `food >= 18` 且血量不满 → 每 4 秒回 1 点 |
| 半饱回血 | `food >= 6` 且有饱和度 → 每 16 秒回 1 点 |
| 饿到 0 | 每 4 秒掉 1 点血（原版节律） |

饱食度写进实体的 NBT（`FoodData` 自带 `addAdditionalSaveData` / `readAdditionalSaveData`），
**重启后还在**。

**顺带修掉一个真 bug**：`UseItemAction.useOnSelf()` 原先**吃掉整叠**（`removeOneStack`）
并且**直接 `heal(nutrition)`** —— 8 个面包一口没了，而且完全绕开饱食度。
现在改成：取整叠 → 吃 **1** 个 → 剩下的放回背包；回血交给正常的饱食回血，不再凭空加血。

## 119. 【配置】三个新开关（默认值即"像真人一样"）

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `[agent].invulnerable` | `false` | `false` = 有真实生命值；`true` = 旧的永久无敌 |
| `[agent].hunger` | `true` | 是否有真实饱食度 |
| `[agent].respawnAfterDeath` | `true` | 见下条 |

**关于"死亡"的取舍（我做了一个保守的选择，请按需改）**：把一个会死的同伴加进别人的存档，
是有破坏性的 —— 它可能带着玩家刚给的东西消失。所以默认语义是**被打倒，而不是消失**：

```
致命一击 → 保留 1 点血、熄灭身上的火、清空药水效果，原地缓过来
          不治疗、不传送 —— 它就在原地剩一口气，等自己的生存行为把它带回你身边
```

`respawnAfterDeath = false` 才是真死：调 `SteveInventory#dropAll()` 让背包掉出来
（原版 `Mob#die` 根本不认识这个自定义背包，不写这一句玩家的东西会凭空消失），
发 `AGENT_DIED` 事件，然后真的死掉。

这样"有生命值"是成立的（会掉血、会被打倒、会饿死），但**不会永久失去 AI**。

## 120. 感知与能力边界同步

- `SelfObserver` 现在报告**真实**饱食度；`hunger = false` 时仍报 `-1`（诚实地说"没有这条"）。
- `Needs.HUNGER` 直接由真实饱食度推导，不再自己累加。
  `onAte()` 保留但只做"刚吃过"的即时重置 —— 因为下一个感知周期就会用真值覆盖它。
- `/as info` 增加一行**饱食度**（未启用时显示"未启用（[agent].hunger = false）"），
  这是玩家验证这套机制最直接的地方。

## 121. 本阶段验证

```
gradlew clean compileJava               -> BUILD SUCCESSFUL
gradlew build -x test fatJar            -> BUILD SUCCESSFUL
node scripts/check-lang.js              -> 4 份 JSON 合法，中英键位完全配对
node scripts/check-keys.js              -> 引用 219 键，缺失 0
node scripts/check-docs-lang.js         -> 7 份中文文档 + 3 份英文文档语言正确
产物                                     -> build/libs/aisteve-1.0.0-all.jar
```

新增语言键：`aisteve.event.knocked_down`、`aisteve.cmd.food_off`（中英各一条）。

**API 都按字节码核对过**，不是凭记忆：`FoodData` 的 `eat(Item, ItemStack)` 返回 **void**
（不是 boolean，我第一版就写错了）；`FoodData` 确有 `addAdditionalSaveData` /
`readAdditionalSaveData`；`ItemStack#isEdible()` 存在（比 `getFoodProperties() != null` 更准确）；
`DamageSource` 上取伤害名要用 `getMsgId()`，没有 `getType()`。

## 122. 已知边界与**未完成项**

**未完成（原需求四件里的第三件）**：**AI 还不会上下船/矿车**。这是独立的一块能力 ——
需要新增一个工具（上下载具）、处理骑乘时的寻路抢占、以及明确的触发方式（玩家说"上船"）。
它没做，所以没有写半个占位实现：任务列表里那条仍是 `pending`。

**已做但值得你注意的取舍**：
- **保命优先于护人**：AI 会受伤之后，血量低时 `Needs.SAFETY` 会压过 `PROTECT` ——
  它可能在你被打时选择先撤。这是有意的优先级（死人护不了人），但如果你希望它"任何情况下都硬顶"，
  需要单独加一个策略开关，目前没有。
- **被打倒会清空药水效果**：`removeAllEffects()`，和"倒下一次"的语义一致。
- **`AGENT_HURT` 不唤醒思考循环**：唤醒由 `LOW_HEALTH` 负责（见第 117 条的说明）。
  顺带记一笔：`AgentEvent#wakesBrain` 这个字段**在整个代码库里其实没有任何消费者**，
  是既有的装饰性设计。本阶段没有去动它（属于另一件事），但至少保证新加的事件不让它撒谎。

