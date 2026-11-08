import com.elitonvita.aibuilder.ActionBudget;

/** Standalone regression checks: no server or external dependencies required. */
public final class ActionBudgetTest {
    private static int checks;

    public static void main(String[] args) {
        ActionBudget budget = new ActionBudget();
        for (int i = 0; i < 100; i++) check(budget.allows(1_000L, 2), "checks must not consume quota");
        budget.record(1_000L);
        check(budget.allows(1_001L, 2), "one remaining action");
        budget.record(1_001L);
        check(!budget.allows(1_002L, 2), "at limit");
        check(!budget.allows(60_999L, 2), "backoff before boundary");
        check(budget.allows(61_000L, 2), "exact 60 second boundary expires oldest");
        check(!budget.allows(61_000L, 1), "second action remains in window");
        check(budget.allows(61_001L, 1), "second action expires at own boundary");
        check(!budget.allows(61_002L, 0), "zero quota denies");
        check(!budget.allows(61_002L, -1), "negative quota denies");

        ActionBudget rollback = new ActionBudget();
        rollback.record(10_000L);
        check(!rollback.allows(9_000L, 2), "rollback fails closed");
        check(rollback.allows(10_000L, 2), "clock catches up");
        rollback.record(9_500L);
        check(!rollback.allows(69_999L, 2), "rollback record uses safe timestamp");
        check(rollback.allows(70_000L, 2), "safe timestamp expires normally");

        ActionBudget bounded = new ActionBudget();
        for (int i = 0; i < 100_000; i++) bounded.record(0L);
        check(!bounded.allows(0L, Integer.MAX_VALUE), "quota is hard capped");
        bounded.record(1L);
        check(!bounded.allows(60_000L, 1), "overflow conservatively blocks a full window");
        check(bounded.allows(60_001L, 1), "saturated window eventually expires");
        System.out.println("ActionBudgetTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
