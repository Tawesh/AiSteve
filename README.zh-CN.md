<div align="center">

# AiSteve

**一个真正在陪你玩《我的世界》的 AI 玩家。**

不是脚本机器人。它是一个分层的智能体：会感知、会记忆、会自己产生目标，
只在真正需要思考的时候才去问大模型。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.0+-E04E14?style=flat-square)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

[English](README.md) · **简体中文**

</div>

---

## 这是什么

AiSteve 往你的世界里加入一个 **AI 玩家**。你用日常说话的方式交代事情，剩下的它自己想 ——
挖矿、砍树、合成、钓鱼、种田、翻箱子、建造、战斗、探索。它会跟着你、记得你、
提醒你附近有怪，你被打的时候它会冲上去。

这是一个 **Minecraft 1.20.1 的 Forge 模组**。**没有指令面板**，所有交互走聊天框里的 `/as`。

> **同时只能存在 1 个 AI 玩家**，由 `SteveManager` 强制。

### 为什么它不是又一个"LLM → 动作"的外壳

大多数实现长这样：

```
Minecraft → LLM → 动作 → Minecraft
```

这种结构一开始能跑，但它有两个天花板：每一步都要花钱，而且 AI 会变成"抽风机器人"。
AiSteve 是分层的：

```
Minecraft ─→ 感知 ─→ 事件总线 ─→ 记忆 / 需求 / 目标 / 社交
                                      │
                                   规划器 ──(技能优先，LLM 其次)──→ LLM
                                      │
                                   技能 ─→ 工具 ─→ 权限闸门
                                      │
                                 动作执行器 (20 TPS) ─→ Minecraft
```

由此得出的几条硬规则：

- **LLM 不负责逐帧操作。** 它只决定"下一步做什么"，怎么做由动作层决定。
- **两个时间尺度。** 动作 20 TPS，感知 2 Hz，大模型每秒最多问一两次，
  而且只在"这个目标没法靠程序规划"的时候才问。
- **技能优先于大模型。** 最高频的二十个请求 —— 挖铁、砍树、跟着我、建房子 ——
  都是确定性计划，**消耗 0 token**。
- **不瞬移。** `MovementController` 里**根本没有** `teleport` 方法。
  它说"我要去 200,64,-300"，然后由寻路和物理走过去。
- **观察是有界的。** `Observation` 每一类信息都限流，所以世界再热闹，token 成本也是平的。
- **能力边界写在代码里，不写在提示词里。** 瞬移、凭空造物、改游戏模式、击杀玩家
  永远不会出现在模型能看到的工具表里，由 `ToolDispatcher` 强制执行。

完整设计见 **[ARCHITECTURE.md](ARCHITECTURE.md)**。

---

## 功能

### 它会玩这个游戏

| | |
| --- | --- |
| ⛏️ **挖矿** | 走到最近的矿旁边挖掉；附近没有就**楼梯式挖到目标 Y 层再分支挖矿**。砍树会把整棵树一起砍下。 |
| 🔨 **合成** | 走真实配方系统，正确支持标签材料，真扣材料；3×3 配方需要工作台时**自己造一个**。 |
| 🍖 **找吃的** | 翻村庄箱子、钓鱼（原版掉落概率）、收割并补种作物、猎杀动物。 |
| 🏠 **建造** | 程序化生成房子、城堡、塔、谷仓、现代建筑。 |
| 🗺️ **探索** | 分段寻路前进，途中发现目标会提前停下并报告。 |
| ⚔️ **战斗** | 打敌对生物或指定动物，可指定数量。 |
| 📦 **物品** | 使用、给出、捡起、丢弃 —— 全部对着**真实背包**操作，绝不凭空变出东西。 |

### 它像一个住在那儿的人

- **四层记忆** —— 工作记忆（刚才几分钟）、经历记忆（真发生过什么）、
  语义记忆（Minecraft 怎么运作）、社交记忆（**关于你**：信任度、互动次数、"喜欢建房子"）。
  后面三层随存档保留。
- **需求系统** —— 饥饿、安全、社交、探索、成就、资源、好奇。
  没人下指令的时候，是它们在驱动行为。
- **人格** —— 好奇 / 勇气 / 幽默 / 热心 / 怕危险，外加说话风格。
  两个 AI 玩家不会表现一样。
