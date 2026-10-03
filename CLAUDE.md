# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AiSteve is a Minecraft 1.20.1 Forge mod that integrates LLM-powered AI companions into the game. Players interact with a single AI entity through natural language commands. The AI understands context, plans multi-step tasks, and executes actions like mining, crafting, farming, fishing, building, and combat.

**Key constraints:**
- Exactly one AI companion can exist at a time (enforced by `SteveManager`)
- All interaction happens through the `/as` command (no GUI). New in the layered refactor:
  `/as agent`, `/as goals`, `/as memory` expose the agent's internal state.
- The AI also *hears* ordinary chat from players within 48 blocks (`ServerEventHandler.onServerChat`).
- The AI is stateful: inventory and world knowledge persist across sessions; conversation memory resets on restart

## Build Commands

**Prerequisites:** JDK 17 (exactly - not 21 or higher). Gradle 8.4 is bundled via wrapper.

```bash
# Compile only (fast check)
./gradlew compileJava

# Full build with installable JAR (required for distribution)
./gradlew build -x test fatJar

# Run in development (creates run/ directory for testing)
./gradlew runClient
```

**Critical:** Always use `fatJar` for distribution. The default `jar` task produces `aisteve-1.0.0.jar` which contains only mod classes and will crash at runtime with `NoClassDefFoundError` when the AI tries to plan tasks. The `fatJar` task produces `aisteve-1.0.0-all.jar` which bundles required dependencies (resilience4j, caffeine) and is the only version safe to install in `mods/`.

**Build outputs:**
- `build/libs/aisteve-1.0.0-all.jar` ← Install this one
- `build/libs/aisteve-1.0.0.jar` ← Incomplete, do not distribute

**Testing in dev:** `./gradlew runClient` uses `run/config/aisteve-common.toml` for config (auto-generated on first launch).

## Configuration

Located at `config/aisteve-common.toml` (production) or `run/config/aisteve-common.toml` (dev). Auto-generated on first game launch. Template: `config/aisteve-common.toml.example`.

**Structure:**
```toml
[ai]
provider = "deepseek"  # deepseek | openai | groq | gemini

[deepseek]
apiKey = "sk-..."
model = "deepseek-flash"  # Recommended: V4.1-Flash
baseUrl = "https://api.deepseek.com"

[openai]
apiKey = ""  # Used as fallback if deepseek.apiKey is empty
maxTokens = 8000  # Shared by all providers
temperature = 0.7  # Lower = more deterministic

[agent]
enabled  = true    # true = layered Agent Runtime (goals + skills); false = legacy one-shot planner
autonomy = true    # let the AI pursue its own needs instead of idling until ordered around
```

**Config reload:** Requires game restart. Changes at runtime are ignored.

## Architecture

**Package structure:**
```
src/main/java/com/steve/ai/
├── protocol/            # Agent Protocol: Observation / ToolSpec / ToolCall / AgentDecision
├── perception/          # PerceptionService + Self/Inventory/Entity/WorldObserver (dual-rate scan)
├── memory/              # Working / Episodic / Semantic / Social + MemoryManager (NBT-persisted)
├── brain/               # Goal, GoalManager, Needs, Persona, Planner, Plan, Reflection, SocialSystem
├── skill/               # Skill + SkillRegistry and the 6 built-in skills
├── tool/                # Tool + ToolRegistry + ToolDispatcher (permission gate) + 6 tool groups
├── agent/               # AgentRuntime (wiring root) and AgentLoop (observe→think→act→reflect)
├── action/              # Action execution system
│   ├── actions/         # Individual action implementations (mine, craft, build, etc.)
│   ├── ActionExecutor   # Orchestrates action queue and tick-based execution
│   └── Task             # Represents a single action with parameters
├── llm/                 # LLM integration layer
│   ├── TaskPlanner      # Routes requests to provider-specific clients
│   ├── PromptBuilder    # Constructs system/user prompts with world context
│   ├── ResponseParser   # Parses JSON action lists from LLM responses
│   ├── async/           # Async HTTP clients (non-blocking LLM calls)
│   └── resilience/      # Circuit breaker, retry, rate limiting, fallback
├── entity/              # Core AI entity
│   ├── SteveEntity      # The AI companion (PathfinderMob with inventory)
│   ├── SteveManager     # Singleton manager enforcing one-AI limit
│   └── SteveInventory   # Inventory wrapper with item management
├── memory/              # Persistence layer
│   ├── SteveMemory      # Conversation context (per-session)
│   ├── WorldMemory      # Discovered locations (villages, water, forests)
│   └── WorldKnowledge   # Builds context strings for prompts
├── command/             # Command registration
│   └── AsCommands       # /as command tree dispatcher
├── config/              # Forge config
│   └── SteveConfig      # Config spec and value accessors
├── plugin/              # Action registry (extensibility)
│   ├── ActionRegistry   # Factory for creating actions by name
│   └── PluginManager    # SPI-based plugin loader
└── structure/           # Building system
    ├── StructureGenerators      # Procedural generation (house, castle, etc.)
    └── StructureTemplateLoader  # Template-based structures
```

