<div align="center">

# AiSteve

**An AI companion that actually plays Minecraft with you.**

Not a scripted bot. A layered agent that perceives, remembers, forms its own goals,
and only asks a language model when it genuinely needs to think.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.0+-E04E14?style=flat-square)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

**English** · [简体中文](README.zh-CN.md)

</div>

---

## What it is

AiSteve adds an AI **player** to your world. You talk to it in plain language and it works
out the rest — mining, chopping, crafting, fishing, farming, looting, building, fighting
and exploring. It follows you, remembers you, warns you about mobs, and comes to your
defence when something hits you.

It is a **Forge** mod for **Minecraft 1.20.1**. There is no GUI panel for commanding it —
everything goes through chat with the `/as` command.

> **One companion at a time.** `SteveManager` enforces a single instance.

### Why it is not just another "LLM → action" wrapper

Most implementations do this:

```
Minecraft → LLM → action → Minecraft
```

…which works until it doesn't, and then the AI behaves like a twitchy robot that costs
money on every step. AiSteve is layered instead:

```
Minecraft ─→ Perception ─→ Event Bus ─→ Memory / Needs / Goals / Social
                                             │
                                         Planner ──(skill first, LLM second)──→ LLM
                                             │
                                         Skills ─→ Tools ─→ Permission Gate
                                             │
                                       ActionExecutor (20 TPS) ─→ Minecraft
```

The rules that follow from that shape:

- **The LLM never drives per-tick input.** It decides *what to do next*; the action layer
  decides how to do it.
- **Two time scales.** Actions run at 20 TPS. Perception runs at 2 Hz. The model is
  consulted at most a couple of times per second, and only when a goal cannot be planned
  without it.
- **Skills before the LLM.** The twenty most common requests — mine iron, chop wood, follow
  me, build a house — are deterministic plans that cost **zero tokens**.
- **No teleporting.** `MovementController` has no `teleport` method at all. The AI says
  "go to 200,64,-300"; pathfinding and physics do the rest.
- **Bounded perception.** Each category in `Observation` is capped, so token cost is flat
  no matter how busy the world is.
- **Capability boundaries live in code.** Teleporting, spawning items, changing gamemode and
  killing players are never exposed to the model. `ToolDispatcher` enforces it.

Read the full design in **[ARCHITECTURE.md](ARCHITECTURE.md)**.

---

## Features

### It plays the game

| | |
| --- | --- |
| ⛏️ **Mining** | Walks to the nearest target, or digs a staircase down to the right Y level and branch-mines. Fells whole trees. |
| 🔨 **Crafting** | Uses the real recipe list, honours item tags, consumes materials, and **builds its own crafting table** when a 3×3 recipe needs one. |
| 🍖 **Food** | Loots village chests, fishes (vanilla drop probabilities), harvests and replants crops, or hunts animals. |
| 🏠 **Building** | Procedural houses, castles, towers, barns, modern builds. |
| 🗺️ **Exploring** | Paths in segments towards a target, and stops early when it spots what it was looking for. |
| ⚔️ **Combat** | Fights hostiles or a named animal, with a quantity. |
| 📦 **Items** | Uses, gives, picks up and drops items — all against its real inventory. It never conjures anything. |

### It behaves like someone who lives there

- **Four layers of memory** — working (the last few minutes), episodic (what actually
  happened), semantic (how Minecraft works), and social (what it knows about *you*: trust,
  interaction count, "喜欢建房子"). The last three survive restarts.
- **Needs** — hunger, safety, social, exploration, achievement, resources, curiosity. These
  drive behaviour when nobody has asked for anything.
- **Persona** — curiosity / courage / humour / helpfulness / risk-aversion, plus a speaking
  style, so two companions do not behave identically.
- **Goal stack with dynamic priorities** — drop to 35% health and `SURVIVE` jumps to 100
  while `EXPLORE` collapses. Nobody hard-codes that transition.
