package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private static final int MAX_RECENT_ACTIONS = 20;

    // Conversation history for multi-turn dialogue
    private final LinkedList<ConversationTurn> conversationHistory;
    private static final int MAX_CONVERSATION_TURNS = 10;

    public static class ConversationTurn {
        public final String userMessage;
        public final String aiResponse;
        public final long timestamp;

        public ConversationTurn(String userMessage, String aiResponse) {
            this.userMessage = userMessage;
            this.aiResponse = aiResponse;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public SteveMemory(SteveEntity steve) {
        this.steve = steve;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
        this.conversationHistory = new LinkedList<>();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Stores the current goal.
     *
     * <p>Null is normalised to an empty string: callers clear the goal that way when a
     * conversation happens or a job finishes, and {@link #saveToNBT} cannot persist null.</p>
     */
    public void setCurrentGoal(String goal) {
        this.currentGoal = (goal == null) ? "" : goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();

        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }

        return result;
    }

    /**
     * Adds a conversation turn to history.
     * Keeps only the most recent turns to avoid memory bloat.
     */
    public void addConversationTurn(String userMessage, String aiResponse) {
        conversationHistory.addLast(new ConversationTurn(userMessage, aiResponse));
        if (conversationHistory.size() > MAX_CONVERSATION_TURNS) {
            conversationHistory.removeFirst();
        }
    }

    /**
     * Gets recent conversation history formatted for LLM prompt.
     * Returns empty string if no history.
     */
    public String getConversationHistoryForPrompt() {
        if (conversationHistory.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 最近的对话历史 ===\n");
        for (ConversationTurn turn : conversationHistory) {
            sb.append("玩家: ").append(turn.userMessage).append("\n");
            sb.append("你: ").append(turn.aiResponse).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Clears conversation history (useful for starting fresh).
     */
    public void clearConversationHistory() {
        conversationHistory.clear();
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    public void saveToNBT(CompoundTag tag) {
        // Defensive: NBT cannot store null, and "no goal" is represented as "".
        tag.putString("CurrentGoal", (currentGoal == null) ? "" : currentGoal);

        ListTag actionsList = new ListTag();
        for (String action : recentActions) {
            actionsList.add(StringTag.valueOf(action));
        }
        tag.put("RecentActions", actionsList);

        // Save conversation history
        ListTag conversationList = new ListTag();
        for (ConversationTurn turn : conversationHistory) {
            CompoundTag turnTag = new CompoundTag();
            turnTag.putString("User", turn.userMessage);
            turnTag.putString("AI", turn.aiResponse);
            turnTag.putLong("Time", turn.timestamp);
            conversationList.add(turnTag);
        }
        tag.put("ConversationHistory", conversationList);
    }

    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("CurrentGoal")) {
            currentGoal = tag.getString("CurrentGoal");
        }

        if (tag.contains("RecentActions")) {
            recentActions.clear();
            ListTag actionsList = tag.getList("RecentActions", 8); // 8 = String type
            for (int i = 0; i < actionsList.size(); i++) {
                recentActions.add(actionsList.getString(i));
            }
        }

        // Load conversation history
        if (tag.contains("ConversationHistory")) {
            conversationHistory.clear();
            ListTag conversationList = tag.getList("ConversationHistory", 10); // 10 = Compound type
            for (int i = 0; i < conversationList.size(); i++) {
                CompoundTag turnTag = conversationList.getCompound(i);
                String userMsg = turnTag.getString("User");
                String aiMsg = turnTag.getString("AI");
                conversationHistory.add(new ConversationTurn(userMsg, aiMsg));
            }
        }
    }
}