**Core execution flow:**
1. Player executes `/as <instruction>` → `AsCommands.sayToAi()`
2. `ActionExecutor.processCommand()` → `TaskPlanner.planTasksAsync()`
3. `TaskPlanner` selects provider (deepseek/openai/groq/gemini) → async HTTP call
4. `ResponseParser.parseAIResponse()` extracts JSON action list
5. Actions enqueued in `ActionExecutor.taskQueue`
6. `ActionExecutor.tick()` (called every game tick) executes current action
7. Action completes → next action in queue starts

**Key design patterns:**
- **Plugin architecture:** Actions are registered via SPI (`ActionRegistry`). Legacy switch fallback exists but plugins take precedence.
- **Resilience:** All LLM clients wrapped in `ResilientLLMClient` with circuit breaker, retry (exponential backoff), rate limiting, and rule-based fallback.
- **Async execution:** LLM calls use `CompletableFuture` to avoid blocking the game thread. Results polled in tick loop.
- **State machine:** `AgentStateMachine` tracks IDLE/BUSY/PLANNING states with event publishing.
- **Persistence:** `WorldMemory` stores discovered landmarks in NBT (survives restarts). Conversation memory is transient.

## Layered Agent Architecture

The mod was refactored from a one-shot "instruction → action list" translator into a layered
Agent Runtime. **See `ARCHITECTURE.md` for the full design**; this is the working summary.

**Core rules (do not break these):**
- The LLM only decides *what to do next*. It never decides per-tick input.
- Movement only ever goes through `MovementController` — **never `setPos`/teleport**. The one
  remaining teleport is the anti-lost recovery in `ActionExecutor`, reachable only from a human
  command or the stuck detector.
- The AI may only act on what perception actually observed. Never invent a value.
- Capability boundaries are enforced by `ToolDispatcher` (Permission + RiskLevel), not by prompt
  wording. `Permission.ADMIN` tools are never registered/visible to the model.
- Tools report honestly. `ToolResult.missingTool` / `unsupported` beats a silent no-op, because
  the brain treats "scheduled" as "in progress".

**New packages**

```
protocol/     Observation, ToolSpec, ToolCall, ToolResult, AgentDecision, Permission, RiskLevel
perception/   PerceptionService + Self/Inventory/Entity/WorldObserver
memory/       WorkingMemory, EpisodicMemory, SemanticMemory, SocialMemory, MemoryManager
brain/        Goal, GoalType, GoalManager, Needs, Persona, Planner, Plan, Reflection, SocialSystem
skill/        Skill, SkillRequest, SkillPlan, SkillContext, SkillRegistry + 6 built-in skills
tool/         Tool, ToolContext, ToolRegistry, ToolDispatcher, SimpleTool + 6 tool groups
agent/        AgentRuntime (wiring root), AgentLoop (observe→think→act→reflect)
event/        AgentEvent, AgentEventType   (added)
execution/    MovementController            (added)
llm/          AgentPromptBuilder, AgentDecisionParser   (added)
```

**Agent tick flow**