- **带动态优先级的目标栈** —— 血量掉到 35% 以下，`生存` 直接跳到 100，
  `探索` 归零。这个切换不是写死的，是从当前状态推出来的。
- **反思** —— 一步失败了，它会先想清楚**为什么**（"需要铁镐"），换一种做法再试，
  最多三次，然后如实说哪里做不了。
- **它会说话** —— 开工前说打算怎么做，做完汇报结果，出问题说明原因，
  闲下来偶尔还会主动搭两句。有节流，不会刷屏。
- **它不会跑丢** —— 活动范围可配置（默认 48 格）。超出范围会自己走回来，
  而不是消失在地平线上。
- **它会保护你** —— 你被打，它直接冲上去打那个攻击者。这是条件反射，
  不是需要思考三十秒的决策。
- **它听得见你** —— 48 格内的**普通聊天**也能收到，不只是 `/as say`。

### 它会说你的语言

两件事用两种方式处理 —— 因为 Minecraft 只允许其中一件做到"自动"：

- **界面跟随每个客户端**：所有菜单、指令反馈、按键名都由 Minecraft 自己翻译。
  同一个服务器上，中文客户端看到中文、英文客户端看到英文 —— 不用配置，也不用重启。
- **AI 跟随跟它说话的人**：你用英文问，它就用英文答；你换中文，它跟着换。
  没人说话时的闲聊用配置里的默认语言。

> 这个拆分不是偷懒。**服务端拿不到客户端语言** —— 那个设置永远不会离开客户端。
> 界面文本在客户端解析，所以能自动适配；而 AI 的聊天内容是服务端拼好后广播的，只能单独跟踪。
> 跟随说话人本身也更像真人：你不会去"配置"队友用什么语言回你。

手动指定：`/as lang zh_cn`、`/as lang en_us`，或在 K 键设置界面里切换。

### 它只在必要的地方花钱

高频请求完全不碰付费接口：

```
"去挖点铁"        → 挖矿技能    → break_block     （确定性，0 token）
"跟着我"          → 社交技能    → follow_player   （确定性，0 token）
"建个房子"        → 建造技能    → build           （确定性，0 token）
"用这些做个陷阱"   → 没有技能命中 → LLM            （真正开放的任务才花钱）
```

---

## 环境要求

| | |
| --- | --- |
| Minecraft | **1.20.1** |
| Forge | **47.2.0+** |
| Java | **17**（必须是 17，JDK 21+ 会导致构建失败） |
| 内存 | 首次构建建议留 ≥ 3 GB 可用空间 |
| 网络 | 运行时需能访问你选的 LLM 服务 |

> **不支持 Fabric 和 NeoForge**，只有 Forge。

---

## 安装

### 1. 拿到 jar

