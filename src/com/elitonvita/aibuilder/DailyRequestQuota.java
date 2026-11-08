package com.elitonvita.aibuilder;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Shared per-player AI quota. A request is accepted only after its counter is durable. */
public final class DailyRequestQuota {
    private final Path file;
    private LocalDate date;
    private Map<UUID, Integer> counts = new HashMap<>();

    public DailyRequestQuota(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        if (Files.size(file) > 2_097_152) throw new IOException("AI quota file is too large");
        try {
            Properties saved = new Properties();
            saved.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            date = LocalDate.parse(saved.getProperty("date"));
            for (String key : saved.stringPropertyNames()) {
                if (key.equals("date")) continue;
                int count = Integer.parseInt(saved.getProperty(key));
                if (count < 0) throw new IllegalArgumentException("negative counter");
                counts.put(UUID.fromString(key), count);
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid AI quota file", invalid); }
    }

    public synchronized boolean consume(UUID player, LocalDate today, int limit) throws IOException {
        if (date != null && today.isBefore(date)) throw new IOException("AI quota date is ahead of the server date");
        Map<UUID, Integer> next = today.equals(date) ? new HashMap<>(counts) : new HashMap<>();
        int used = next.getOrDefault(player, 0);
        if (limit > 0 && used >= limit) return false;
        next.put(player, used == Integer.MAX_VALUE ? used : used + 1);
        Properties saved = new Properties();
        saved.setProperty("date", today.toString());
        next.forEach((id, count) -> saved.setProperty(id.toString(), Integer.toString(count)));
        StringWriter text = new StringWriter();
        saved.store(text, "AIBuilder daily AI requests");
        if (text.toString().getBytes(StandardCharsets.UTF_8).length > 2_097_152)
            throw new IOException("AI quota file is too large");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".ai-quota-", ".tmp");
        try {
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        date = today;
        counts = next;
        return true;
    }
}