```
SteveEntity.tick()
  ├─ actionExecutor.tick()      // 20 TPS: the old action queue still does the real work
  └─ agentRuntime.tick()
       ├─ drain AgentEvent bus          // chat, low health, hurt, ...
       ├─ perception (2 Hz)             // Observation
       ├─ Needs.updateFrom + GoalManager.reevaluate   // dynamic priorities + autonomy
       ├─ LLM answer in flight?         → apply it (goal_update / actions / chat reply)
       ├─ executor busy?                → stay out of the way
       └─ advance(): skill plan → dispatch steps → reflect on failure → close goal
```

**Cost control** (three layers, in order): `SkillRegistry` (deterministic, free) →
cached/resilient LLM call (only when `Plan.needsLlm()`) → `ToolDispatcher` (no-op-free).

**Action ↔ Tool boundary:** tools are thin adapters that `enqueueTask(...)` onto the existing
`ActionExecutor`. The 16 original actions were deliberately left untouched.

**Two-planner hazard:** when `agent.enabled=true`, `SteveEntity` calls
`actionExecutor.setExternalPlanningEnabled(true)` so the legacy `maybeReplan` stays out of the
way. Only the agent's `Reflection` may rewrite the queue.

**Late-failure correlation:** a tool returns `ToolResult.scheduled` immediately, so a failing
action is only detected via `ActionExecutor.ExecutionListener#onActionFinished`, which calls
`AgentLoop.onActionFinished(...)` → `Reflection`.

**1.20.1 gotcha:** `FoodData` lives on `Player`, *not* on `LivingEntity`. `SteveEntity` is a
`PathfinderMob`, so it has no hunger bar. `SelfObserver` reports `food = -1` ("not applicable")
and `Needs` maintains its own satiety, relieved by `Needs.onAte()` when a `use_item(self=true)`
step runs.

## Localisation (two tracks — do not mix them)

1. **Mod UI text** → `Component.translatable("aisteve.…")` + `assets/aisteve/lang/zh_cn.json`
   / `en_us.json`. Resolved by Minecraft on each **client**, so it adapts per player with zero
   configuration.
2. **Text the AI says** → `AgentLang.t("agent.…")` + `assets/aisteve/agent/strings_<locale>.json`.
   These are assembled **server-side** and then broadcast, and the server cannot know any
   client's language setting, so the conversation language is tracked separately and follows
   whoever last spoke to the AI (`AgentLang.observePlayerSpeech`).

Rules:
- Never add a raw string to a chat line, a command reply or a window title. Use the right track.
- The `agent/` bundles live outside `lang/` on purpose — `lang/` is loaded by the client's
  resource manager and would be the wrong consumer.
- Run `node scripts/check-lang.js` and `node scripts/check-keys.js` after touching any bundle.
  `check-keys.js` catches typo'd or missing keys, which otherwise surface at runtime as the
  literal key name appearing in chat.
- When adding a new tool/skill, its user-visible strings go in **both** `agent/` bundles.
  The prompt's own instructions stay Chinese (internal, not player-visible); the *output*
  language is driven by the directive + localised examples in `AgentPromptBuilder`.

**Threading.** Commands and chat arrive on non-server threads. `AgentLoop.requestInstruction`
only **enqueues** (`ConcurrentLinkedQueue`); `processInbox()` drains it on the server thread
inside `tick()`. Never touch agent state directly from a command/event thread.

**Don't pre-check the inventory at dispatch time.** `Observation` is a 2 Hz snapshot, so a
`give_item` step dispatched right after `craft_item` sees a stale backpack. Tools must queue and
let the action decide at execution time — otherwise the AI reports "I don't have X" and then
hands X over anyway (see changelog item 59).

**Talking.** `narrate()` sends chat **directly**, not through the action queue, so a long mining
job cannot delay a progress report. Throttled to `NARRATION_INTERVAL_TICKS`, with the suppressed
line replayed later. `maybeIdleChat()` gives it something to say when idle.

**Roam limit.** `RoamRadius` (config `[agent].roamRadius`) is enforced in three places: a
high-priority "回到 X 身边" goal from `GoalManager.applyRoamLimit`, a clamp inside
`ExploreAction.onTick`, and the distance clamp in `ExplorationSkill`. Skipped during emergencies.

