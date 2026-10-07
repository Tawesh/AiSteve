# AiSteve 分层 Agent 架构设计

[English](README.md) · [简体中文](README.zh-CN.md) · [现状](docs/STATUS.md)

> 本文件是 [docs/DESIGN-BRIEF.zh-CN.md](docs/DESIGN-BRIEF.zh-CN.md)（《方案文档》）在
> AiSteve 代码库中的落地设计。
> 目标：把 AiSteve 从 **"Minecraft → LLM → Action → Minecraft"** 的直线式脚本，
> 重构成 **"Minecraft Adapter → Perception → EventBus → Memory/Goal/Social → Planner → LLM
> → Skill → Tool → ActionExecutor → Minecraft"** 的分层 Agent Runtime。
>
> **本文档写的是"设计意图"；哪些已经做到、哪些还没做，以 [docs/STATUS.md](docs/STATUS.md) 为准。**
> 两者不一致时，STATUS 是事实，本文档是目标。

---

## 0. 核心原则（不可违背）

1. **LLM 只当大脑，不做实时操作。**
   LLM 决定"我要去哪里、我要做什么、为什么"，程序决定"第 N tick 按什么键"。
2. **两个时间尺度。**
   - 快速控制层（20~100ms，游戏内每 tick）：移动 / 转向 / 挖掘 / 攻击 / 拾取 —— 不含 LLM。
   - 慢速思考层（几秒 ~ 几十秒）：目标 / 计划 / 反思 / 社交判断 —— 交给 LLM。
3. **AI 说"我要去哪"，不说"每一帧怎么走"。**
   `move_to` **禁止** `setPos()` 瞬移，必须 `Pathfinding → MovementController → W/A/S/D/Jump → 物理`。
4. **不给 LLM 全世界。**
   只给"局部观察 + 相关状态 + 当前目标 + 相关记忆 + 最近事件"。
5. **Tool 是手脚，Skill 是技能，Memory 是经历，Goal 是欲望，LLM 是思考中枢，Mod 是身体。**
6. **能力边界由 Runtime 决定，而不是由 Prompt 决定。**
   每个 Tool 声明 `permission` / `risk`，`ToolDispatcher` 强制执行；`ADMIN` 类能力（瞬移、刷物品、
   gamemode、kill）永远不可被 LLM 调用。

---

## 1. 目标架构总览

```
                         Minecraft 世界
                              │
                   ┌──────────▼───────────┐
                   │  Minecraft Adapter    │  Fabric/Forge 无关的适配层
                   │  (SteveEntity 等)      │
                   └──────────┬───────────┘
                              │
                   ┌──────────▼───────────┐
                   │  Perception 感知层     │  快频(自/背包/实体) + 慢频(方块/容器)
                   │  → Observation        │
                   └──────────┬───────────┘
                              │
                   ┌──────────▼───────────┐
                   │     Event Bus         │  PLAYER_CHAT / LOW_HEALTH / ENTITY_DETECTED ...
                   └──────────┬───────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
  ┌───────────┐        ┌───────────┐        ┌─────────────┐
  │ GoalManager│        │  Memory   │        │ SocialSystem│
  │  + Needs   │        │ 4 层记忆  │        │  玩家关系   │
  └─────┬─────┘        └─────┬─────┘        └──────┬──────┘
        └─────────────────────┼─────────────────────┘
                              ▼
                     ┌────────────────┐
                     │    Planner     │  Goal → Plan（优先 Skill，必要时问 LLM）
                     └───────┬────────┘
                              ▼
                     ┌────────────────┐
                     │      LLM       │  AgentDecision(thought / goal_update / actions)
                     └───────┬────────┘
                              ▼
                     ┌────────────────┐
                     │    Skills      │  Mining / Combat / Building / Exploration / Survival / Social
                     └───────┬────────┘
                              ▼
                     ┌────────────────┐
                     │     Tools      │  六大类原子能力 + Permission Gate
                     └───────┬────────┘
                              ▼
                     ┌────────────────┐
                     │ ActionExecutor │  tick 级动作队列（20 TPS）
                     └───────┬────────┘
                              ▼
                         Minecraft
                              │
                              └────────→ Perception
```

---

## 2. 包结构（重构后）

