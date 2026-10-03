# 方案文档（设计对话原始记录）

> **这是什么**：AiSteve 分层 Agent 架构的**原始设计讨论记录**，未做删改。
>
> **这不是实现文档。** 它描述的是一份"应该怎么做"的方案，
> 其中**部分已经落地，部分还没有**。
>
> - 落地后的**设计**（分层、数据流、时间尺度、权限模型）见 [ARCHITECTURE.md](../ARCHITECTURE.md)
> - **实际做到哪、还差什么**见 [STATUS.md](STATUS.md) —— 那才是事实来源
>
> 保留这份文档的原因是：它记录了**为什么这么设计**，包括被否掉的方案
> （例如"LLM 每 100ms 决定一次按键"为什么行不通）。
> 后续若要推进第四阶段（多 AI / AI 社会 / AI 经济），这份原始设想仍是最好的起点。

---

如果你的目标是：

> **AI 不是一个“会自动挖矿的机器人”，而是真正作为 Minecraft 世界中的一个玩家，与真人玩家一起生存、交流、探索、战斗、建造，并且表现出持续的人格和行为。**

那么我建议不要做成简单的：

```text
Minecraft → LLM → Tool → Minecraft
```

这个架构后期一定会变得很僵。

更合适的是做成一个**分层 Agent 架构**：

```text
                    ┌──────────────────────────┐
                    │       Minecraft 世界      │
                    │ 玩家 / 生物 / 方块 / 时间 │
                    └────────────┬─────────────┘
                                 │
                           Perception
                                 │
                    ┌────────────▼─────────────┐
                    │       AI Player           │
                    │                          │
                    │  感知 Perception           │
                    │  ├─ 当前视觉               │
                    │  ├─ 附近实体               │
                    │  ├─ 背包                   │
                    │  ├─ 状态                   │
                    │  └─ 世界知识               │
                    │                          │
                    │  Memory                   │
                    │  ├─ Working Memory        │
                    │  ├─ Episodic Memory       │
                    │  ├─ Semantic Memory       │
                    │  └─ Social Memory         │
                    │                          │
                    │  Brain                    │
                    │  ├─ Goal                   │
                    │  ├─ Planner                │
                    │  ├─ Reasoner               │
                    │  └─ Social                 │
                    │                          │
                    │  Skill                    │
                    │  ├─ 生存                   │
                    │  ├─ 战斗                   │
                    │  ├─ 建造                   │
                    │  ├─ 探索                   │
                    │  └─ 社交                   │
                    │                          │
                    │  Tool                     │
                    │  ├─ move                   │
                    │  ├─ mine                   │
                    │  ├─ craft                  │
                    │  ├─ attack                 │
                    │  ├─ place_block             │
                    │  └─ chat                   │
                    └────────────┬─────────────┘
                                 │
                          Action Executor
                                 │
                    ┌────────────▼─────────────┐
                    │      Minecraft Client     │
                    └──────────────────────────┘
```

这里真正重要的是：**LLM 只负责“大脑”，不要负责实时操作。**

---

# 一、先确定一个核心原则

我非常建议你的 AI 玩家采用：

> **LLM 决策 + 程序执行 + 游戏状态反馈**

而不是：

> LLM 每 100ms 决定一次“按 W / 按空格 / 鼠标左键”。

后者会非常昂贵，而且 AI 很容易变成“抽风机器人”。

真正的人类玩家其实也是类似的：

```text
看到僵尸
   ↓
意识到危险
   ↓
决定：打它
   ↓
进入战斗行为
   ↓
程序控制移动、转向、攻击
   ↓
观察结果
   ↓
决定继续打 / 逃跑 / 换武器
```

所以 AI 应该拥有两个时间尺度。

### 快速控制层

几十毫秒～几百毫秒：

```text
移动
跳跃
转向
攻击
躲避
跟随
拾取
路径移动
```

这些**不需要 LLM**。

### 慢速思考层

几秒～几十秒：

