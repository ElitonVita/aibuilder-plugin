import com.elitonvita.aibuilder.RequestGuard;

/** Real lifecycle regression tests, independent of Bukkit and HTTP. */
public class RequestGuardTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RequestGuard guard = new RequestGuard();
        check(!guard.isThinking(), "new guard should be idle");
        check(!guard.accepts(0), "idle guard accepted response");
        long first = guard.begin();
        check(guard.isThinking() && guard.accepts(first), "request did not start");
        boolean rejected = false;
        try { guard.begin(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "concurrent request accepted");
        check(guard.accepts(first), "rejected begin invalidated active request");
        check(!guard.complete(first + 1) && guard.isThinking(), "foreign completion released request");
        check(guard.complete(first) && !guard.isThinking(), "current completion did not release request");
        check(!guard.accepts(first) && !guard.complete(first), "duplicate completion accepted");
        long second = guard.begin();
        check(second > first && guard.accepts(second), "new request token not fresh");
        check(!guard.complete(first) && guard.accepts(second), "stale failure cleared current request");
        guard.invalidate();
        check(!guard.isThinking() && !guard.accepts(second), "stop did not invalidate response");
        long third = guard.begin();
        check(third > second && !guard.accepts(second), "response survived stop/restart");
        check(!guard.complete(second) && guard.accepts(third), "late response cleared replacement request");
        check(guard.complete(third), "failure could not release current request");
        check(!guard.isThinking(), "request remained busy after failure");
        guard.invalidate();
        guard.invalidate();
        check(!guard.isThinking() && !guard.accepts(third), "repeated stop revived response");
        long fourth = guard.begin();
        check(guard.accepts(fourth) && fourth > third, "request after repeated stop failed");
        System.out.println("PASS: " + checks + " request lifecycle assertions");
    }
}