```
com.steve.ai
│
├── agent/                 【新】Agent 运行时装配与主循环
│   ├── AgentRuntime        组装 perception + memory + brain + skill + tool + executor
│   └── AgentLoop           慢速思考循环（事件触发 + 节流），驱动 Observe→Think→Act→Reflect
│
├── protocol/              【新】Agent Protocol（Agent 与 LLM 之间的结构化契约）
│   ├── Observation         结构化观察（self / players / hostiles / animals / resources / inventory）
│   ├── ToolSpec            Tool 元数据：name / description / category / parameters / permission / risk
│   ├── ToolCall            {"tool":"...","arguments":{...},"reason":"..."}
│   ├── ToolResult          {"success":true,"status":"...","message":"...","data":{...}}
│   ├── AgentDecision       LLM 输出：intent / thought / goal_update / actions / reply
│   ├── Permission          MOVEMENT / WORLD_READ / WORLD_WRITE / INVENTORY / COMBAT / SOCIAL / ADMIN
│   └── RiskLevel           LOW / MEDIUM / HIGH / FORBIDDEN
│
├── perception/            【新】感知层
│   ├── PerceptionService   双频调度 + 缓存（快频 0.5s、慢频 3s）
│   ├── SelfObserver        自身：生命/饥饿/坐标/维度/群系/时间/天气
│   ├── InventoryObserver   背包
│   ├── EntityObserver      附近玩家 / 敌对生物 / 动物
│   └── WorldObserver       附近资源方块 / 容器 / 地标线索
│
├── memory/                【扩展】四层记忆
│   ├── WorkingMemory       【新】短期（分钟级，内存环）
│   ├── EpisodicMemory      【新】事件记忆（带重要度，NBT 持久）
│   ├── SemanticMemory      【新】长期知识（含关键词检索 ≈ RAG，NBT 持久）
│   ├── SocialMemory        【新】玩家关系（trust / interactions / facts，NBT 持久）
│   ├── MemoryManager       【新】统一门面 + NBT 编解码
│   ├── SteveMemory         保留：会话记忆（对话轮次 / 最近动作）
│   ├── WorldMemory         保留：地标记忆（村庄/水/森林…）
│   └── WorldKnowledge      保留：即时环境摘要（供旧 prompt 兼容）
│
├── brain/                 【新】大脑层
│   ├── Goal                单个目标（type / priority / status / source / params）
│   ├── GoalType            SURVIVE / PROTECT / TASK / RESOURCE / BUILD / EXPLORE / SOCIAL / IDLE
│   ├── GoalManager         目标栈：提交 / 完成 / 失败 / 按 Needs+Observation 动态调优先级
│   ├── Needs               欲望系统：HUNGER / SAFETY / SOCIAL / EXPLORATION / ACHIEVEMENT / RESOURCES / CURIOSITY
│   ├── Persona             人格系统：curiosity / courage / humor / helpfulness / risk_aversion + 说话风格
│   ├── Planner             Goal → Plan（先问 Skill，未命中再交给 LLM）
│   ├── Plan                计划（steps: List<ToolCall>、narrative、needsLlm）
│   ├── Reflection          失败反思：目标 / 失败原因 / 下一步 / 是否需要重规划
│   └── SocialSystem        解析 PLAYER_CHAT → SocialMemory + 可能的跟随/协作目标
│
├── skill/                 【新】技能层（复杂能力 = 多个 Tool 的组合）
│   ├── Skill / SkillRequest / SkillPlan / SkillContext / SkillRegistry
│   ├── MiningSkill         挖矿/砍树
│   ├── CombatSkill         战斗/清怪
│   ├── BuildingSkill       建造
│   ├── ExplorationSkill    探索/寻路找资源
│   ├── SurvivalSkill       吃/避险/回血
│   └── SocialSkill         跟随/给物品/回应玩家
│
├── tool/                  【新】工具层（原子能力）
│   ├── Tool / ToolContext / ToolRegistry / ToolDispatcher（Permission Gate）
│   ├── MovementTools       move_to / follow_player / look_at / jump / stop
│   ├── InteractionTools    break_block / place_block / open_container / pickup_item / use_item
│   ├── InventoryTools      get_inventory / equip_item / drop_item / craft_item
│   ├── CombatTools         attack_entity / use_weapon / flee
│   ├── WorldTools          scan_area / find_block / find_entity / get_time / get_weather
│   └── SocialTools         send_chat / ask_player / remember_player
│
├── execution/             【扩展】执行层
│   ├── ActionExecutor      保留（重构为 Tool 的执行后端 + 执行监听）
│   ├── MovementController  【新】路径 → 逐 tick 控制（禁止瞬移的唯一入口）
│   ├── AgentState(Machine) 保留
│   └── ActionContext / Interceptor* 保留
│
├── llm/                   【扩展】
│   ├── AgentPromptBuilder  【新】分层 Prompt（Persona + Goal + Memory + Observation + Events + Tools）
│   ├── AgentDecisionParser 【新】解析 AgentDecision（含宽松 JSON 修复）
│   ├── TaskPlanner/PromptBuilder/ResponseParser 保留（旧路径，兼容 + 兜底）
│   └── async/ resilience/ 保留
│
├── action/                保留（Action = Tool 的底层实现，不删除）
├── event/                 【扩展】AgentEvent / AgentEventType
├── context/               保留（WorldContext 供旧路径与 TaskDecomposer 使用）
├── menu/                  【新】容器界面（两侧共用菜单定义）
│   ├── SteveInventoryMenu       空手右击 AI 打开：36 格只读背包 + 玩家物品栏
│   └── SteveInventoryContainer  直读 SteveInventory 的只读 Container（槽位禁止取放）
└── command/ client/ config/ plugin/ structure/ util/ di/  保留
    └── client/gui/SteveInventoryScreen  只读背包窗口（程序化绘制，不引贴图）
```