```text
我要去哪里？
我要做什么？
为什么要做？
我要不要帮助陶哥？
我要不要回家？
我要不要去挖钻石？
这个玩家可信吗？
今天要不要建一个房子？
```

这些交给 LLM。

这样才会像真人。

---

# 二、整体架构

我建议第一版直接设计成：

```text
┌────────────────────────────────────────────────────┐
│                 Minecraft AI Player                 │
│                                                    │
│ ┌──────────────┐        ┌──────────────────────┐ │
│ │ Minecraft Mod│◄──────►│    Agent Runtime     │ │
│ │              │        │                      │ │
│ │ World        │        │ Perception           │ │
│ │ Entity       │        │ State                │ │
│ │ Inventory    │        │ Memory               │ │
│ │ Player       │        │ Goal                 │ │
│ │ Physics      │        │ Planner              │ │
│ └──────┬───────┘        │ Skill                │ │
│        │                │ Tool                 │ │
│        │                │ LLM                  │ │
│        │                └──────────┬───────────┘ │
│        │                           │             │
│        └──────── Action Executor ◄─┘             │
│                                                    │
└────────────────────────────────────────────────────┘
                         │
                         ▼
              OpenAI / DeepSeek / Qwen
              / Claude / 本地模型
```

甚至可以进一步拆成：

```text
Minecraft Mod
      │
      │ Agent Protocol
      ▼
Agent Runtime
      │
 ┌────┼───────────────┐
 ▼    ▼               ▼
LLM  Memory         Planner
 │      │              │
 └──────┼──────────────┘
        │
      Skills
        │
      Tools
        │
 Action Executor
        │
 Minecraft
```

---

# 三、Minecraft Mod 层

如果你准备真正开发，我建议：

### Minecraft

例如：

```text
Minecraft 1.21.x
```

然后选择：

```text
Fabric
```

或者：

```text
NeoForge
```

**AI 架构不要和 Fabric/NeoForge 强耦合。**

也就是说：

```text
Minecraft Adapter
```

负责：

```java
getPlayerPosition();

getNearbyEntities();

getVisibleBlocks();

getInventory();

getHealth();

getFood();

getWorldTime();

move();

attack();

jump();

breakBlock();

placeBlock();

chat();
```

上层 Agent 根本不知道你用 Fabric 还是 NeoForge。

---

# 四、最重要的模块：Perception

AI 不应该直接读取整个 Minecraft 世界。

它应该有一个：

```text
Perception System
```

把 Minecraft 世界转换成 AI 能理解的信息。

例如真实世界：

```text
坐标：
X=123
Y=64
Z=-230

周围：

Steve
距离 4.2m

Zombie
距离 8.1m

Cow
距离 12m

Chest
距离 3m

Iron Ore
距离 5m
```

转换成：

```json
{
  "self": {
    "health": 18,
    "food": 16,
    "position": [123, 64, -230],
    "dimension": "overworld"
  },
  "nearby_players": [
    {
      "name": "Steve",
      "distance": 4.2
    }
  ],
  "hostile_entities": [
    {
      "type": "zombie",
      "distance": 8.1
    }
  ],
  "resources": [
    {
      "type": "iron_ore",
      "distance": 5
    }
  ]
}
```

然后再交给 LLM。

---

# 五、千万不要把所有世界信息都塞给 LLM

这是这个项目非常容易踩的坑。

假设：

```text
Minecraft 世界
100000 × 100000
```

你不能：

```text
世界所有方块 → LLM
```

应该做：

```text
World
  ↓
Local Observation
  ↓
Relevant State
  ↓
LLM
```

例如：

```text
当前视野 32 blocks
附近实体 16 blocks
当前任务相关目标
背包
自身状态
最近事件
```

这样 Token 消耗会低很多。

---

# 六、Memory 要独立出来

你之前研究过 Agent 的 Memory，这个项目特别适合使用。

我建议至少四层。

---

## 1. Working Memory

短期记忆。

例如：

