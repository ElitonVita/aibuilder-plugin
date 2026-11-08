import com.elitonvita.aibuilder.DailyRequestQuota;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.UUID;

/** Real disk/restart/date-boundary checks; no server or HTTP. */
public final class DailyRequestQuotaTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("aibuilder-quota-");
        try {
            Path file = dir.resolve("quota.properties");
            UUID player = UUID.randomUUID(), other = UUID.randomUUID();
            LocalDate day = LocalDate.of(2026, 10, 20);
            DailyRequestQuota quota = new DailyRequestQuota(file);
            check(quota.consume(player, day, 2), "first request denied");
            quota = new DailyRequestQuota(file);
            check(quota.consume(player, day, 2), "restart lost remaining request");
            check(!new DailyRequestQuota(file).consume(player, day, 2), "restart bypasses daily cap");
            check(quota.consume(other, day, 2), "players share a counter");
            check(quota.consume(player, day.plusDays(1), 2), "new local day did not reset quota");
            check(quota.consume(player, day.plusDays(1), 0), "unlimited request denied");
            check(!new DailyRequestQuota(file).consume(player, day.plusDays(1), 2), "unlimited usage not persisted before limit change");
            try { quota.consume(player, day, 1); throw new AssertionError("clock rollback reset quota"); }
            catch (IOException expected) { checks++; }
            Files.writeString(file, "broken");
            try { new DailyRequestQuota(file); throw new AssertionError("corrupt quota accepted"); }
            catch (IOException expected) { checks++; }
            Path blocked = dir.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            quota = new DailyRequestQuota(blocked.resolve("quota.properties"));
            try { quota.consume(player, day, 1); throw new AssertionError("disk failure accepted a request"); }
            catch (IOException expected) { checks++; }
            Files.delete(blocked);
            check(quota.consume(player, day, 1), "failed write consumed quota in memory");
            check(!new DailyRequestQuota(blocked.resolve("quota.properties")).consume(player, day, 1), "retry was not persisted");
        } finally {
            try (var paths = Files.walk(dir)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("DailyRequestQuotaTest: " + checks + " checks passed");
    }
}