---

## 3. 与旧架构的差距对照

| 维度 | 旧 AiSteve | 目标架构 |
| --- | --- | --- |
| LLM 角色 | 收到玩家指令 → 一次性产出 Action 列表 | 只负责 Goal / Plan / Reflection，事件驱动、节流调用 |
| 触发方式 | 只有玩家 `/as say` | EventBus：玩家说话 / 低血 / 发现敌人 / 目标完成… |
| 感知 | WorldContext + WorldKnowledge（每次规划全量扫） | PerceptionService 快慢双频 + Observation 协议 |
| 记忆 | 会话记忆 + 地标记忆（2 层） | Working / Episodic / Semantic / Social（4 层）+ MemoryManager |
| 目标 | `currentGoal: String` | GoalManager（类型 + 优先级 + 来源 + 动态调整） |
| 欲望 | 无 | Needs（饥饿/安全/社交/探索/成就/资源/好奇） |
| 人格 | 无 | Persona（性格权重 + 说话风格） |
| 规划 | 直接 LLM 出 Action | Planner：Skill 优先，LLM 兜底；Plan 显式化 |
| 反思 | 失败回灌 prompt（`maybeReplan`） | Reflection 层：原因分析 + 目标修正 + 有界重规划 |
| 能力抽象 | Action（16 个） | Tool（原子，六大类）+ Skill（组合） |
| 协议 | `{type,reasoning,plan,tasks[]}` | AgentDecision：`{intent,thought,goal_update,actions[],reply}` |
| 权限 | ActionCapabilities 白名单 | Permission + RiskLevel + ToolDispatcher 强制 |
| 社交 | 无 | SocialSystem + SocialMemory（关系/信任/互动） |

---

## 4. 关键数据流

### 4.1 玩家说话（社交通道）

```
玩家: "AI，跟我去找钻石"
   │
   ▼ AsCommands → AgentRuntime.submitInstruction()
EventBus: PLAYER_CHAT
   │
   ├─→ SocialSystem: SocialMemory.recordInteraction("玩家", "想找钻石", +trust)
   ├─→ WorkingMemory: remember("玩家刚才说：跟我去找钻石")
   └─→ AgentLoop.wake(PLAYER_CHAT)          ← 事件唤醒（不做每帧 LLM）
             │
             ▼ GoalManager.submit(Goal(RESOURCE,"和玩家寻找钻石",src=玩家))
             ▼ Planner.plan(goal, observation)
                   ├─ SkillRegistry 命中 MiningSkill → Plan (确定性，不花 token)
                   └ 未命中 → needsLlm=true
             ▼ (必要时) LLM → AgentDecision{thought, goal_update, actions:[ToolCall...]}
             ▼ ToolDispatcher.dispatch(ToolCall)   ← Permission/Risk 校验
             ▼ ActionExecutor.enqueue(Task)        ← 复用既有 16 个 Action
             ▼ Minecraft（tick 级执行）
```

### 4.2 自主行为（无玩家指令）