从 [Releases](https://github.com/Tawesh/AiSteve/releases) 下载 `aisteve-1.0.0-all.jar`，
或者自己构建（见[构建](#构建)）。

> ⚠️ **一定要用 `-all` 那个。** 普通的 `aisteve-1.0.0.jar` 只有模组自身的 class，
> 单独安装会在 AI 一开始规划任务时报 `NoClassDefFoundError` 崩溃。

### 2. 放进 `mods/`

```
.minecraft/mods/aisteve-1.0.0-all.jar
```

### 3. 先启动一次，再填配置

首次启动会生成 `config/aisteve-common.toml`。填上 API Key 然后**重启**：

```toml
[ai]
provider = "deepseek"

[deepseek]
apiKey  = "sk-你的密钥"
model   = "deepseek-chat"
baseUrl = "https://api.deepseek.com"
```

> 配置在 **`config/`** 目录，**不是 mods**。模板见 `config/aisteve-common.toml.example`。

### 4. 创建 AI 玩家

```
/as create Bob
```

---

## 使用

用日常说话的方式就行，不需要背语法。

```
/as 帮我弄一个羊排
/as 去挖点铁
/as 在我前面建个房子
/as 跟着我
```

### 指令一览

| 指令 | 作用 |
| --- | --- |
| `/as create <名字>` | 创建 AI 玩家 |
| `/as remove` | 移除它（手上的东西会掉在地上） |
| `/as cleanup` | 强制清除世界上所有 AI 实体 —— 升级模组后用 |
| `/as info` | 位置、生命、背包、当前目标 |
| `/as agent` | 智能体状态：人格、需求、记忆、循环阶段 |
| `/as goals` | 目标栈（类型、优先级、来源） |
| `/as memory` | 它记住的玩家关系与经历 |
| `/as lang [zh_cn\|en_us]` | 查看或设置 AI 说话的语言 |
| `/as stop` | 立即停止当前任务 |
| `/as come` | 把它叫到身边（万一走丢了） |
| `/as say <内容>` | 下达任务，或者闲聊 |
| `/as <内容>` | 同上，`say` 可以省略 |
| `/as give` | 把**你手上拿的**物品交给它 |
| `/as give <物品> [数量]` | 直接给指定物品 |
| `/as take` | 把它身上所有东西收回来 |

**`/as give` 是给它装备的主要方式。** 你手上拿着什么就给它什么 ——
打火石、鱼竿、建筑材料都靠这个。

### 游戏内设置（K 键）

| 页面 | 内容 |
| --- | --- |
| 大模型配置 | 服务商、API Key、模型、token 上限、温度 |
| AI 权限与行为 | 分层 Agent 开关、自主行为、活动半径、聊天、播报、PvP 反击 |
| AI 能力开关 | 逐项开关（挖矿、建造、合成、战斗……） |

所有页面**都可以滚动**，行为类设置**改完立即生效，不用重启游戏**。

---

## 配置

`config/aisteve-common.toml`

```toml
[ai]
provider = "deepseek"          # deepseek | openai | groq | gemini

[deepseek]
apiKey  = ""
model   = "deepseek-chat"
baseUrl = "https://api.deepseek.com"

[openai]
apiKey      = ""
model       = "gpt-4-turbo-preview"
maxTokens   = 8000
temperature = 0.7

[behavior]
actionTickDelay     = 20
enableChatResponses = true

[agent]
enabled               = true   # 分层 Agent（关掉退回旧的单次规划路径）
autonomy              = true   # 闲着时自己找事做
roamRadius            = 48     # 活动半径，超出就走回来（格）
idleChat              = true   # 主动说话
progressNarration     = true   # 播报计划与结果
defendAgainstPlayers  = true   # 别的玩家打你时反击
language              = "zh_cn" # AI 说话语言（没人说话时用它；之后跟随说话者）
```

服务商与 API 设置需要重启；`[agent]` 和 `[behavior]` 可以在游戏内改，立即生效。

---

## 现状：能做什么、不能做什么

**AiSteve 是可用的、确实能玩的，但它没有完工。** 完整且诚实的清单在
**[docs/STATUS.md](docs/STATUS.md)**，要点如下：

**已经能用：** 分层 Agent 运行时 · 22 个工具 · 16 个动作 · 四层记忆 · 6 个技能 ·
动态优先级目标栈 · 反思 · 进度播报 · 保护玩家 · 活动范围限制 · 可滚动设置界面 ·
多服务商 LLM（含熔断/重试/缓存）。

**还不能用：**

- 🔴 **没有熔炼 → 铁器时代到不了。** 1.20.1 挖铁矿掉的是粗铁，粗铁必须烧成铁锭
  才能做铁镐。没有熔炼能力，AI 走到铁就停住了。**钻石也随之不可能**（需要铁镐）。
- 🔴 **AI 目前是无敌的**（`hurt()` 恒返回 `false`）。它不会掉血、不会死，
  所以"生存"那一半 —— `安全` 需求、`生存` 目标、`flee` 工具 —— 永远不会被真实受伤触发。
  这是"陪玩伙伴"和"真人玩家"之间最大的一处语义差距。
- 🟠 **没有单元测试。** `src/test/` 下 4 个测试类**全是 `// TODO` 空壳**。
- 🟠 **技能匹配靠关键词**，快且免费，但偶尔会误判说法。
- 🟠 **方块感知是节流的**（约 3 秒），所以"我周围有什么"可能比世界晚几秒。
- 🟡 **只支持单个 AI**，多智能体社会还没开始。
- 🟡 **语义记忆是词法检索**，不是向量检索。
- 🟡 **`equip_item` 是刻意的空操作**（动作层会自动选工具）；盔甲和盾牌无法穿戴。
- 🟡 **仓库没有附带结构模板**，所以 `build` 始终走程序化生成。
- 🟡 **AI 同一时间只能说一种语言** —— 它跟随最后跟它说话的人，所以双语服务器上会来回切换。
  要彻底解决需要按玩家分别维护对话状态。
- 🟡 **提示词正文与工具说明是中文**。它们是玩家看不到的内部指令；**输出**语言由显式指令
  和本地化示例控制，主流模型都能正确处理。

---

## 构建

```bash
./gradlew compileJava             # 快速类型/语法检查
./gradlew build -x test fatJar    # 产出可安装的 jar
./gradlew runClient               # 带模组的开发客户端
```

Windows 上用 `gradlew.bat`。

| 产物 | 用途 |
| --- | --- |
| `build/libs/aisteve-1.0.0-all.jar` | ✅ **装这个**（依赖已打包） |
| `build/libs/aisteve-1.0.0.jar` | ⚠️ 只有模组 class，单独装会崩 |

首次构建要下载 Minecraft、Forge 和 MCP 映射，需要几分钟。

详细说明（JDK 配置、代理设置、构建排错）见 **[docs/BUILD.zh-CN.md](docs/BUILD.zh-CN.md)**。

---

## 项目结构

```
src/main/java/com/steve/ai/
├── protocol/     Agent 协议：Observation / ToolSpec / ToolCall / ToolResult / AgentDecision
├── perception/   感知：PerceptionService + 自身/背包/实体/方块 四个 Observer
├── memory/       记忆：工作 / 经历 / 语义 / 社交 四层 + MemoryManager
├── brain/        大脑：Goal、GoalManager、Needs、Persona、Planner、Plan、Reflection、SocialSystem
├── skill/        技能：Skill + SkillRegistry 与内置技能
├── tool/         工具：Tool + ToolRegistry + ToolDispatcher（权限闸门）+ 六类工具
├── agent/        运行时：AgentRuntime（装配根）+ AgentLoop（感知→思考→行动→反思）
├── action/       16 个真正操作世界的底层动作
├── llm/          服务商客户端、提示词、回复解析
├── event/        Forge 事件桥接 + 智能体事件总线
├── entity/       SteveEntity、背包、管理器
├── structure/    程序化生成与模板加载
└── ...
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 完整设计：分层、数据流、时间尺度、权限模型、迁移路线 |
| [docs/STATUS.md](docs/STATUS.md) | 已实现 / 未实现 / 已知不足 / 路线图 |
| [CHANGELOG.md](CHANGELOG.md) | 全部改动，每个 bug 都写了**根因** |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | "AI 没反应"等常见问题排查 |
| [docs/USAGE.zh-CN.md](docs/USAGE.zh-CN.md) | 完整使用说明 |
| [docs/BUILD.zh-CN.md](docs/BUILD.zh-CN.md) | 构建与本地开发指南 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 开发环境、架构铁律、如何扩展 |
| [CLAUDE.md](CLAUDE.md) | 给 AI 编程助手的定位说明 |

---

## 从旧版本升级

内部标识变过（`steve` → `aisteve`，`/tai` → `/as`），**不能原地升级**：

1. 运行 `scripts/migrate-to-aisteve.ps1` —— 把 API Key 搬到新配置路径。
2. 从 `mods/` 删掉旧 jar，放进新的。
3. 进游戏执行 **`/as cleanup`**（清掉旧 id 创建的实体），再 **`/as create <名字>`**。

---

## 排错

AI 完全没反应？按可能性排序：

1. **没填 API Key。** 检查 `config/aisteve-common.toml`。日志会明确写出来：
   `No API key configured for provider 'X'`。
2. **配置没重新加载。** 服务商设置需要重启游戏。
3. **装错 jar 了。** 要用 `-all` 那个。
4. **装到 Fabric 了。** 这是 Forge 模组，会静默不加载。

完整排查：**[TROUBLESHOOTING.md](TROUBLESHOOTING.md)**。

---

## 参与贡献

欢迎贡献，尤其是**新增工具和技能** —— 那里的收益最大。
请先读 [CONTRIBUTING.md](CONTRIBUTING.md)，里面有开发环境、
**不能破坏的架构铁律**、以及每一层的扩展步骤。

动 `brain/`、`skill/`、`tool/`、`agent/` 之前请务必读完架构规则 ——
每一条都是因为被破坏过、产生过真实 bug 才写下来的。

---

## 致谢

- 上游项目：[YuvDwi/Steve](https://github.com/YuvDwi/Steve)
- Minecraft Forge 提供的模组框架
- DeepSeek / OpenAI / Groq / Google 提供的 LLM 接口

## 许可

[MIT](LICENSE)