```text
我现在正在和陶哥去找村庄。

陶哥刚才说想找铁。

我们已经走了 300 格。

前面发现了一条河。

附近有两个僵尸。
```

生命周期：

```text
几分钟
```

可以直接放：

```text
Redis
```

甚至内存即可。

---

# 七、Episodic Memory

事件记忆。

例如：

```text
2026-10-02

我和陶哥第一次进入下界。

陶哥掉进岩浆。

我使用方块搭桥救了他。

陶哥说“谢谢”。
```

以后遇到类似情况：

```text
危险区域
```

AI 会想起：

```text
上次陶哥差点掉岩浆。
```

这就开始有“人格感”了。

---

# 八、Semantic Memory

长期知识。

例如：

```text
钻石需要铁镐。

村庄通常有村民。

夜晚会生成敌对生物。

下界存在烈焰人。

末影龙位于末地。
```

这部分甚至可以：

```text
RAG
```

---

# 九、Social Memory

这是我认为**让 AI 真正像真人玩家的关键模块之一。**

专门记录玩家关系。

例如：

```json
{
  "player": "陶哥",
  "relationship": "friend",
  "trust": 0.92,
  "interactions": 127,
  "facts": [
    "喜欢建房子",
    "经常挖矿",
    "喜欢探索",
    "不喜欢晚上在野外乱跑"
  ]
}
```

然后 AI 遇到陶哥：

```text
陶哥：
“走，去找钻石。”
```

AI 不只是：

```text
收到命令
```

而是：

```text
陶哥最近一直在挖矿。

他现在装备还不错。

附近可能有洞穴。

我可以跟着他。
```

于是行为就自然很多。

---

# 十、Goal System

这是整个 Agent 的核心。

不要让 LLM 每次都回答：

```text
下一步干什么？
```

应该有：

```text
Goal Manager
```

例如：

```text
当前长期目标：

成为一个稳定的生存玩家
```

然后：

```text
长期目标
    ↓
阶段目标
    ↓
当前任务
    ↓
具体动作
```

例如：

```text
成为优秀生存玩家
        ↓
建立基地
        ↓
收集建筑材料
        ↓
获得木头
        ↓
寻找森林
        ↓
移动到森林
        ↓
砍树
```

---

# 十一、Goal 可以有优先级

例如：

```text
Goal Priority

生存       100
保护队友    90
完成任务    70
资源收集    50
探索        30
娱乐        20
```

但不要写死。

可以让 LLM 根据情况动态调整。

例如：

```text
平时：

探索 60
资源 50
社交 40

发现血量 20%：

生存 100
逃跑 95
探索 0
```

---

# 十二、Planner

Planner 负责把：

```text
Goal
```

拆成：

```text
Plan
```

例如：

```text
Goal:

建立一个基地
```

Planner：

```text
1. 找平坦区域
2. 收集木头
3. 收集石头
4. 制作工具
5. 建造房屋
6. 放置箱子
7. 放置床
8. 设置基地
```

然后每个步骤交给 Skill。

---

# 十三、Skill 和 Tool 要分开

这个地方正好对应你之前问过的 Agent：

> Skill 和 Tool 到底有什么区别？

在这个 Minecraft 项目里特别好理解。

---

## Tool

Tool 是原子能力。

比如：

```text
move_to
look_at
attack
jump
break_block
place_block
pickup_item
craft
equip
open_chest
send_chat
```

Tool：

> “我能做什么？”

例如：

```json
{
  "name": "move_to",
  "description": "移动到指定位置",
  "parameters": {
    "x": "number",
    "y": "number",
    "z": "number"
  }
}
```

---

# 十四、Skill

Skill 是复杂能力。

例如：

```text
MiningSkill
CombatSkill
BuildingSkill
ExplorationSkill
FarmingSkill
TradingSkill
SocialSkill
SurvivalSkill
```

例如：

```text
MiningSkill
```

里面可能调用：