```
每 tick: AgentLoop.tick()
   ├─ 快频感知（0.5s）→ Observation
   ├─ Needs.updateFrom(observation)   → 饥饿 20 / 安全 30 …
   ├─ GoalManager.reevaluate(obs, needs)
   │     └ 血量 20% ⇒ SURVIVE 优先级 100，EXPLORE 归零
   ├─ 没有可执行 Goal 且 Needs.topDesire() 存在 ⇒ 生成 Goal
   └─ 执行中 Goal 的 Plan 跑完 ⇒ Reflection / 完成事件
```

### 4.3 失败反思

```
ToolCall "mine diamond_ore" → ToolResult{success=false, status="missing_tool", message="需要铁镐"}
   │
   ▼ Reflection.reflect(goal, call, result, obs)
        cause   = 缺少铁镐（当前只有石镐）
        下一步  = 先获得铁 → 制作铁镐
   │
   ▼ GoalManager.submit(Goal(RESOURCE,"制作铁镐", priority=70, parent=原目标))
   ▼ 继续循环（有界，最多 MAX_REPLAN 次）
```

---

## 5. 时间尺度

| 层 | 频率 | 说明 |
| --- | --- | --- |
| Minecraft tick | 20 TPS | 物理/渲染 |
| ActionExecutor | 20 TPS | 每个 Action 的 `tick()` |
| Perception（快频） | 2 Hz（每 10 tick） | self / inventory / entities |
| Perception（慢频） | 0.33 Hz（每 60 tick） | 方块 / 容器扫描（带缓存） |
| AgentLoop 判定 | 2 Hz | 是否该思考（纯本地，不花 token） |
| LLM 调用 | 0.2 ~ 2 Hz（事件驱动 + 最小间隔） | 只有需要决策时才调用 |
| Reflection | 事件驱动 | 只在动作失败/目标完成时 |

---

## 6. 迁移路线（分阶段，可增量）

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P0 | 设计文档 + 包骨架（protocol / perception / memory / brain / skill / tool / agent） | ✅ 本阶段 |
| P1 | Perception + Observation 取代规划期的全量 WorldKnowledge 扫描 | ✅ 本阶段 |
| P2 | Memory 四层 + MemoryManager（NBT 持久） | ✅ 本阶段 |
| P3 | Goal/Needs/Persona/Planner/Reflection/SocialSystem | ✅ 本阶段 |
| P4 | Skill 层 + Tool 层 + Permission Gate（Tool → 既有 Action） | ✅ 本阶段 |
| P5 | AgentLoop 事件驱动 LLM 决策（AgentDecision 协议） | ✅ 本阶段 |
| P6 | 主 Prompt 切换到 AgentPromptBuilder | ✅ 本阶段 |
| P7 | 旧路径（TaskPlanner/ResponseParser/WorldContext）保留为兜底，逐步下线 | 保留 |
| P8 | 多 AI / AI 社会 / AI 村庄（方案文档第四阶段） | 未开始 |

**向后兼容策略**

- 既有 16 个 Action **不删除**，成为 Tool 的执行后端 → 行为不回退。
- `/as say` 仍可用：现在同时驱动新 AgentLoop（Goal + Plan + 记忆）与旧的 TaskPlanner 兜底。
- `WorldContext` / `TaskDecomposer` 保留，供旧路径与前置条件校验使用。
- 新 `/as` 子指令（`/as goals`、`/as memory`、`/as persona`、`/as autonomy`）用于观测 Agent 内部状态。

---

## 7. 权利边界（Permission Gate）

| 能力 | Permission | Risk | 是否允许 LLM 调用 |
| --- | --- | --- | --- |
| move_to / follow / look_at / jump / stop | MOVEMENT | LOW | ✅ |
| break_block / place_block / use_item | WORLD_WRITE | LOW | ✅ |
| get_inventory / craft_item / drop_item | INVENTORY | LOW | ✅ |
| attack_entity / flee | COMBAT | MEDIUM | ✅ |
| scan_area / find_block / get_time | WORLD_READ | LOW | ✅ |
| send_chat / ask_player | SOCIAL | LOW | ✅ |
| teleport / give_item / gamemode / kill / set_block_noclip | ADMIN | FORBIDDEN | ❌ 永不注册进 LLM 可见 Tool 列表 |

> 说明：`/as come` 这类瞬移仍保留为**玩家指令**（人在回路），但**不作为 Tool 暴露给 LLM**，
> 避免模型学会"瞬移解决问题"。
