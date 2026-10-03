package com.steve.ai.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SteveConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_MODEL;
    // DeepSeek (OpenAI-compatible) configuration
    public static final ForgeConfigSpec.ConfigValue<String> DEEPSEEK_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> DEEPSEEK_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> DEEPSEEK_BASE_URL;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;

    // ---- Layered agent (see ARCHITECTURE.md) ----------------------------------
    /** Master switch: run the new layered Agent Runtime instead of the legacy one-shot planner. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_AGENT;
    /** Allow the AI to generate its own goals from internal needs when nobody asks for anything. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_AUTONOMY;
    /**
     * How far (in blocks) the AI may stray from the player before it heads back.
     *
     * <p>This is the single most important knob for "看起来像在陪你玩" rather than
     * "跑了不见了": a real teammate does not wander 400 blocks away while you are mining.</p>
     */
    public static final ForgeConfigSpec.IntValue ROAM_RADIUS;
    /** Let the AI speak up on its own (progress reports, warnings, small talk). */
    public static final ForgeConfigSpec.BooleanValue ENABLE_IDLE_CHAT;
    /** Narrate plan start / step transition / completion out loud, like a teammate would. */
    public static final ForgeConfigSpec.BooleanValue ENABLE_PROGRESS_NARRATION;
    /**
     * Whether the AI may fight back against *players* who attack the player it protects.
     *
     * <p>On by default because that is what "保护玩家" means, but it is the only path in the
     * whole agent that can ever target a player - and it is restricted to the specific player who
     * just attacked, for a limited window. Set to false to keep PvP strictly out of the AI's
     * repertoire.</p>
     */
    public static final ForgeConfigSpec.BooleanValue DEFEND_AGAINST_PLAYERS;
    /**
     * The language the AI speaks when nobody has spoken yet.
     *
     * <p>Once a player talks to the AI, the language follows <em>them</em> - a companion that
     * answers in the language you addressed it in is what a real teammate does. This setting only
     * decides the starting language and the fallback for idle chatter.</p>
     *
     * <p>Note this is separate from the mod's UI, which has no setting at all: the interface is
     * translated by Minecraft itself and always matches each client's own language.</p>
     */
    public static final ForgeConfigSpec.ConfigValue<String> AGENT_LANGUAGE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("AI API Configuration").push("ai");
        
        AI_PROVIDER = builder
            .comment("AI provider to use: 'groq' (FASTEST, FREE), 'openai', 'gemini', or 'deepseek'")
            .define("provider", "groq");
        
        builder.pop();

        builder.comment("DeepSeek API Configuration (OpenAI-compatible endpoint)").push("deepseek");

        DEEPSEEK_API_KEY = builder
            .comment("Your DeepSeek API key. Get one from https://platform.deepseek.com/api_keys",
                     "If left empty, the value in [openai].apiKey will be used as a fallback.")
            .define("apiKey", "");

        DEEPSEEK_MODEL = builder
            .comment("DeepSeek model to use. Currently available: 'deepseek-flash' (V4.1-Flash, recommended),",
                     "'deepseek-v4-pro' (V4-Pro). Older names 'deepseek-chat' (V3) / 'deepseek-reasoner' (R1)",
                     "only work if your account still exposes them.")
            .define("model", "deepseek-flash");

        DEEPSEEK_BASE_URL = builder
            .comment("DeepSeek API base URL (do not include the trailing /chat/completions)")
            .define("baseUrl", "https://api.deepseek.com");

        builder.pop();

        builder.comment("OpenAI/Gemini API Configuration (same key field used for both)").push("openai");
        
        OPENAI_API_KEY = builder
            .comment("Your OpenAI API key (required)")
            .define("apiKey", "");
        
        OPENAI_MODEL = builder
            .comment("OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)")
            .define("model", "gpt-4-turbo-preview");
        
        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);
        
        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);
        
        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");
        
        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);
        
        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);
        
        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);
        
        builder.pop();

        builder.comment("Layered Agent Runtime (see ARCHITECTURE.md)").push("agent");

        ENABLE_AGENT = builder
            .comment("Use the layered Agent architecture (perception / memory / goals / skills / tools).",
                     "true  = the AI plans via GoalManager + skills, and only calls the LLM when needed",
                     "false = legacy behaviour: every /as say is translated straight into an action list")
            .define("enabled", true);

        ENABLE_AUTONOMY = builder
            .comment("Let the AI pursue its own needs (eat when hungry, retreat when hurt, explore when bored)",
                     "instead of standing still until a player gives it an order.")
            .define("autonomy", true);

        ROAM_RADIUS = builder
            .comment("How far the AI may stray from the player, in blocks.",
                     "If it ends up further away than this while doing a task it will work its way back.",
                     "Recommended 32-96. This is what keeps it 'playing with you' instead of vanishing.")
            .defineInRange("roamRadius", 48, 16, 256);

        ENABLE_IDLE_CHAT = builder
            .comment("Let the AI speak up on its own: progress reports, danger warnings, small talk.")
            .define("idleChat", true);

        ENABLE_PROGRESS_NARRATION = builder
            .comment("Narrate what it is doing out loud: '先砍树，再做木镐' ... '搞定了'.",
                     "Turn this off if you find it too chatty.")
            .define("progressNarration", true);

        DEFEND_AGAINST_PLAYERS = builder
            .comment("Allow the AI to fight back when ANOTHER PLAYER attacks you.",
                     "true  = it defends you in PvP too (default)",
                     "false = it never targets players, under any circumstance")
            .define("defendAgainstPlayers", true);

        AGENT_LANGUAGE = builder
            .comment("Language the AI speaks: 'zh_cn' or 'en_us'.",
                     "This is the STARTING language and the fallback for idle chatter.",
                     "Once a player talks to the AI it mirrors THEIR language, and switches back",
                     "whenever someone addresses it in the other one.",
                     "The mod's own UI is not affected - Minecraft translates that per client.")
            .define("language", "zh_cn");

        builder.pop();

        SPEC = builder.build();
    }
}