```text
find_ore
move_to
dig
place_torch
avoid_lava
pickup_item
return_home
```

所以：

```text
Skill
   ↓
多个 Tool
```

而：

```text
Tool
```

应该尽可能简单。

---

# 十五、建议你的 Tool 分成六大类

### Movement

```text
move_to
follow_player
look_at
jump
sneak
stop
```

### Interaction

```text
break_block
place_block
open_container
pickup_item
use_item
interact_entity
```

### Inventory

```text
get_inventory
equip_item
drop_item
craft_item
smelt_item
```

### Combat

```text
attack_entity
block_attack
use_weapon
flee
```

### World

```text
scan_area
find_block
find_entity
get_path
get_time
get_weather
```

### Social

```text
send_chat
whisper
ask_player
remember_player
```

这样就非常清晰。

---

# 十六、真正关键：不要让 Tool 直接执行移动

例如 LLM：

```json
{
  "tool": "move_to",
  "x": 200,
  "y": 64,
  "z": -300
}
```

不能简单变成：

```java
player.setPos(200, 64, -300);
```

否则 AI 就会：

```text
瞬移
```

完全不像玩家。

应该：

```text
move_to
   ↓
Pathfinding
   ↓
Path
   ↓
Movement Controller
   ↓
W / A / S / D / Jump
   ↓
Minecraft Physics
```

也就是说：

> **AI 决定“我要去哪里”，而不是决定“每一帧怎么走”。**

这点非常重要。

---

# 十七、你甚至可以做一个 Action Executor

例如：

```text
LLM
 ↓
move_to(120,64,-30)
 ↓
Action Executor
 ↓
Pathfinding
 ↓
目标点
 ↓
每 tick：

计算方向
 ↓
W
 ↓
判断障碍
 ↓
Jump
 ↓
继续 W
 ↓
到达
```

LLM 完全不知道：

```text
第 127 tick 按了 W
第 128 tick 按了 W
```

这样性能会非常好。

---

# 十八、LLM Loop

真正的 AI Loop 可以设计成：

```text
┌──────────────────────┐
│    Observe World     │
└──────────┬───────────┘
           ↓
┌──────────────────────┐
│ Update WorkingMemory │
└──────────┬───────────┘
           ↓
┌──────────────────────┐
│ Check Current Goal   │
└──────────┬───────────┘
           ↓
      是否需要思考？
        /       \
      NO         YES
      │           │
      │           ▼
      │     Retrieve Memory
      │           │
      │           ▼
      │        LLM
      │           │
      │           ▼
      │       Plan / Tool
      │           │
      │           ▼
      └──── Action Executor
                  │
                  ▼
             Minecraft
                  │
                  ▼
              New State
                  │
                  └───────→ Observe
```

---

# 十九、但不要每一帧调用 LLM

例如：

```text
Minecraft tick = 20 TPS
```

你不能：

```text
20 次 / 秒调用 LLM
```

建议：

### Minecraft Tick

```text
20 TPS
```

### Perception

```text
5~10 Hz
```

### Behavior Controller

```text
10~20 Hz
```

### Agent Brain

```text
0.2~2 Hz
```

甚至可以：

```text
事件触发
```

比如：

```text
发现敌人
玩家说话
任务完成
死亡
进入新区域
发现钻石
队友受伤
```

才唤醒 LLM。

---

# 二十、做一个 Event Bus

这个架构会非常舒服。

```text
Minecraft
    │
    ▼
Event Bus
    │
    ├── PLAYER_CHAT
    ├── PLAYER_NEARBY
    ├── PLAYER_HURT
    ├── ENTITY_DETECTED
    ├── BLOCK_FOUND
    ├── BLOCK_BROKEN
    ├── ITEM_PICKED
    ├── LOW_HEALTH
    ├── GOAL_COMPLETED
    ├── PLAYER_DIED
    └── WORLD_CHANGED
```

例如：

```text
陶哥：

“AI，跟我去找钻石”
```

变成：