**Defence.** `ServerEventHandler.onLivingHurt` → `SteveEntity.onPlayerHurt` → `PLAYER_HURT`
event → `AgentLoop.handlePlayerHurt` submits a `PROTECT` goal. The only path that can ever
target a player is the temporary whitelist in `ToolContext.allowedPlayerTargets`, gated by
`[agent].defendAgainstPlayers` and cleared when the goal finishes.

## Action System

**Available actions** (defined in `action/actions/`):
- `mine <block> [count]` - Walks to nearest block, mines it. For ores: digs staircase to correct Y-level and branch-mines.
- `place <block> <x> <y> <z>` - Places block at coordinates
- `craft <item> [count]` - Uses real recipe system. Auto-crafts crafting table if needed for 3×3 recipes.
- `use_item <item> [target]` - Generic item use (eat, place torch, light fire, etc.)
- `attack <entity> [count]` - Combat. Can specify quantity (e.g., "kill 3 sheep").
- `pickup` - Collects nearby dropped items
- `give <item> [count]` - Transfers item to player
- `fish` - Fishes at nearest water (requires fishing rod in inventory)
- `farm` - Harvests crops (wheat/carrot/potato/beetroot) and replants
- `loot_container` - Opens and takes items from chests/barrels/furnaces
- `explore <direction> <distance>` - Walks in a direction to discover resources
- `build <structure>` - Constructs procedural structures (house/castle/tower/barn/modern)
- `follow` - Stays within 6 blocks of player
- `say <message>` - Sends chat message
- `pathfind <x> <y> <z>` - Low-level navigation

**Adding new actions:**
1. Create class in `action/actions/` extending `BaseAction`
2. Implement `tick()` for incremental execution (returns CONTINUE/SUCCESS/FAILURE)
3. Register in `ActionRegistry` via SPI or legacy switch in `ActionExecutor`
4. Update `PromptBuilder.buildSystemPrompt()` to document action for LLM

**Action execution model:** Tick-based. Each action's `tick()` is called once per game tick (20 TPS) until it returns SUCCESS or FAILURE. Long-running actions (mining, pathfinding) spread work across many ticks.

## LLM Integration

**Provider selection:** Controlled by `[ai].provider` in config. Supported: `deepseek`, `openai`, `groq`, `gemini`.

**Request flow:**
1. `TaskPlanner.planTasksAsync()` builds prompt via `PromptBuilder`
2. Selects async client based on provider config
3. `ResilientLLMClient` wraps call with:
   - **Circuit breaker:** Opens after 5 consecutive failures (60s timeout)
   - **Retry:** Up to 3 attempts with exponential backoff (1s, 2s, 4s)
   - **Rate limiting:** Configurable per-provider
   - **Cache:** SHA-256 hash of (prompt, model, temp) as key
   - **Fallback:** Rule-based responses if all providers fail
4. Response parsed as JSON array of `{action, parameters}` objects

**Prompt structure:**
- **System prompt:** Action catalog, JSON schema, behavioral guidelines
- **User prompt:** Current inventory + world knowledge (discovered locations) + player instruction

**World knowledge injection:** `WorldKnowledge` scans `WorldMemory` for nearby landmarks and formats as:
```
Known locations:
- village at [123, 65, -456] (95m northwest)
- water at [200, 63, 100] (150m east)
```

**Fallback behavior:** When LLM unreachable, `LLMFallbackHandler` returns hardcoded action lists for common commands (e.g., "mine iron" → `[{action: mine, parameters: {block: iron_ore, count: 1}}]`). Logged as `(fallback)` in console.

## Common Development Tasks

**Add a new LLM provider:**
1. Add config fields in `SteveConfig` (apiKey, model, baseUrl)
2. Create `Async<Provider>Client` implementing `AsyncLLMClient` interface
3. Register in `TaskPlanner` constructor
4. Add provider name to switch in `TaskPlanner.getAsyncClient()`