- **Reflection** — when a step fails it works out *why* ("需要铁镐") and tries a different
  approach, up to three times, then says honestly what it could not do.
- **It talks** — announces what it is about to do, reports when it is done, says what went
  wrong, and occasionally chats unprompted. Rate-limited so it never spams.
- **It stays with you** — bounded to a configurable radius (default 48 blocks). Drift
  outside and it works its way back rather than vanishing over the horizon.
- **It protects you** — when you get hit, it goes straight for whatever attacked you. That
  is a reflex, not a decision it spends thirty seconds on.
- **It hears you** — ordinary chat within 48 blocks reaches it, not just `/as say`.

### It speaks your language

Two things are handled two different ways, because Minecraft only allows one of them to be
automatic:

- **The interface follows each client.** Every menu, command reply and keybind is translated by
  Minecraft itself, so on the same server a Chinese client sees Chinese and an English client sees
  English — no configuration and no restart.
- **The AI follows whoever is talking to it.** Speak English and it answers in English; switch to
  Chinese and it switches with you. Idle chatter uses the configured default.

> This split is not laziness. **The server cannot know your client's language** — that setting
> never leaves the client. UI text is resolved on the client, so it adapts automatically; the AI's
> chat lines are assembled server-side, so they need their own track. Mirroring the player is also
> simply how a person behaves: you do not configure the language a teammate replies in.

Manual override: `/as lang zh_cn`, `/as lang en_us`, or the setting in the K menu.

### It costs what it should

High-frequency requests never touch a paid API:

```
"去挖点铁"        → MiningSkill        → break_block        （确定性，0 token）
"跟着我"          → SocialSkill        → follow_player      （确定性，0 token）
"建个房子"        → BuildingSkill      → build              （确定性，0 token）
"用这些做个陷阱"   → no skill matches  → LLM                （真正开放的任务才花钱）
```

---

## Requirements

| | |
| --- | --- |
| Minecraft | **1.20.1** |
| Forge | **47.2.0+** |
| Java | **17** (exactly — JDK 21+ breaks the build) |
| RAM | ≥ 3 GB free for the first build |
| Network | access to your LLM provider at runtime |

> **Fabric and NeoForge are not supported.** Forge only.

---

## Install

### 1. Get the jar

