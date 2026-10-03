# AiSteve — 项目现状 / Project Status

> **本文档诚实描述"现在能做什么、不能做什么、哪里做得不够好"。**
> 它按可验证的事实编写，不按愿望编写。如果你发现某条与实测不符，请提 Issue 并附日志 —— 那说明文档需要修正。
>
> This document honestly describes **what works, what does not, and where the
> implementation falls short**. It is written from verifiable facts, not
> intentions. If something here disagrees with your testing, please open an
> issue with a log excerpt — the document is then wrong and should be fixed.
>
> 最后更新 / Last updated: 2026-10-03 · 版本 / Version: 1.0.0

---

## 目录 / Contents

- [一、已实现 / Implemented](#一已实现--implemented)
- [二、未实现 / Not implemented](#二未实现--not-implemented)
- [三、已知不足 / Known deficiencies](#三已知不足--known-deficiencies)
- [四、路线图 / Roadmap](#四路线图--roadmap)
- [五、验证状态 / Verification status](#五验证状态--verification-status)

---

## 一、已实现 / Implemented

### 1.1 分层 Agent 运行时 / Layered agent runtime

| 层 | 内容 | 状态 |
| --- | --- | --- |
| `protocol` | `Observation` / `ToolSpec` / `ToolCall` / `ToolResult` / `AgentDecision` / `Permission` / `RiskLevel` | ✅ |
| `perception` | `PerceptionService` + 自身/背包/实体/方块 四个 Observer，快频 2 Hz、慢频 3 s 缓存 | ✅ |
| `memory` | 工作/经历/语义/社交 四层记忆，后三层随实体写入 NBT | ✅ |
| `brain` | `GoalManager`（动态优先级）、`Needs`（七维需求）、`Persona`、`Planner`、`Reflection`、`SocialSystem` | ✅ |
| `skill` | 6 个技能：挖矿 / 战斗 / 建造 / 探索 / 生存 / 社交 | ✅ |
| `tool` | 六大类工具 + `ToolDispatcher` 权限闸门 | ✅ |
| `agent` | `AgentRuntime`（装配根）+ `AgentLoop`（observe → think → act → reflect） | ✅ |
| `llm` | `AgentPromptBuilder` / `AgentDecisionParser`（含宽容 JSON 修复） | ✅ |

**关键机制**

- ✅ **两个时间尺度**：动作层 20 TPS；感知 2 Hz；LLM 0.2–2 Hz 且事件驱动。
- ✅ **Skill 优先、LLM 兜底**：高频请求（挖铁、砍树、跟随、建房）走确定性计划，**不花 token**。
- ✅ **反射与有界重规划**：失败按 `ToolResult.status` 查表得出原因与下一步，同一目标最多重试 3 次。
- ✅ **权限闸门**：`ADMIN` 类能力（瞬移/刷物品/gamemode/击杀）**永不进入模型可见工具表**。
- ✅ **禁止瞬移**：`MovementController` 没有 `teleport` 方法，一切位移走 vanilla 寻路。
- ✅ **有界观察**：`Observation` 每类信息限流（玩家 5 / 敌对 6 / 背包 12 / 资源 8…），token 成本与世界的繁忙程度无关。

### 1.2 工具清单 / Tool catalogue（22 个）

| 类别 | 工具 |
| --- | --- |
| movement | `move_to` `follow_player` `look_at` `jump` `stop` |
| interaction | `break_block` `place_block` `open_container` `pickup_item` `use_item` `build` `explore` `fish` `farm` |
| inventory | `get_inventory` `craft_item` `drop_item` `equip_item` `give_item` |
| combat | `attack_entity` `use_weapon` `flee` |
| world | `scan_area` `find_block` `find_entity` `get_time` `get_weather` |
| social | `send_chat` `ask_player` `remember_player` |

### 1.3 动作清单 / Actions（16 个）

`mine` · `place` · `craft` · `use_item` · `attack` · `pickup` · `give` · `fish` ·
`farm` · `loot_container` · `explore` · `build` · `follow` · `say` · `pathfind` · `gather`

| 能力 | 实现程度 |
| --- | --- |
| 挖矿 | ✅ 地表就近采集；矿石可**楼梯式下挖到目标 Y 层再分支挖矿**；砍树自动砍整棵 |
| 合成 | ✅ 走**真实配方系统**（`RecipeManager`），正确支持标签材料；需要工作台时**自己造一个** |
| 战斗 | ✅ 可指定具体生物或 `hostile`；可指定数量 |
| 翻箱子 | ✅ 箱子/木桶/熔炉/潜影盒/漏斗 |
| 钓鱼 | ✅ 原版概率掉落（鱼/垃圾/宝藏） |
| 种田 | ✅ 收割成熟作物并**自动补种** |
| 建造 | ✅ 程序化生成（house/castle/tower/barn/modern/powerplant） |
| 探索 | ✅ 分段寻路，可带目标提前停下 |
| 物品 | ✅ 使用/给予/捡拾/丢弃，均**真实扣减背包**，不凭空造物 |

### 1.4 交互与人格 / Interaction & persona

- ✅ `/as` 指令族：`create` `remove` `cleanup` `info` `agent` `goals` `memory` `stop` `come` `say` `give` `take`
- ✅ **听得见普通聊天**（48 格内），不只是 `/as say`
- ✅ **进度播报**：开工说计划、完成报结果、失败讲原因，节流 30 tick
- ✅ **主动说话**：最多每 90 秒一句，按处境生成（附近有怪 / 天黑 / 背包空 / 有动物）
- ✅ **社交记忆**：信任度、互动次数、玩家偏好，跨重启保留
- ✅ **保护玩家**：玩家被打 → 立刻反击攻击者（不经过 LLM）
- ✅ **活动范围**：默认 48 格，超出自动回位
- ✅ **失败诚实**：缺东西就说缺，不假装完成

### 1.5 界面与配置 / GUI & config

- ✅ 设置界面（K 键）：大模型配置 / 权限与行为 / 能力开关，**均可滚动**
- ✅ 行为类设置**改完立即生效**，无需重启
- ✅ 多 provider：deepseek / openai / groq / gemini
- ✅ 容错：熔断、重试（指数退避）、限流、缓存、规则降级
- ✅ 中英文本地化（`zh_cn.json` / `en_us.json`）

---

## 二、未实现 / Not implemented

> 下面的内容**当前完全不存在**。有些会显著影响可玩性，已在"影响"列说明。

### 2.1 会卡住进度的功能缺口 / Progress-blocking gaps

| 未实现 | 影响 | 严重度 |
| --- | --- | --- |
| **熔炼（furnace smelting）** | **铁器时代无法到达。** 1.20.1 挖 `iron_ore` 掉 `raw_iron`，必须烧成 `iron_ingot` 才能做铁镐/铁剑/铁盔甲。当前没有 `smelt` 工具，AI 走到这一步会如实报"缺少材料"并停止 —— 也就是说它**永远造不出铁质工具**，后续的钻石采集（需要铁镐）也随之不可能。 | 🔴 高 |
| **烧炼木炭 / 制作火把链** | 木炭需要熔炉烧原木。因此下矿照明只能依赖**自然生成的煤炭**，找不到煤就没法插火把。 | 🟠 中 |
| **装备与盔甲** | `equip_item` 是**诚实的空操作**（动作层会自动选用背包里最合适的工具）。但盔甲/盾牌无法穿戴，AI 也没有装备槽。 | 🟠 中 |
| **睡觉 / 床** | 无法跳过夜晚。`Needs` 里的"疲劳"概念不存在。 | 🟡 低 |
| **村民交易** | 无法与村民换东西。 | 🟡 低 |

### 2.2 游戏内容覆盖 / Content coverage

| 未实现 | 说明 |
| --- | --- |
| 下界与末地流程 | 不会造传送门、不会打烈焰人、不会找要塞、不会打末影龙 |
| 附魔与药水 | 无附魔台、无酿造台相关能力 |
| 农业扩展 | 不能开垦新农田（只能收割已有作物），不能养殖动物（只能猎杀） |
| 红石 / 机械 | 无相关能力 |
| 结构模板建造 | 代码支持加载 `.nbt` 模板，但**仓库未附带任何模板**，因此 `build` 实际始终走程序化生成回退路径 |

### 2.3 智能体能力 / Agent capability

| 未实现 | 说明 |
| --- | --- |
| **多 AI 支持** | **硬性限制：同时只能存在 1 个 AI 玩家**。`SteveManager` 会拒绝创建第二个。方案文档里的"AI 社会 / AI 村庄 / AI 经济"全部未开始。 |
| **向量检索（真 RAG）** | `SemanticMemory` 用的是**词法打分**（拉丁词按整词、中日韩按字），不是 embedding。知识库小的时候够用，规模上去会退化。 |
| **死亡与重生** | 见"不足"第 1 条 —— AI 目前**完全无敌**。 |
| **代码执行工具** | `CodeExecutionEngine` / `SteveAPI` 是**死代码**，从未被实例化（GraalVM 刻意不打包）。其中 `SteveAPI.say()` 还留着 `TODO`。 |
| **跨会话对话记忆** | 重启后聊天上下文重置（背包与三类持久记忆保留）。 |

### 2.4 工程化 / Engineering

| 未实现 | 说明 |
| --- | --- |
| **单元测试** | `src/test/` 下 **4 个测试类全部是 `// TODO: Add test implementation` 空壳**。也就是说 `./gradlew test` 什么都不会验证。 |
| CI / CD | 无 GitHub Actions。`build` 与 `fatJar` 全靠本地手工跑。 |
| 自动发布 | 无 Release 流程，jar 手动上传。 |
| Fabric / NeoForge 支持 | **仅 Forge**。`ARCHITECTURE.md` 描述的适配层解耦尚未真正抽离。 |

---

## 三、已知不足 / Known deficiencies

> 与上一节不同：这些**功能存在**，但实现得不够好，或者会在某些情况下出问题。

### 1. 🔴 AI 完全无敌 —— "生存"闭环有一半是装饰

`SteveEntity` 里：

```java
@Override public boolean hurt(DamageSource source, float amount) { return false; }
@Override public boolean isInvulnerableTo(DamageSource source) { return true; }
```

**后果**：它不会掉血、不会死。于是：
- `Needs` 里的 `SAFETY`、`SURVIVE` 目标、`flee` 工具、"血量不足先撤"的分支，
  **永远不会被真实的受伤触发**（只能由"附近有敌对生物"间接触发）。
- 战斗是单向的 —— 苦力怕炸它、骷髅射它，都没有任何后果。
- 与"像真人一样玩"的目标直接冲突：真人会死。

**这是当前最大的一处语义不一致**，修它需要同时处理死亡、掉落、重生与记忆延续。

### 2. 🔴 铁器链条断裂（对应 2.1 第 1 条）

已在上文说明，单列是因为它对**可玩性**的影响最大：玩家让它"去挖钻石"，
它会挖到铁就停下，因为造不出铁镐。

### 3. 🟠 技能匹配靠关键词，比较脆

`SkillRegistry` 用 `SkillSupport` 里的中英文关键词表匹配。好处是零 token、零延迟；
坏处是：
- 没覆盖的说法会落到 LLM（可接受）；
- 但**语义相近的说法可能被误判**。例如 `SocialSkill` 曾经把任意"给我…"当成
  "把身上所有东西都交出去"，导致 AI 塞给玩家一堆无关物品。已通过收紧条件修复，
  但同类风险仍然存在于其他关键词分支。

### 4. 🟠 感知是节流的，可能滞后

慢频方块扫描每 3 秒一次。**"我周围有什么"可能比世界晚几秒**。
自身/背包/实体是 2 Hz，不受影响。

这也是 `Observation` 快照不能用于**派发阶段**预检的根本原因 ——
曾经因此产生
"我身上没有 X" 紧接着又真的把 X 交出去的矛盾现象（已在 CHANGELOG 第 59 条修复）。

### 5. 🟠 `equip_item` 是空操作

它明确返回 `unsupported`（"不需要手动装备"），因为动作层会自动选工具。
这是**刻意的诚实**，但如果玩家期望"把剑拿在手上"这类真实装备语义，会觉得缺失。

### 6. 🟡 语义记忆的检索质量有限

词法匹配在知识库只有十几条时表现正常，条目变多会出现无关命中。
真 RAG 需要 embedding，代价与收益要在规模上来之后重新评估。

### 7. 🟡 部分场景的完成判定偏保守

`ActionResult.partial(...)` 用于"能做的都做了、没有可再尝试的"（例如材料只够挖到 3/8 个铁）。
这类结局**不会触发重规划**，AI 会如实汇报数量。设计上是对的，但玩家若期望"必须凑够 N 个"，
会觉得它放弃得太早。

### 8. 🟡 依赖模型质量

规划质量直接取决于所选模型。小模型可能返回不符合协议的 JSON ——
`AgentDecisionParser` 做了宽容修复（markdown 围栏、缺逗号、顶层数组、旧动作名映射），
但**修复不了的会退回"我这边没处理成功"**。

### 9. 🟡 单 AI 限制带来的架构约束

`SteveManager` 强制单一实例，`CollaborativeBuildManager` 的多人协作逻辑因此
长期处于"只有 1 个参与者"的状态。多 AI 需要重新处理资源竞争与通信。

---

## 四、路线图 / Roadmap

按**投入产出比**排序的建议优先级。

### P0 — 修复语义不一致（让"能玩"变成"玩得顺"）

1. **加入熔炼能力**（`smelt_item` + 熔炉跟踪），打通铁器链条。
   这是单项收益最大的一件事。
2. **让 AI 可以受伤/死亡**（可配置），并处理掉落、重生、记忆延续。
   或者至少在文档与 UI 上明确"AI 无敌"是设计选择，而不是遗漏。
3. **补上单元测试**：从纯逻辑类开始成本最低 ——
   `AgentDecisionParser`（JSON 修复）、`Reflection`（状态码映射）、
   `Needs` / `GoalManager`（优先级推导）、`SemanticMemory`（检索排序）。

### P1 — 提升智能体表现

4. 探索/挖矿的**空间记忆**：记住"我在这附近挖到过铁"，减少重复搜索。
5. `SemanticMemory` 引入轻量 embedding 或更好的检索打分。
6. 技能匹配从纯关键词升级为**关键词 + 槽位解析**，降低误判率。
7. 装备语义补全（盔甲/盾牌/手持切换）。

### P2 — 扩展内容

8. 下界流程（传送门、烈焰粉）→ 末地流程。
9. 村民交易。
10. 开垦农田与动物养殖（从"只能猎杀"到"可持续生产"）。
11. 附带结构模板（`.nbt`），让 `build` 支持设计好的建筑。

### P3 — 工程化

12. GitHub Actions：`compileJava` + `build` + `fatJar` 冒烟。
13. 自动发布 Release 并附上 `-all` jar。
14. 抽取 `Minecraft Adapter`，让 Agent 核心真正与 Forge 解耦（为 Fabric/NeoForge 铺路）。

### P4 — 多智能体（方案文档的第四阶段）

15. 多 AI 注册、资源竞争、协作协议、AI 社会/经济。
    `AgentRuntime` 已经是"一个 AI 一个实例"的独立对象，是这块工作的良好起点。

---

## 五、验证状态 / Verification status

### 已通过 / Passing

```
./gradlew compileJava             → BUILD SUCCESSFUL
./gradlew build -x test fatJar    → BUILD SUCCESSFUL
```

产物 / Artifacts:

| 文件 | 用途 |
| --- | --- |
| `build/libs/aisteve-1.0.0-all.jar` | ✅ **装进 `mods/`**（已打包依赖） |
| `build/libs/aisteve-1.0.0.jar` | ⚠️ 仅模组自身 class，单独安装会在运行时报 `NoClassDefFoundError` |

> 编译仅剩 deprecation 提示（沿用既有 API），无错误。

### 未验证 / Not verified

| 项目 | 原因 |
| --- | --- |
| `./gradlew test` | 测试全是空壳，**没有实际断言** |
| 多人服务器环境 | 未在 dedicated server + 多玩家场景下长时间运行 |
| 长时间稳定性 | 未做数小时连续运行的观察 |
| 除 DeepSeek 外的 provider | 代码路径存在且有容错，但**未经本项目实测** |

### 复现构建 / Reproducing the build

见 **`docs/BUILD.zh-CN.md`**（含 JDK 版本要求、代理配置、产物区别说明）。

---

## 如何反馈 / How to report

请附上：

1. `logs/latest.log` 中相关的错误片段
2. 你实际输入的指令
3. 期望的行为 vs 实际发生的行为
4. Minecraft / Forge / 模组版本

先看 **`TROUBLESHOOTING.md`** —— 大部分"AI 没反应"都是 API Key 或网络问题。