```text
PLAYER_CHAT
       ↓
Social System
       ↓
Goal Manager
       ↓
Goal:

跟随陶哥寻找钻石
```

而不是直接把这句话扔给一个万能 Prompt。

---

# 二十一、Social System

如果你希望：

> 真人玩家和 AI 玩家真的像一起玩游戏

这个模块必须重点做。

例如：

```text
陶哥：
“你跟着我。”

AI：
“好，我去。”

→ follow_player(陶哥)
```

走了一段：

```text
AI：

“前面好像有怪，我先看看。”
```

发现 Creeper：

```text
AI：

“等一下，前面有苦力怕。”
```

然后：

```text
CombatSkill
```

介入。

战斗结束：

```text
AI：

“解决了，继续走。”
```

这比：

```text
LLM：

调用 move_to
调用 attack
调用 move_to
调用 attack
```

自然很多。

---

# 二十二、人格系统

再往上加：

```text
Persona
```

例如：

```json
{
  "name": "陶小弟",
  "personality": {
    "curiosity": 0.8,
    "courage": 0.7,
    "humor": 0.6,
    "helpfulness": 0.9,
    "risk_aversion": 0.4
  },
  "speaking_style": "自然、简短、偶尔开玩笑"
}
```

这样不同 AI 玩家就会产生区别。

例如：

```text
AI A：

“走，进去看看。”

AI B：

“等等，这洞看着不太安全。”

AI C：

“钻石！卧槽！”
```

---

# 二十三、甚至可以给 AI 一个“欲望系统”

这是让 NPC 从“机器人”变成“玩家”的关键升级。

例如：

```text
Needs

Hunger        60
Safety        80
Exploration   30
Social        70
Achievement   50
Resources     40
Curiosity     90
```

然后产生：

```text
Need
 ↓
Desire
 ↓
Goal
 ↓
Plan
 ↓
Action
```

比如：

```text
饥饿 20
 ↓
想吃东西
 ↓
寻找食物
 ↓
找动物 / 找箱子
 ↓
获取食物
 ↓
烹饪
 ↓
吃
```

而不是每次都由 LLM 自己凭空决定。

---

# 二十四、这样就出现了“自主行为”

例如真人玩家没有给 AI 下任何命令。

晚上：

```text
AI：

时间不早了。
我的食物不多。
附近有怪。
陶哥在基地。

→ 回基地
```

到了基地：

```text
发现陶哥正在建房子
```

AI：

```text
观察
 ↓
判断陶哥缺木板
 ↓
主动砍树
 ↓
回来
 ↓
把木头放进箱子
```

这时候才开始有：

> **“这个 AI 好像真的在玩 Minecraft。”**

的感觉。

---

# 二十五、LLM 的 Prompt 不应该巨大

我建议采用：

```text
System Prompt
+
Persona
+
Current Goal
+
Relevant Memory
+
Current Observation
+
Recent Events
```

例如：

```text
你是 Minecraft 世界中的一个真实玩家。

你的名字是陶小弟。

你和玩家“陶哥”是长期合作伙伴。

你不能瞬移、不能凭空获得物品、不能知道视野之外的信息。

你的行为必须符合 Minecraft 游戏规则。

当前目标：
和陶哥寻找钻石。

当前状态：
生命：18
饥饿：16
位置：123,64,-230

附近：
陶哥：距离 4m
僵尸：距离 8m
铁矿：距离 5m

最近事件：
陶哥刚才说：“往洞穴里面走。”

相关记忆：
上次探索洞穴时遇到过苦力怕。

现在决定下一步行为。
```

然后 Tool Calling。

---

# 二十六、Tool Calling 最好采用结构化协议

比如：

```json
{
  "type": "tool_call",
  "tool": "follow_player",
  "arguments": {
    "player": "陶哥"
  },
  "reason": "跟随陶哥进入洞穴"
}
```

执行结果：

```json
{
  "success": true,
  "status": "following",
  "distance": 3.4
}
```

然后再回给 LLM：

