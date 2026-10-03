package com.steve.ai.llm;

import com.steve.ai.brain.GoalManager;
import com.steve.ai.brain.Needs;
import com.steve.ai.brain.Persona;
import com.steve.ai.i18n.AgentLang;
import com.steve.ai.i18n.ConversationLanguage;
import com.steve.ai.memory.MemoryManager;
import com.steve.ai.protocol.Observation;
import com.steve.ai.tool.ToolRegistry;

/**
 * Builds the two prompts that drive the slow-thinking loop.
 *
 * <p>Follows the architecture document's structure exactly:</p>
 * <pre>
 * System Prompt
 * + Persona
 * + Current Goal
 * + Relevant Memory
 * + Current Observation
 * + Recent Events
 * + Available Tools
 * </pre>
 *
 * <p>The prompt stays small on purpose. Nothing about the world is included except the
 * already-compressed {@link Observation}, and nothing about memory except the few entries the
 * retrievers judged relevant. "不要把所有世界信息都塞给 LLM" is enforced here, at the one
 * place where tokens are actually spent.</p>
 */
public final class AgentPromptBuilder {

    private AgentPromptBuilder() {
    }

    /**
     * The system prompt: who the AI is, what it may never do, and the exact output contract.
     */
    public static String buildSystemPrompt(Persona persona, ToolRegistry tools,
                                          ConversationLanguage language) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是《我的世界》里一个**真实的玩家**，不是脚本机器人，也不是助手程序。\n");
        sb.append("你的名字是 ").append(persona.name()).append("，")
          .append("你和真人玩家一起在这个世界里生存、探索、战斗、建造。\n\n");

        // ---- Output language -----------------------------------------------------------
        // 放在最前面是有意的：模型对"开头就给出的输出约束"遵守得最好。
        // 提示词本身用中文写（它是内部指令，玩家看不到），但产出的自然语言必须是指定语言。
        ConversationLanguage lang = language == null ? ConversationLanguage.ZH_CN : language;
        sb.append(AgentLang.t(lang, "agent.prompt.language",
            lang.promptName(), lang.promptName(), lang.promptName()));

        // ---- Hard constraints: the "behaves like a player" contract --------------------
        sb.append("================= 不可违背的规则 =================\n");
        sb.append("R1. 你不能瞬移，只能像玩家一样走过去。\n");
        sb.append("R2. 你不能凭空获得任何物品；背包里没有的东西就是没有。\n");
        sb.append("R3. 你不知道视野和感知范围之外的信息。上面给你的就是你知道的全部。\n");
        sb.append("R4. 你只能使用下面【可用工具】里列出的能力，不能自己发明工具或参数。\n");
        sb.append("R5. 你只决定“下一步做什么”，具体怎么走、怎么挖由程序负责，\n");
        sb.append("    所以不要输出“按 W 前进”这种操作，只要说“走到某个坐标”。\n");
        sb.append("R6. 我不会攻击玩家。永远不要把玩家当作攻击目标。\n");
        sb.append("R7. 缺东西就如实说缺，用 send_chat/ask_player 向玩家要，\n");
        sb.append("    绝不要假装已经完成。\n");
        sb.append("R8. 只输出 JSON，不要 markdown 代码块，不要输出任何解释文字。\n");
        sb.append("R9. 你以玩家为中心活动。不要跑远：出了范围会被自动叫回来。\n");
        sb.append("    找东西优先在玩家附近找（往下挖、找洞穴、看地表），而不是长途跋涉。\n");
        sb.append("R10. 挖矿用 break_block 就够了，它会自己处理深度，不要指定坐标、不要先走很远。\n");
        sb.append("R11. 玩家被打时你要去保护他：直接攻击那个攻击者，不要先聊天。\n");
        sb.append("R12. 不确定要做什么时，就在玩家附近待着，或者问一句，不要自己乱跑。\n");
        sb.append("R13. 先用手上已有的东西。背包里有的工具/物品直接用，不要重复收集。\n");
        sb.append("     背包里没有的才去采集或合成；实在弄不到就用 ask_player 向玩家要。\n\n");

        // ---- Persona -------------------------------------------------------------------
        sb.append(persona.toPromptText()).append('\n');

        // ---- Output contract -----------------------------------------------------------
        sb.append("================= 输出格式（严格 JSON，二选一） =================\n");
        sb.append("【A. 闲聊/提问】玩家只是打招呼、聊天、问问题：\n");
        sb.append("{\"intent\":\"chat\",\"reply\":\"你的中文回复\"}\n\n");
        sb.append("【B. 交办任务或自主行动】需要做事：\n");
        sb.append("{\"intent\":\"task\",\"thought\":\"简短推理(20字内)\",");
        sb.append("\"goal_update\":{\"description\":\"目标描述\",\"type\":\"resource\",\"priority\":70},");
        sb.append("\"actions\":[{\"tool\":\"工具名\",\"arguments\":{...},\"reason\":\"为什么\"}]}\n\n");
        sb.append("说明：\n");
        sb.append("· intent=chat 时不要产生 actions。\n");
        sb.append("· goal_update 可以省略（省略表示继续当前目标）。\n");
        sb.append("· type 可选：survive / protect / task / resource / build / social / explore / idle。\n");
        sb.append("· priority 0~100，越大越优先；不确定就写 70。\n");
        sb.append("· actions 按执行顺序排列，通常 1~5 步。\n\n");

        // ---- Tool catalogue ------------------------------------------------------------
        sb.append("================= 可用工具 =================\n");
        sb.append(tools.describeForPrompt()).append('\n');

        // ---- Examples (localised: they model the expected output language) --------------
        sb.append(AgentLang.t("agent.prompt.examples", persona.name()));

        return sb.toString();
    }

    /**
     * The user prompt: everything the AI currently knows, in the order it will read it.
     *
     * @param instruction   the player's latest words, or a synthetic line for autonomous cycles
     * @param reflectionNote optional feedback from the reflection layer ("上一步失败了：…")
     */
    public static String buildUserPrompt(Observation observation,
                                         GoalManager goals,
                                         Needs needs,
                                         MemoryManager memory,
                                         String instruction,
                                         String reflectionNote,
                                         long nowTick) {

        StringBuilder sb = new StringBuilder();

        if (reflectionNote != null && !reflectionNote.isBlank()) {
            sb.append("================= 反思反馈 =================\n");
            sb.append(reflectionNote).append("\n\n");
        }

        sb.append("================= 你现在看到的世界 =================\n");
        sb.append(observation == null ? "（还没有感知到环境）\n" : observation.toPromptText());
        sb.append('\n');

        String needText = needs == null ? "" : needs.toPromptText();
        if (!needText.isBlank()) {
            sb.append(needText).append('\n');
        }

        sb.append(goals.toPromptText()).append('\n');

        String goalQuery = goals.open().stream().findFirst()
            .map(g -> g.description()).orElse("");
        String memoryText = memory.toPromptText(nowTick, goalQuery);
        if (!memoryText.isBlank()) {
            sb.append("================= 你的记忆 =================\n");
            sb.append(memoryText).append('\n');
        }

        sb.append("================= 玩家的话 =================\n");
        sb.append(instruction == null || instruction.isBlank()
            ? "（没有新的指令，你自己决定下一步做什么）" : instruction);
        sb.append("\n\n");

        sb.append("================= 你的 JSON 回复 =================\n");
        return sb.toString();
    }
}
