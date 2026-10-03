# Contributing to AiSteve

Thanks for taking the time to help. This document covers the parts that are
specific to *this* mod, so you can skip discovering them the hard way.

- [Ways to contribute](#ways-to-contribute)
- [Development setup](#development-setup)
- [Project layout](#project-layout)
- [Architecture rules you must not break](#architecture-rules-you-must-not-break)
- [How to add a tool](#how-to-add-a-tool)
- [How to add a skill](#how-to-add-a-skill)
- [How to add an action](#how-to-add-an-action)
- [Commit conventions](#commit-conventions)
- [Pull request checklist](#pull-request-checklist)

---

## Ways to contribute

- **Bug reports** — please include the log excerpt, the exact command you typed,
  and what you expected instead. `logs/latest.log` is the single most useful
  thing you can attach. See `TROUBLESHOOTING.md` first.
- **New tools / skills / actions** — the most welcome kind of PR. See the
  step-by-step guides below.
- **Prompt improvements** — `AgentPromptBuilder` is where the model's behaviour
  is shaped. Small wording changes can have a large effect; please describe what
  you observed before and after.
- **Documentation** — especially the Chinese docs, which are the primary ones
  for most players.

Please **do not** open a PR that only reformats files. It makes the history
useless and the review impossible.

---

## Development setup

**Requirements**

| Component | Version | Notes |
| --- | --- | --- |
| JDK | **17 exactly** | Forge 1.20.1 requires it. JDK 21+ breaks the build. |
| Minecraft | 1.20.1 + Forge 47.2.0+ | |
| Gradle | — | Use the bundled wrapper; do not install your own. |
| RAM | ≥ 3 GB free | Gradle + Forge caches are large. |

**Commands**

```bash
./gradlew compileJava              # fast syntax/type check
./gradlew build -x test fatJar     # produce the installable jar
./gradlew runClient                # launch a dev client with the mod loaded
```

> **Always use `fatJar` for a distributable jar.** The plain `jar` output contains
> only the mod's own classes and will crash at runtime with
> `NoClassDefFoundError` as soon as the AI tries to plan anything.

On Windows use `gradlew.bat`. Some machines need a proxy for the initial
dependency download; see `docs/BUILD.zh-CN.md`.

Full build/distribution details: **`docs/BUILD.zh-CN.md`**.

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
├── action/       The 16 low-level actions that actually touch the world
├── llm/          Provider clients, prompts, response parsing
├── event/        Forge event glue + the agent event bus
├── entity/       SteveEntity, inventory, manager
├── structure/    Procedural generation and template loading
└── ...
```

Read **`ARCHITECTURE.md`** before touching anything under `brain/`, `skill/`,
`tool/` or `agent/`. The layering is deliberate and the reasons are written down
there.

---

## Architecture rules you must not break

These are not style preferences. Each one exists because breaking it produced a
real, reported bug.

1. **The LLM never drives per-tick input.**
   It decides *what to do next*. The action layer decides how.

2. **Movement only goes through `MovementController`.**
   Never `setPos`, never teleport. The one remaining teleport is the anti-lost
   recovery in `ActionExecutor`, reachable only from a human command or the
   stuck detector. There is deliberately no `teleport` method on
   `MovementController`.

3. **Never report a value perception did not observe.**
   A `PathfinderMob` has no hunger bar in 1.20.1, so `SelfObserver` reports
   `food = -1` for "not applicable" rather than inventing a number.

4. **Tools must be honest.**
   `ToolResult.missingTool` / `unsupported` is correct. A silent no-op is not:
   the brain treats `scheduled` as "in progress" and will wait forever.

5. **Never pre-check mutable state at dispatch time.**
   `Observation` is a 2 Hz snapshot. A `give_item` step dispatched right after
   `craft_item` sees a stale backpack and produces the classic
   *"I don't have X"* followed immediately by *handing X over*. Queue the step
   and let the action decide at execution time.

6. **Capability boundaries live in code, not in the prompt.**
   `ToolDispatcher` enforces `Permission` + `RiskLevel`. `Permission.ADMIN`
   tools are never visible to the model.

7. **Never attack players.**
   The single exception is the temporary defence whitelist
   (`ToolContext.allowedPlayerTargets`), which is set only when a player attacks
   the player we protect, gated by `[agent].defendAgainstPlayers`, and cleared
   when the goal finishes.

8. **Agent state is touched only on the server tick thread.**
   Commands and chat arrive on other threads. `AgentLoop.requestInstruction`
   enqueues; `processInbox()` drains on tick.

---

## How to add a tool

A tool is one atomic capability — "the AI's hands".

1. Pick the right group in `tool/` (`MovementTools`, `InteractionTools`,
   `InventoryTools`, `CombatTools`, `WorldTools`, `SocialTools`). Add a private
   static factory method and register it in that group's `register(...)`.
2. Declare the `ToolSpec`, including `permission` and `risk`. If it can harm the
   world or a player, say so honestly.
3. Implement `invoke`. Two rules:
   - **Queue long work**, don't perform it: `ctx.enqueue("action_name", args)`
     and return `ToolResult.scheduled(...)`.
   - **Fail loudly**: return `missingTool` / `badArguments` / `notFound`
     rather than pretending.
4. If you added a new underlying action name, make sure it is registered in
   `CoreActionsPlugin` and permitted in `ActionCapabilities`.

The tool catalogue in the prompt is generated from `ToolRegistry`, so you do not
have to update any prompt text by hand.

---

## How to add a skill

A skill is a *complex* capability composed of several tools — and the reason the
twenty most common requests never cost an API call.

1. Create a class implementing `Skill` in `skill/`.
2. `canHandle` must be **conservative**. Claiming something you only half
   understand produces worse behaviour than letting the LLM handle it.
3. `plan` returns a `SkillPlan` of ordered `ToolCall`s.
4. Register it in `SkillRegistry.createDefault()`, **before** the more general
   skills — the first match wins.

Good skills are deterministic and honest: if the plan is "I looked and there is
nothing to do", return `SkillPlan.nothing(...)` instead of an empty tool list
with a misleading narrative.

Keyword tables live in `SkillSupport`. Put shared aliases there rather than
reinventing them per skill.

---

## How to add an action

Actions are the layer that actually touches Minecraft.

1. Create a class extending `BaseAction` in `action/actions/`.
2. Implement `onStart` / `onTick` / `onCancel`. `onTick` runs once per game tick
   and must return promptly — spread long work across ticks.
3. Register the name in `CoreActionsPlugin`.
4. Use `ActionUtils` for name resolution (`parseBlock`, `parseItem`,
   `findBestTool`). Do **not** write another private parser.
5. Report honestly. Use the `ActionResult` factories:
   - `success(msg)` — fully done
   - `partial(msg)` — did all it usefully could; **does not** trigger replanning
   - `failure(msg)` — recoverable; asks for a different approach
   - `giveUp(msg)` — pointless to retry

Never conjure items or blocks out of thin air. If the AI lacks the material, it
gathers it, asks the player, or fails honestly.

---

## Commit conventions

[Conventional Commits](https://www.conventionalcommits.org/), with the area in
the scope. One logical change per commit.

```
<type>(<scope>): <short summary>

<optional body — why, not what>
```

**Types:** `feat`, `fix`, `refactor`, `perf`, `docs`, `build`, `test`, `chore`, `style`

**Scopes:** `agent`, `brain`, `skill`, `tool`, `perception`, `memory`, `action`,
`llm`, `gui`, `command`, `config`, `build`, `docs`

Examples from this project's history:

```
feat(skill): add FishingSkill for renewable food
fix(tool): stop give_item pre-checking a stale inventory snapshot
fix(agent): close the goal when the model returns no actions
perf(memory): cap semantic retrieval at six facts per prompt
docs(readme): add Chinese README and status document
```

If a change fixes a reported bug, say what the user actually saw — the reasoning
is worth more than the diff. `CHANGELOG.md` documents bugs this way and it has
repeatedly saved future maintainers.

---

## Pull request checklist

- [ ] `./gradlew compileJava` passes.
- [ ] `./gradlew build -x test fatJar` passes.
- [ ] Tested in-game with `./gradlew runClient` (or a real client with the
      `-all` jar).
- [ ] No API keys, no `build/`, no `run/`, no generated `.nbt` in the diff.
- [ ] New tools/skills/actions are registered, or they will never be reachable.
- [ ] Commit messages follow the convention above.
- [ ] If behaviour changed, `CHANGELOG.md` says what the user saw before and after.
- [ ] If it is a user-visible gap or limitation, `docs/STATUS.md` is updated.
- [ ] If you added any user-visible string, it is in **both** language bundles, and
      `node scripts/check-lang.js && node scripts/check-keys.js` reports no missing keys.
      See the Localisation section in `CLAUDE.md` — the two tracks (client UI vs AI speech) are
      deliberately separate and must not be mixed up.

---

## License

By contributing you agree that your work is released under the MIT License — see
`LICENSE`.