```text
Tool Result:
已经开始跟随陶哥。
当前距离：3.4m
```

形成：

```text
Observe
 ↓
Think
 ↓
Tool Call
 ↓
Execute
 ↓
Observe Result
 ↓
Think
 ↓
...
```

这就是 Agent Loop。

---

# 二十七、我建议加入“反思层”

例如一个动作失败：

```text
AI：

挖钻石
 ↓
失败
 ↓
发现使用了石镐
```

不要只是：

```text
Tool failed
```

而是：

```text
Reflection

目标：
获得钻石

失败原因：
当前没有铁镐

下一步：
寻找铁

更新 Goal：
制作铁镐
```

于是：

```text
Planning
    ↓
Execution
    ↓
Evaluation
    ↓
Reflection
    ↓
Replanning
```

这就是比较完整的 Agent。

---

# 二十八、整个系统我建议最终做到这个程度

```text
                         Minecraft
                             │
                    ┌────────▼────────┐
                    │ Minecraft Adapter│
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   Perception    │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │    Event Bus    │
                    └────────┬────────┘
                             │
             ┌───────────────┼────────────────┐
             │               │                │
             ▼               ▼                ▼
        Goal Manager      Memory          Social System
             │               │                │
             └───────────────┼────────────────┘
                             │
                    ┌────────▼────────┐
                    │     Planner     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │      LLM        │
                    │                 │
                    │ Reasoning       │
                    │ Decision        │
                    │ Tool Calling    │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │     Skills      │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
          Movement        Combat         Building
              │              │              │
              └──────────────┼──────────────┘
                             │
                    ┌────────▼────────┐
                    │      Tools      │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ Action Executor │
                    └────────┬────────┘
                             │
                         Minecraft
```

---

# 二十九、技术实现上，我会这样拆项目

如果你准备自己开发，我建议不要一上来就做成十几个微服务。

第一版：

```text
minecraft-ai-player/
│
├── minecraft-mod/
│
├── agent-core/
│   ├── AgentRuntime
│   ├── AgentLoop
│   ├── GoalManager
│   ├── Planner
│   ├── Reflection
│   └── EventBus
│
├── perception/
│   ├── WorldObserver
│   ├── EntityObserver
│   ├── InventoryObserver
│   └── PlayerObserver
│
├── memory/
│   ├── WorkingMemory
│   ├── EpisodicMemory
│   ├── SemanticMemory
│   └── SocialMemory
│
├── skill/
│   ├── MiningSkill
│   ├── CombatSkill
│   ├── BuildingSkill
│   ├── ExplorationSkill
│   ├── SurvivalSkill
│   └── SocialSkill
│
├── tool/
│   ├── MovementTools
│   ├── CombatTools
│   ├── WorldTools
│   ├── InventoryTools
│   └── SocialTools
│
├── execution/
│   ├── ActionExecutor
│   ├── MovementController
│   ├── Pathfinding
│   └── CombatController
│
└── llm/
    ├── OpenAIClient
    ├── DeepSeekClient
    ├── QwenClient
    └── ToolCalling
```

---

# 三十、甚至可以让 Agent Runtime 独立于 Minecraft

这个我非常推荐。

变成：

```text
                    ┌─────────────────┐
                    │   Agent Core    │
                    │                 │
                    │ LLM             │
                    │ Memory          │
                    │ Goal            │
                    │ Planner         │
                    │ Skill           │
                    │ Tool            │
                    └────────┬────────┘
                             │
                       Agent Protocol
                             │
              ┌──────────────┴──────────────┐
              │                             │
       Minecraft Adapter              Minecraft Server
              │
       Fabric / NeoForge
```

以后你甚至可以：

```text
Minecraft Agent
Minecraft Bot
游戏 NPC
Discord Agent
网页 Agent
```

共用 Agent Core。

---

# 三十一、我尤其建议设计一个 Agent Protocol

例如：

