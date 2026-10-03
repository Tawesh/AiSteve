package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

/**
 * Makes the Steve speak in chat.
 *
 * <p>Used by the LLM planner to ask the player for something it needs
 * (for example: "我需要一个打火石"). Purely cosmetic / communicative - it never
 * changes world state, so it completes immediately.</p>
 *
 * <p><b>Parameters:</b> {@code message} - the text to say.</p>
 */
public class SayAction extends BaseAction {

    public SayAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String message = task.getStringParameter("message", "");
        if (message.isEmpty()) {
            result = ActionResult.failure("Nothing to say");
            return;
        }
        steve.sendChatMessage(message);
        result = ActionResult.success("Said: " + message);
    }

    @Override
    protected void onTick() {
        // Nothing to do - completes in onStart()
    }

    @Override
    protected void onCancel() {
        // Nothing to clean up
    }

    @Override
    public String getDescription() {
        return "Say: " + task.getStringParameter("message", "");
    }
}
