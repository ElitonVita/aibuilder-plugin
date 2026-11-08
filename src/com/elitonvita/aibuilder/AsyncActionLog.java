package com.elitonvita.aibuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Bounded, single-writer action log. Producers never wait for disk I/O. */
final class AsyncActionLog implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 4096;
    private static final int MAX_LINE_CHARS = 2048;
    private static final int BATCH_SIZE = 128;
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Path file;
    private final Logger logger;
    private final long maxFileBytes;
    private final int retainedFiles;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Object lifecycle = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean queueFullWarningPending = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong nextWarningAt = new AtomicLong(Long.MIN_VALUE);
    private final Thread writer;

    AsyncActionLog(Path file, Logger logger, long maxFileBytes, int retainedFiles) {
        this.file = file;
        this.logger = logger;
        this.maxFileBytes = Math.max(65_536L, Math.min(67_108_864L, maxFileBytes));
        this.retainedFiles = Math.max(1, Math.min(10, retainedFiles));
        writer = new Thread(this::writeLoop, "AIBuilder-action-log");
        writer.setDaemon(true);
        writer.start();
    }

    void log(String message) {
        String safe = message == null ? "null" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > MAX_LINE_CHARS) safe = safe.substring(0, MAX_LINE_CHARS) + " [truncated]";
        boolean full;
        synchronized (lifecycle) {
            if (closing.get()) return;
            full = !queue.offer(safe);
        }
        if (full) {
            dropped.incrementAndGet();
            queueFullWarningPending.set(true);
        }
    }

    private void writeLoop() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        while (!closing.get() || !queue.isEmpty()) {
            if (queueFullWarningPending.getAndSet(false))
                warn("AIBuilder action log queue is full; some entries are being dropped.");
            try {
                String first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                appendBatch(batch);
            } catch (InterruptedException interrupted) {
                if (closing.get()) break;
                Thread.currentThread().interrupt();
                break;
            } catch (IOException | RuntimeException failure) {
                dropped.addAndGet(Math.max(1, batch.size()));
                warn("AIBuilder could not write actions.log: " + failure.getMessage());
            }
        }
    }

    private void appendBatch(List<String> batch) throws IOException {
        StringBuilder text = new StringBuilder(batch.size() * 96);
        for (String line : batch) text.append(Instant.now()).append(' ').append(line).append('\n');
        long droppedCount = dropped.get();
        if (droppedCount > 0)
            text.insert(0, Instant.now() + " [AIBuilder][log] dropped " + droppedCount + " action log entries\n");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        rotateIfNeeded(bytes.length);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (droppedCount > 0) dropped.addAndGet(-droppedCount);
        logger.info(batch.size() == 1 ? batch.get(0) : batch.size() + " AIBuilder action log entries");
    }

    private void rotateIfNeeded(long incomingBytes) throws IOException {
        long currentBytes = Files.exists(file) ? Files.size(file) : 0;
        if (currentBytes == 0 || currentBytes + incomingBytes <= maxFileBytes) return;
        for (int index = retainedFiles; index >= 1; index--) {
            Path target = rotated(index);
            Files.deleteIfExists(target);
            Path source = index == 1 ? file : rotated(index - 1);
            if (Files.exists(source)) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path rotated(int index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    private void warn(String message) {
        long now = System.nanoTime();
        long next = nextWarningAt.get();
        if (now >= next && nextWarningAt.compareAndSet(next, now + WARNING_INTERVAL_NANOS))
            logger.warning(message);
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (!closing.compareAndSet(false, true)) return;
        }
        try {
            writer.join(2_000);
            if (writer.isAlive()) {
                writer.interrupt();
                logger.warning("AIBuilder action log did not drain within 2 seconds; pending entries may be lost.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.interrupt();
        }
    }
}