Download `aisteve-1.0.0-all.jar` from
[Releases](https://github.com/Tawesh/AiSteve/releases), or build it yourself
(see [Building](#building)).

> ⚠️ **Use the `-all` jar.** The plain `aisteve-1.0.0.jar` contains only the mod's own
> classes; installing it alone crashes with `NoClassDefFoundError` the moment the AI tries
> to plan anything.

### 2. Drop it in `mods/`

```
.minecraft/mods/aisteve-1.0.0-all.jar
```

### 3. Launch once, then configure

The first launch generates `config/aisteve-common.toml`. Fill in your API key and
**restart**:

```toml
[ai]
provider = "deepseek"

[deepseek]
apiKey  = "sk-your-key-here"
model   = "deepseek-chat"
baseUrl = "https://api.deepseek.com"
```

> The config lives in **`config/`**, not in `mods/`. A template is included at
> `config/aisteve-common.toml.example`.

### 4. Create your companion

```
/as create Bob
```

---

## Usage

Talk to it in ordinary language — you do not need to memorise syntax.

```
/as 帮我弄一个羊排
/as 去挖点铁
/as 在我前面建个房子
/as 跟着我
```

### Commands

| Command | What it does |
| --- | --- |
| `/as create <name>` | Create the AI companion |
| `/as remove` | Remove it (drops whatever it was carrying) |
| `/as cleanup` | Force-clear every AI entity in the world — use after upgrading |
| `/as info` | Position, health, inventory, current goal |
| `/as agent` | Agent state: persona, needs, memory, loop phase |
| `/as goals` | The goal stack (type, priority, source) |
| `/as memory` | What it remembers about players and the past |
| `/as lang [zh_cn\|en_us]` | Show or set which language the AI speaks |
| `/as stop` | Stop the current task immediately |
| `/as come` | Call it to your side (if it got lost) |
| `/as say <text>` | Give it a task, or just chat |
| `/as <text>` | Shorthand for the above |
| `/as give` | Hand over whatever you are holding |
| `/as give <item> [count]` | Give a specific item |
| `/as take` | Take everything it is carrying |

**`/as give` is the main way to equip it.** Whatever is in your hand goes to it — a flint
and steel, a fishing rod, building materials.

### In-game settings (K key)

| Page | Contents |
| --- | --- |
| 大模型配置 | Provider, API key, model, tokens, temperature |
| AI 权限与行为 | Agent on/off, autonomy, roam radius, chat, narration, PvP defence |
| AI 能力开关 | Per-capability toggles (mining, building, crafting, combat, …) |

All pages scroll, and the behaviour settings take effect **without restarting**.

---

## Configuration

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
actionTickDelay    = 20
enableChatResponses = true

[agent]
enabled               = true   # layered Agent Runtime vs legacy one-shot planner
autonomy              = true   # pursue its own needs when idle
roamRadius            = 48     # how far it may stray before heading back (blocks)
idleChat              = true   # speak up on its own
progressNarration     = true   # announce plans and report results
defendAgainstPlayers  = true   # fight back when another player attacks you
language              = "zh_cn" # AI speech language (default; follows the speaker after that)
```

Provider/API settings need a restart. `[agent]` and `[behavior]` settings can be changed
in-game and apply immediately.

---

## Status: what works, what doesn't

**AiSteve is functional and genuinely playable, but it is not finished.** The honest
breakdown lives in **[docs/STATUS.md](docs/STATUS.md)**. The headline items:

**Works:** layered agent runtime · 22 tools · 16 actions · four-layer memory · 6 skills ·
goal stack with dynamic priorities · reflection · progress narration · player protection ·
roam limit · scrollable settings GUI · multi-provider LLM with circuit breaker/retry/cache.

**Does not work yet:**

- 🔴 **No furnace smelting → the iron tier is unreachable.** Iron ore drops raw iron in
  1.20.1, and raw iron must be smelted into ingots. With no smelt tool the AI gets as far
  as iron and stops. Diamonds are consequently out of reach too (they need an iron pickaxe).
- 🔴 **The AI is currently invulnerable** (`hurt()` returns `false`). It cannot take damage
  or die, so the survival half of the agent — `SAFETY`, `SURVIVE`, `flee` — is never
  triggered by real injury. This is the biggest semantic gap between "companion" and "real
  player".
- 🟠 **No unit tests.** All four test classes in `src/test/` are `// TODO` placeholders.
- 🟠 **Skill matching is keyword-based**, which is fast and free but occasionally
  misjudges phrasing.
- 🟠 **Block perception is throttled** to ~3 s, so "what is around me" can lag the world.
- 🟡 **Single AI only.** Multi-agent society is not started.
- 🟡 **Semantic memory retrieval is lexical**, not embedding-based.
- 🟡 **`equip_item` is an honest no-op** (the action layer picks tools automatically);
  armour and shields cannot be worn.
- 🟡 **No structure templates ship with the repo**, so `build` always uses the procedural
  generator.
- 🟡 **The AI speaks one language at a time** — it mirrors whoever spoke to it last, so on a
  bilingual server the language can flip between speakers. Per-player conversation state would
  be needed to fix that properly.
- 🟡 **The prompt's instructions and tool descriptions are written in Chinese.** They are internal
  text the player never sees; the *output* language is controlled by an explicit directive plus
  localised examples, which every major model handles.

---

## Building

```bash
./gradlew compileJava             # quick type/syntax check
./gradlew build -x test fatJar    # produce the installable jar
./gradlew runClient               # dev client with the mod loaded
```

On Windows use `gradlew.bat`.

| Output | Use |
| --- | --- |
| `build/libs/aisteve-1.0.0-all.jar` | ✅ **install this** (dependencies bundled) |
| `build/libs/aisteve-1.0.0.jar` | ⚠️ mod classes only — crashes if installed alone |

The first build downloads Minecraft, Forge and the MCP mappings and takes several minutes.

Detailed guidance (JDK setup, proxy configuration, troubleshooting the build):
**[docs/BUILD.zh-CN.md](docs/BUILD.zh-CN.md)** *(Chinese)*.

---

## Project layout

```
src/main/java/com/steve/ai/
├── protocol/     Agent Protocol: Observation / ToolSpec / ToolCall / ToolResult / AgentDecision
├── perception/   PerceptionService + Self/Inventory/Entity/WorldObserver
├── memory/       Working / Episodic / Semantic / Social + MemoryManager
├── brain/        Goal, GoalManager, Needs, Persona, Planner, Plan, Reflection, SocialSystem
├── skill/        Skill + SkillRegistry and the built-in skills
├── tool/         Tool + ToolRegistry + ToolDispatcher (permission gate) + tool groups
├── agent/        AgentRuntime (wiring root) and AgentLoop (observe → think → act → reflect)
├── action/       The 16 low-level actions that touch the world
├── llm/          Provider clients, prompts, response parsing
├── event/        Forge event glue + the agent event bus
├── entity/       SteveEntity, inventory, manager
├── structure/    Procedural generation and template loading
└── ...
```

## Documentation

| Document | Contents |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Full design: layers, data flows, timing, permission model, migration path |
| [docs/STATUS.md](docs/STATUS.md) | Implemented / not implemented / known deficiencies / roadmap |
| [CHANGELOG.md](CHANGELOG.md) | Every change, with the *reason* behind each bug fix |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | "The AI does nothing" and other common problems *(Chinese)* |
| [docs/USAGE.zh-CN.md](docs/USAGE.zh-CN.md) | Full usage guide *(Chinese)* |
| [docs/BUILD.zh-CN.md](docs/BUILD.zh-CN.md) | Build and local development guide *(Chinese)* |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development setup, architecture rules, how to extend |
| [CLAUDE.md](CLAUDE.md) | Orientation notes for AI coding assistants |

---

## Upgrading

Internals changed (`steve` → `aisteve`, `/tai` → `/as`) in a way that is **not** an
in-place upgrade:

1. Run `scripts/migrate-to-aisteve.ps1` — copies your API key to the new config path.
2. Delete the old jar from `mods/` and add the new one.
3. In game, run **`/as cleanup`** (clears entities created under the old id), then
   **`/as create <name>`**.

---

## Troubleshooting

The AI does nothing? In order of likelihood:

1. **No API key.** Check `config/aisteve-common.toml`. The log says so explicitly:
   `No API key configured for provider 'X'`.
2. **Config not reloaded.** Provider settings need a restart.
3. **Wrong jar.** You need the `-all` one.
4. **Fabric.** This is a Forge mod; it will silently not load.

Full guide: **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** *(Chinese)*.

---

## Contributing

Contributions are welcome — especially **new tools and skills**, which is where the biggest
gains are. Start with [CONTRIBUTING.md](CONTRIBUTING.md); it covers the development setup,
the architecture rules that must not be broken, and step-by-step guides for extending each
layer.

Please read the architecture rules before touching `brain/`, `skill/`, `tool/` or `agent/`.
Each one exists because breaking it produced a real bug.

---

## Credits

- Upstream project: [YuvDwi/Steve](https://github.com/YuvDwi/Steve)
- Minecraft Forge for the modding framework
- DeepSeek / OpenAI / Groq / Google for the LLM APIs

## License

[MIT](LICENSE)