```json
{
  "agent_id": "tao_ai_001",

  "observation": {
    "player": {},
    "entities": [],
    "blocks": [],
    "inventory": [],
    "world": {}
  },

  "goal": {
    "id": "goal_001",
    "description": "和陶哥寻找钻石",
    "priority": 80
  },

  "events": [],

  "memory": [],

  "available_tools": []
}
```

LLM 返回：

```json
{
  "thought": "...",
  "goal_update": null,

  "actions": [
    {
      "tool": "follow_player",
      "arguments": {
        "player": "陶哥"
      }
    }
  ]
}
```

这样以后换：

```text
DeepSeek
GPT
Qwen
Claude
本地 Qwen
Ollama
```

Agent Core 都不用改。

---

# 三十二、还有一个特别重要的设计：LLM 不应该拥有所有权限

例如：

```text
LLM
 ↓
Tool
 ↓
Permission
 ↓
Executor
```

Tool 可以定义：

```json
{
  "name": "place_block",
  "permission": "WORLD_WRITE",
  "risk": "LOW"
}
```

甚至：

```text
creative_mode
gamemode
kill_player
give_item
teleport
```

直接禁止。

这样：

> AI 的能力边界由 Minecraft Agent Runtime 决定，而不是由 Prompt 决定。

这一点非常重要。

---

# 三十三、最终 AI 玩家的“灵魂”

如果把整个系统压缩成一句话，我会设计成：

```text
                ┌───────────────┐
                │   Personality │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │    Memory     │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │     Needs     │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │     Goals     │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │     LLM       │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │    Skills     │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │     Tools     │
                └───────┬───────┘
                        │
                ┌───────▼───────┐
                │   Executor    │
                └───────┬───────┘
                        │
                   Minecraft
                        │
                        ▼
                    Perception
                        │
                        └──────→ Memory
```

这实际上就是一个完整的**游戏世界 Agent**。

---

## 三十四、我建议你的 MVP 不要一开始做太大

第一阶段只实现：

```text
AI 玩家出生
   ↓
认识真人玩家
   ↓
聊天
   ↓
跟随真人玩家
   ↓
自动移动
   ↓
识别敌人
   ↓
战斗
   ↓
捡东西
   ↓
吃东西
   ↓
低血逃跑
```

做到这里，你就已经会得到一个非常有意思的东西。

第二阶段：

```text
挖矿
砍树
制作
建造
探索
回家
```

第三阶段：

```text
长期记忆
玩家关系
人格
自主目标
社会行为
团队协作
```

第四阶段才考虑：

```text
多 AI 玩家
AI 社会
AI 村庄
AI 经济
AI 阵营
AI 长期世界
```

---

## 最关键的一点

我甚至会把这个项目定义成：

> **不是“Minecraft AI Bot”，而是“Minecraft Agent Runtime”。**

因为 Bot 的思维是：

```text
看到东西 → 执行动作
```

而你真正想做的是：

```text
感知世界
   ↓
理解发生了什么
   ↓
记住过去发生了什么
   ↓
产生自己的需求
   ↓
形成目标
   ↓
制定计划
   ↓
调用技能
   ↓
执行动作
   ↓
观察结果
   ↓
修正计划
   ↓
继续生活
```

这样一来，**真人玩家不再是 AI 的“控制器”，而是 AI 世界里的另一个玩家**。

而你之前研究的 **Skill / Tool / Memory / RAG / Agent Loop**，在这个项目里刚好可以全部串起来：**Tool 是手脚，Skill 是技能，Memory 是经历，Goal 是欲望，LLM 是思考中枢，Minecraft Mod 是身体。**

如果按这个方向继续做，我下一步会直接把它落到一个**可开发的技术设计**：包括 `Fabric + Java` 模组目录结构、`AgentRuntime` 核心类、Observation/Tool JSON 协议、LLM Tool Calling、Memory 数据库表，以及 **“AI 跟随真人 → 自动打怪 → 对话 → 自主挖矿”** 这一条完整链路的代码骨架。
