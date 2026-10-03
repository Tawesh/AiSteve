package com.steve.ai.action;

/**
 * Result of a finished action.
 *
 * <p>Carries two independent pieces of information, which the executor uses differently:</p>
 * <ul>
 *   <li><b>success</b> - did the action achieve what it set out to do?</li>
 *   <li><b>requiresReplanning</b> - is it worth asking the LLM for a different approach?</li>
 * </ul>
 *
 * <p>Keeping these separate matters. Plenty of outcomes are "not fully successful" yet
 * completely fine to stop on: a partially filled mining quota, a target that no longer
 * exists, a request that is simply impossible. Treating all of those as retryable used to
 * make the AI announce "I failed" - and eventually give up out loud - even when the job was
 * effectively done.</p>
 */
public class ActionResult {
    private final boolean success;
    private final String message;
    private final boolean requiresReplanning;

    public ActionResult(boolean success, String message) {
        this(success, message, !success);
    }

    public ActionResult(boolean success, String message, boolean requiresReplanning) {
        this.success = success;
        this.message = message;
        this.requiresReplanning = requiresReplanning;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public boolean requiresReplanning() {
        return requiresReplanning;
    }

    /** The action did exactly what was asked. */
    public static ActionResult success(String message) {
        return new ActionResult(true, message, false);
    }

    /**
     * Did as much as it usefully could and there is nothing sensible left to try.
     *
     * <p>Treated as a completed step: the goal may close normally, no replanning is triggered
     * and the player is not told that something went wrong.</p>
     *
     * <p>Example: "mine 8 iron" but only 3 exist in reach - reporting that honestly and
     * finishing is correct behaviour, not a failure loop.</p>
     */
    public static ActionResult partial(String message) {
        return new ActionResult(true, message, false);
    }

    /**
     * Failed, and a different approach could plausibly succeed - ask the LLM to replan.
     *
     * <p>Use for recoverable situations: no path found, missing tool, target disappeared,
     * resource out of range.</p>
     */
    public static ActionResult failure(String message) {
        return new ActionResult(false, message, true);
    }

    public static ActionResult failure(String message, boolean requiresReplanning) {
        return new ActionResult(false, message, requiresReplanning);
    }

    /**
     * Failed, and retrying is pointless - stop without consulting the LLM.
     *
     * <p>Use for unrecoverable or user-error situations: malformed parameters, a request the
     * AI will never be able to satisfy, or work that is already finished.</p>
     */
    public static ActionResult giveUp(String message) {
        return new ActionResult(false, message, false);
    }

    @Override
    public String toString() {
        return "ActionResult{success=" + success + ", message='" + message + "', requiresReplanning=" + requiresReplanning + "}";
    }
}