**Modify AI capabilities:**
- Update action implementations in `action/actions/`
- Sync changes to `PromptBuilder.buildSystemPrompt()` (this is the LLM's documentation)
- Test with `/as` commands, check logs for plan output

**Debug LLM failures:**
1. Check `logs/latest.log` for `TaskPlanner` entries
2. Look for HTTP error codes (401=bad key, 429=rate limit, 402=no credit)
3. Search for `Plan received: (N tasks, 0ms, 0 tokens)` - indicates fallback (not real LLM response)
4. Verify config loaded: grep log for "provider" and "apiKey"

**Test changes in dev:**
```bash
./gradlew runClient
# In-game:
/as create TestBot
/as <your test instruction>
/as info  # Check inventory/state
```

**Reproduce user issues:**
- Always test with `aisteve-1.0.0-all.jar` installed in real Minecraft, not just dev environment
- Check `config/aisteve-common.toml` exists and has valid keys
- Verify Forge 47.2.0+ (not Fabric - mod will silently fail to load)

## Testing

**Unit tests:** `src/test/java/` (mostly TODO placeholders). Run with `./gradlew test`.

**Integration testing:** Manual in-game testing required. Key scenarios:
- Mining (surface blocks, ores requiring deep mining)
- Crafting (2×2 recipes, 3×3 requiring table)
- Food acquisition (fishing, farming, looting, hunting)
- Building (each structure type)
- Combat (hostile mobs, animals)
- Memory persistence (create AI, discover village, restart game, verify memory retained)

**Logging:** All AI operations log to `logs/latest.log`. Key prefixes:
- `[aisteve/TaskPlanner]` - LLM requests/responses
- `[aisteve/ActionExecutor]` - Action lifecycle
- `[aisteve/SteveEntity]` - Entity lifecycle, despawn prevention

## Known Issues & Design Decisions

**Single AI limit:** Enforced by `SteveManager.hasActiveSteve()`. Attempting to create a second AI fails with error message. This is intentional - multi-AI support would require major architecture changes (collision resolution, resource contention, communication protocol).

**Despawn prevention:** `SteveEntity` calls `setPersistenceRequired()` and overrides `removeWhenFarAway()` to prevent vanilla despawning. Prior to this fix, AIs would silently disappear after 30s of inactivity beyond 32 blocks from player.

**No furnace smelting:** Crafting system uses `RecipeManager` for crafting table recipes only. Smelting would require tracking furnace state across ticks and is not implemented.

**GraalVM not bundled:** `CodeExecutionEngine` exists but is never instantiated. The JS engine would add ~100MB to jar size and conflict with other mods. If JS execution is needed in future, it should be an optional dependency.

**Commons-codec exclusion:** Forge 1.20.1 bundles commons-codec 1.15. Bundling it in fatJar causes module conflict (`ResolutionException: Modules steve and org.apache.commons.codec export package`). The `fatJar` task explicitly excludes it - use Forge's copy at runtime.

**Migration from old mod:** Prior versions used modId `steve` with commands `/tai` and `/steve`. Current version is modId `aisteve` with command `/as`. In-place upgrade requires running `migrate-to-aisteve.ps1` to copy config, then `/as cleanup` in-game to remove old entities.

## Important Files

- `build.gradle` - Build config, dependency management, fatJar task definition
- `src/main/java/com/steve/ai/SteveMod.java` - Mod entry point, entity registration
- `src/main/java/com/steve/ai/entity/SteveEntity.java` - Core AI entity with inventory and action executor
- `src/main/java/com/steve/ai/agent/AgentRuntime.java` - assembles every layer for one AI (see ARCHITECTURE.md)
- `src/main/java/com/steve/ai/agent/AgentLoop.java` - the slow-thinking loop (perception, goals, LLM, reflection)
- `src/main/java/com/steve/ai/tool/ToolDispatcher.java` - permission gate; the AI's real capability boundary
- `src/main/java/com/steve/ai/brain/GoalManager.java` - goal stack with dynamic priorities
- `src/main/java/com/steve/ai/perception/PerceptionService.java` - dual-rate world → Observation
- `src/main/java/com/steve/ai/memory/MemoryManager.java` - four memory layers + NBT persistence
- `src/main/java/com/steve/ai/action/ActionExecutor.java` - Action queue and tick loop
- `src/main/java/com/steve/ai/llm/TaskPlanner.java` - LLM provider routing
- `src/main/java/com/steve/ai/llm/PromptBuilder.java` - System prompt with action documentation
- `src/main/java/com/steve/ai/config/SteveConfig.java` - Forge config specification
- `config/aisteve-common.toml.example` - Config template with all providers documented
