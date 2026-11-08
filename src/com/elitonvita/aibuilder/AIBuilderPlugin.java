package com.elitonvita.aibuilder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Registry and routing live on the server thread. Every builder owns its mutable execution state. */
public final class AIBuilderPlugin extends JavaPlugin implements Listener {
    static final List<String> NAMES = List.of("Alfred", "Bruce", "Clark", "Diana", "Tony");
    final Map<UUID, BuilderSession> builders = new LinkedHashMap<>();
    final Map<UUID, UUID> selected = new HashMap<>();
    // Server-thread scope, shared by every builder. Event identity never exempts another event.
    private Set<BlockBreakEvent> builderBreakEvents;
    boolean isBuilderBreakEvent(BlockBreakEvent event) {
        return builderBreakEvents != null && builderBreakEvents.contains(event);
    }
    void dispatchBuilderBreak(BlockBreakEvent event) {
        if (builderBreakEvents == null) builderBreakEvents = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean added = builderBreakEvents.add(event);
        try { org.bukkit.Bukkit.getPluginManager().callEvent(event); }
        finally { if (added) builderBreakEvents.remove(event); }
    }

    DailyRequestQuota dailyQuota;
    HttpClient http;
    private AsyncActionLog actionLog;
    private ThreadPoolExecutor blueprintExecutor;
    private boolean registryHealthy = true;
    private int terrainCursor;
    private int buildQueueCursor;
    private void processTerrainJobs() {
        var sessions=List.copyOf(builders.values());
        if (sessions.isEmpty()) return;
        long deadline=System.nanoTime()+6_000_000L; // Shared six milliseconds per server tick.
        int start=Math.floorMod(terrainCursor++,sessions.size());
        var deep=sessions.stream().filter(BuilderSession::hasDeepTerrainWork).toList();
        var regular=sessions.stream().filter(builder -> !builder.hasDeepTerrainWork()).toList();
        int remaining=deepTerrainActionsPerTick();
        for(int i=0;i<deep.size() && remaining>0;i++) {
            BuilderSession builder=deep.get((start+i)%deep.size());
            int share=Math.max(129,remaining/(deep.size()-i));
            int limit=Math.min(builder.deepTerrainActionLimit(),share);
            remaining=Math.max(0,remaining-builder.processTerrain(deadline,limit));
        }
        for(int i=0;i<regular.size() && System.nanoTime()<deadline;i++)
            regular.get((start+i)%regular.size()).processTerrain(deadline);
    }
    private void processBuilderQueues() {
        var sessions=List.copyOf(builders.values());
        if (sessions.isEmpty()) return;
        int start=Math.floorMod(buildQueueCursor++,sessions.size());
        long quickDeadline=System.nanoTime()+quickBuildBudgetNanos();
        int quickCount=(int)sessions.stream().filter(BuilderSession::quickBuildMode).count();
        long perBuilderBudget=quickCount==0?0:Math.max(1_000_000L,quickBuildBudgetNanos()/quickCount);
        for(int i=0;i<sessions.size() && System.nanoTime()<quickDeadline;i++) {
            BuilderSession builder=sessions.get((start+i)%sessions.size());
            if(builder.quickBuildMode()) builder.processScheduledQueue(Math.min(quickDeadline,System.nanoTime()+perBuilderBudget));
        }
        // Ordinary builds keep their existing per-builder cadence and two millisecond budget.
        for(int i=0;i<sessions.size();i++) {
            BuilderSession builder=sessions.get((start+i)%sessions.size());
            if(!builder.quickBuildMode()) builder.processScheduledQueue();
        }
    }
    private volatile String legacyMention = "@construtor";

    private int boundedConfigInt(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(key, fallback)));
    }

    int maxUserPrebuilts() { return boundedConfigInt("prebuilt.max-user-models", 256, 1, UserPrebuilts.HARD_MAX_USER_PREBUILTS); }

    int quickBuildActionLimit() { return boundedConfigInt("construction.quick-build-actions-per-tick", 4096, 128, 8192); }
    long quickBuildBudgetNanos() { return boundedConfigInt("construction.quick-build-budget-ms-per-tick", 20, 1, 40) * 1_000_000L; }
    int deepTerrainActionsPerTick() { return boundedConfigInt("terrain.deep-actions-per-tick", 2048, 645, 8192); }

    void logAction(String message) {
        AsyncActionLog writer = actionLog;
        if (writer == null) getLogger().info(message);
        else writer.log(message);
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                boundedConfigInt("ai.connect-timeout-seconds", 15, 1, 60))).build();
    }

    /** Copia o guia, o prompt e um exemplo para prebuilts/ na primeira execucao (nunca sobrescreve). O viewer (preview/index.html) e do plugin e e atualizado a cada inicio. */
    private void installPrebuiltKit() {
        Path folder = getDataFolder().toPath().resolve(UserPrebuilts.FOLDER);
        try {
            Files.createDirectories(folder);
            for (String name : List.of("LEIA-ME.md", "PROMPT-EN.md", "ORIENTACAO-E-LIMITES.md", "LIMITES-CONFIGURAVEIS.md", "exemplo_abrigo.json.example")) {
                Path target = folder.resolve(name);
                if (Files.exists(target)) continue;
                try (var in = getResource("prebuilt-kit/" + name)) {
                    if (in != null) Files.copy(in, target);
                }
            }
            Path preview = folder.resolve("preview");
            Files.createDirectories(preview);
            try (var in = getResource("prebuilt-kit/preview/index.html")) {
                if (in != null) Files.copy(in, preview.resolve("index.html"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().warning(LocaleText.format(this, "message_2d722a47b7e0", LocaleText.text(this, e.getMessage())));
        }
    }

    ConstructionLimits constructionLimits() {
        return new ConstructionLimits(getConfig().getInt("construction.max-width",200),
            getConfig().getInt("construction.max-depth",200),getConfig().getInt("construction.max-height",200),
            getConfig().getLong("construction.max-volume",8_000_000L),getConfig().getInt("max-project-blocks",2500),
            getConfig().getInt("construction.max-parts",4096),getConfig().getInt("construction.max-openings",1024),
            getConfig().getLong("construction.max-processing-visits",128_000_000L),
            getConfig().getLong("construction.max-file-bytes",67_108_864L),getConfig().getInt("construction.preview-max-blocks",20000));
    }

    boolean submitBlueprintWork(Runnable work) {
        ThreadPoolExecutor executor = blueprintExecutor;
        if (executor == null || executor.isShutdown()) return false;
        try { executor.execute(work); return true; }
        catch (RejectedExecutionException saturated) { return false; }
    }

    private volatile String prebuiltSignature = "";

    /** Carrega os pre-moldados e atualiza a previa. Guarda a assinatura da pasta para o vigia. */
    UserPrebuilts.Load reloadPrebuilts() {
        Path data = getDataFolder().toPath();
        UserPrebuilts.Load load = BuilderSession.loadPrebuilts(data, constructionLimits(), maxUserPrebuilts());
        prebuiltSignature = UserPrebuilts.signature(data.resolve(UserPrebuilts.FOLDER));
        return load;
    }

    /** Vigia a pasta: se um JSON foi criado, editado ou apagado, recarrega sem precisar de comando. */
    private void watchPrebuilts() {
        String now = UserPrebuilts.signature(getDataFolder().toPath().resolve(UserPrebuilts.FOLDER));
        if (now.equals(prebuiltSignature)) return;
        UserPrebuilts.Load load = reloadPrebuilts();
        getLogger().info(LocaleText.format(this, "message_1e983de9c6ae", load.entries().size(), load.files()));
        load.errors().forEach(error -> getLogger().warning(LocaleText.format(this, "message_7deb53e9e45d", error)));
    }

    @Override public void onEnable() {
        getLogger().info("Compatibility: Paper 26.3 and newer.");
        saveDefaultConfig();
        LocaleText.reload(this);
        migrateTerrainDefaults();
        mergeMissingConfigDefaults();
        actionLog = new AsyncActionLog(getDataFolder().toPath().resolve("actions.log"), getLogger(),
                getConfig().getLong("logging.actions.max-file-bytes", 8_388_608L),
                getConfig().getInt("logging.actions.retained-files", 4));
        blueprintExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "AIBuilder-blueprint-compiler");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        updateInstallMetadata();
        try { dailyQuota = new DailyRequestQuota(getDataFolder().toPath().resolve("ai-daily-quota.properties")); }
        catch (IOException invalid) { getLogger().severe("AI requests blocked: cannot load daily quota: " + invalid.getMessage()); }
        legacyMention = "@" + getConfig().getString("bot-name", "Construtor").toLowerCase(Locale.ROOT);
        http = createHttpClient();
        loadBuilders(); // reserve names and slots before accepting commands
        for (Player player : getServer().getOnlinePlayers()) BuilderTools.remove(player, this::isOrphanTool);
        for (var world : getServer().getWorlds())
            for (var item : world.getEntitiesByClass(org.bukkit.entity.Item.class))
                if (isOrphanTool(item.getItemStack())) item.remove();
        installPrebuiltKit();
        UserPrebuilts.Load prebuilts = reloadPrebuilts();
        prebuilts.errors().forEach(error -> getLogger().warning(LocaleText.format(this, "message_7deb53e9e45d", error)));
        getLogger().info(LocaleText.format(this, "message_a9a0a83ac910", prebuilts.entries().size(), prebuilts.files()));
        getServer().getScheduler().runTaskTimer(this, this::watchPrebuilts, 100L, 100L);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskLater(this, () -> builders.values().forEach(BuilderSession::restoreBuilder), 20L);
        getServer().getScheduler().runTaskLater(this, () -> builders.values().forEach(BuilderSession::ensureCoreProtect), 100L);
        // Normal builders self-throttle to one queue step per four ticks; OP speed shares the terrain 6 ms/tick budget.
        getServer().getScheduler().runTaskTimer(this, this::processBuilderQueues, 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::processTerrainJobs, 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> List.copyOf(builders.values()).forEach(BuilderSession::followOwner), 40L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> builders.values().forEach(BuilderSession::showAreaMarkers), 20L, 10L);
        getServer().getScheduler().runTaskTimer(this, () -> builders.values().forEach(BuilderSession::saveBuilderRegistry), 600L, 600L);
        getLogger().info(LocaleText.format(this, "message_5cd726c85515", builders.size(), builderLimit()));
    }

    @Override public void onDisable() {
        try {
            builders.values().forEach(BuilderSession::shutdown);
        } finally {
            try {
                if (blueprintExecutor != null) blueprintExecutor.shutdownNow();
                if (actionLog != null) actionLog.close();
            } finally {
                LocaleText.forget(this);
            }
        }
    }

    private void updateInstallMetadata() {
        if (getConfig().getString("installation.date", "").isBlank())
            getConfig().set("installation.date", LocalDate.now().toString());
        String version = getDescription().getVersion();
        getConfig().set("plugin-version", version);
        try { getConfig().set("build-number", Integer.parseInt(version.substring(version.lastIndexOf('.') + 1))); }
        catch (RuntimeException ignored) { getConfig().set("build-number", 0); }
        String language = getConfig().getString("language", "EN");
        if (!language.equalsIgnoreCase("EN") && !language.equalsIgnoreCase("PT")) getConfig().set("language", "EN");
        saveConfig();
    }

    void migrateTerrainDefaults() {
        if(getConfig().contains("terrain.defaults-version",true)) return;
        // Preserve the configured value even when it matches an older default. The new
        // resource default applies only to installations whose key is absent.
        getConfig().set("terrain.defaults-version",1);
        saveConfig();
    }

    /** Atualiza configs antigos somente com folhas ausentes; valores e comentarios existentes ficam intactos. */
    private void mergeMissingConfigDefaults() {
        try (var stream = getResource("config.yml")) {
            if (stream == null) return;
            org.bukkit.configuration.file.YamlConfiguration defaults = new org.bukkit.configuration.file.YamlConfiguration();
            defaults.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || getConfig().contains(key, true)) continue;
                getConfig().set(key, defaults.get(key));
                changed = true;
            }
            if (changed) {
                saveConfig();
                getLogger().info(LocaleText.format(this, "message_af035b7be792"));
            }
        } catch (Exception error) {
            getLogger().warning(LocaleText.format(this, "message_4a0a2e57e67a", LocaleText.text(this, error.getMessage())));
        }
    }

    int builderLimit() { return Math.max(1, Math.min(5, getConfig().getInt("builders.max", 3))); }

    BuilderSession named(String name) {
        return builders.values().stream().filter(b -> b.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    boolean controls(CommandSender sender, BuilderSession builder) {
        return sender.isOp() || sender instanceof Player p && p.getUniqueId().equals(builder.ownerId);
    }

    BuilderSession selectedFor(Player player) {
        BuilderSession builder = builders.get(selected.get(player.getUniqueId()));
        if (builder != null && controls(player, builder)) return builder;
        // A player's own first builder is the default; never silently choose another owner's NPC.
        return builders.values().stream().filter(b -> player.getUniqueId().equals(b.ownerId)).min(java.util.Comparator.comparingInt(b -> NAMES.indexOf(b.name))).orElse(null);
    }

    private BuilderSession resolve(CommandSender sender) {
        if (sender instanceof Player p) return selectedFor(p);
        return builders.size() == 1 ? builders.values().iterator().next() : null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = BuilderCommands.internal(args);
        if (args == null) { tell(sender, LocaleText.format(this, "message_c0927ca51c4b")); return true; }
        if (!sender.hasPermission("aibuilder.use")) { tell(sender, LocaleText.format(this, "message_a9ac80086aa9")); return true; }
        // /construtor (ou /construtor menu) abre o menu de botoes para quem esta em jogo
        if ((args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("menu")) && sender instanceof Player menuPlayer) {
            BuilderMenu.open(this, menuPlayer, BuilderMenu.Page.MAIN, null);
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("settings") || sub.equals("config")) { settingsCommand(sender, args); return true; }
        if (sub.equals("spawn")) {
            if (!(sender instanceof Player player)) { tell(sender, LocaleText.format(this, "message_07c6d68d4778")); return true; }
            if (!registryHealthy) { tell(sender, LocaleText.format(this, "message_95e0c773fd22")); return true; }
            if (builders.size() >= builderLimit()) { tell(sender, LocaleText.format(this, "message_0f3126a43744", builderLimit())); return true; }
            String name = NAMES.stream().filter(n -> named(n) == null).findFirst().orElse(null);
            if (name == null) { tell(sender, LocaleText.format(this, "message_97c8464da806")); return true; }
            BuilderSession builder = new BuilderSession(this, UUID.randomUUID(), name, player.getUniqueId());
            builders.put(builder.id, builder);
            try {
                builder.spawnNpc(player.getLocation(), player);
                if (!builder.saveBuilderRegistry()) throw new IllegalStateException(LocaleText.format(this, "message_191b0818abfb"));
                selected.put(player.getUniqueId(), builder.id);
                tell(sender, LocaleText.format(this, "message_c4e4e035ffd2", name, name.toLowerCase(Locale.ROOT)));
            } catch (RuntimeException failure) {
                builders.remove(builder.id);
                if (builder.npc != null) builder.npc.remove();
                builder.deleteBuilderRegistry();
                tell(sender, LocaleText.format(this, "message_e0b6c53f4555", name));
                getLogger().warning(LocaleText.format(this, "message_5f08d7556e6d", LocaleText.text(this, failure.getMessage())));
            }
            return true;
        }
        if (sub.equals("listar")) {
            tell(sender, LocaleText.format(this, "message_727242359ed6", builders.size(), builderLimit()));
            for (BuilderSession b : builders.values()) if (controls(sender, b))
                tell(sender, LocaleText.format(this, "message_b422560d73bd", b.name, LocaleText.text(this, b.busyDescription()), b.name.toLowerCase(Locale.ROOT)));
            return true;
        }
        if (sub.equals("pre-moldado") && args.length == 2 && args[1].equalsIgnoreCase("recarregar")) {
            if (!sender.isOp()) { tell(sender, LocaleText.format(this, "message_f6a69cf1f672")); return true; }
            UserPrebuilts.Load load = reloadPrebuilts();
            tell(sender, LocaleText.format(this, "message_0d4af59cbc0b", load.entries().size(), maxUserPrebuilts(), load.files()));
            for (String error : load.errors()) tell(sender, error);
            return true;
        }
        if (sub.equals("reload")) {
            if (!sender.isOp()) { tell(sender, LocaleText.format(this, "message_2a48766876b8")); return true; }
            for (BuilderSession b : builders.values()) b.stopCommand(sender);
            reloadConfig();
            LocaleText.reload(this);
            if (dailyQuota == null) {
                try { dailyQuota = new DailyRequestQuota(getDataFolder().toPath().resolve("ai-daily-quota.properties")); }
                catch (IOException invalid) { getLogger().severe("AI daily quota is still unavailable: " + invalid.getMessage()); }
            }
            migrateTerrainDefaults();
            mergeMissingConfigDefaults();
            updateInstallMetadata();
            http = createHttpClient();
            reloadPrebuilts();
            legacyMention = "@" + getConfig().getString("bot-name", "Construtor").toLowerCase(Locale.ROOT);
            for (BuilderSession b : builders.values()) { b.restoreBuilder(); b.applyBuilderSkin(sender); }
            tell(sender, LocaleText.format(this, "message_6c2d5d6287b2", builderLimit()));
            return true;
        }
        // Parental limits are global, even with no NPC. One shared counter prevents bypass by switching builders.
        if (sub.equals("pais")) {
            if (!sender.isOp()) { tell(sender, LocaleText.format(this, "message_2a48766876b8")); return true; }
            new BuilderSession(this, UUID.randomUUID(), "Construtor", null).parentalCommand(sender, args);
            return true;
        }
        if (sub.equals("help") || sub.equals("ajuda")) {
            sender.sendMessage(LocaleText.text(this, LocaleText.format(this, "message_662efa954da1")));
            new BuilderSession(this, UUID.randomUUID(), "Construtor", null).helpCommand(sender);
            return true;
        }
        if ((sub.equals("chamar") || sub.equals("teleportar")) && args.length == 2 && args[1].equalsIgnoreCase("todos")) {
            if (!(sender instanceof Player player)) { tell(sender, LocaleText.format(this, "message_07c6d68d4778")); return true; }
            var mine = builders.values().stream().filter(b -> player.getUniqueId().equals(b.ownerId)).toList();
            if (mine.isEmpty()) tell(sender, LocaleText.format(this, "message_7bdbbb370711"));
            mine.forEach(b -> b.recallCommand(player));
            return true;
        }
        BuilderSession builder;
        if (sub.equals("selecionar")) {
            builder = args.length == 2 ? named(args[1]) : null;
            if (builder == null || !controls(sender, builder)) { tell(sender, LocaleText.format(this, "message_b673fcf9caec")); return true; }
            if (sender instanceof Player player) selected.put(player.getUniqueId(), builder.id);
            tell(sender, LocaleText.format(this, "message_46994a2b1221", builder.name));
            return true;
        }
        builder = named(sub);
        if (builder != null) args = Arrays.copyOfRange(args, 1, args.length);
        else {
            // A misspelled name must not send its command to the selected builder.
            if (!List.of("status", "delete", "deletar", "despawn", "sim", "confirmar", "nao", "cancelar", "zona", "projeto", "missao", "terreno", "obra", "stay", "ficar", "follow", "seguir", "chamar", "teleportar", "distancia", "orientacao", "orientação", "stop", "parar", "ferramenta", "pre-moldado", "premoldado", "settings", "config").contains(sub)) {
                tell(sender, LocaleText.format(this, "message_65277fb3b137")); return true;
            }
            builder = resolve(sender);
        }
        if (builder == null) { tell(sender, LocaleText.format(this, "message_ab8363ca14fd")); return true; }
        if (!controls(sender, builder)) { tell(sender, LocaleText.format(this, "message_d6b7b5056cdf", builder.name)); return true; }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (List.of("delete", "deletar", "despawn").contains(action)) {
            if (builder.isActive()) {
                tell(sender, LocaleText.format(this, "message_80418c12d897", builder.name, LocaleText.text(this, builder.busyDescription()), builder.name.toLowerCase(Locale.ROOT)));
                return true;
            }
            builder.stopCommand(sender);
            if (!builder.deleteBuilderRegistry()) { tell(sender, LocaleText.format(this, "message_68d4a030744c")); return true; }
            builder.removeBuilderEntities(sender);
            int tools = builder.removeWands();
            builders.remove(builder.id);
            selected.values().removeIf(builder.id::equals);
            tell(sender, LocaleText.format(this, "message_140662b65ccb", builder.name, tools));
            return true;
        }
        // Explicit name routes do not change selection; callbacks carry their own identity.
        return builder.onCommand(sender, command, label, args);
    }

    private void settingsCommand(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("show")) {
            tell(sender, LocaleText.format(this, "message_4df301a02f24", LocaleText.language(this)));
            tell(sender, LocaleText.format(this, "message_abe6f800858f"));
            tell(sender, LocaleText.format(this, "message_bdb74b3ae30e"));
            tell(sender, "builders.max, max-project-blocks, max-blocks-per-minute, terrain.deep-clean.enabled");
            tell(sender, LocaleText.format(this, "message_71487135c2fb"));
            tell(sender, LocaleText.format(this, "message_17de2dec352e"));
            tell(sender, LocaleText.format(this, "message_17d03d6cff0a"));
            return;
        }
        if (!sender.isOp()) { sender.sendMessage(LocaleText.text(this, LocaleText.format(this, "message_0add5700ff33"))); return; }
        if (args.length == 3 && args[1].equalsIgnoreCase("language")) {
            String value = args[2].toUpperCase(Locale.ROOT);
            if (!Set.of("EN", "PT").contains(value)) { sender.sendMessage(LocaleText.text(this, LocaleText.format(this, "message_97ecf79588e3"))); return; }
            getConfig().set("language", value); saveConfig();
            sender.sendMessage(LocaleText.text(this, LocaleText.format(this, "message_5a5586aa9af4", value)));
            return;
        }
        tell(sender, LocaleText.format(this, "message_125621989c4b"));
    }

    private void tell(CommandSender sender, String message) {
        sender.sendMessage(LocaleText.text(this, BuilderCommands.inMessage(LocaleText.text(this, message)).replaceAll("§[0-9A-FK-ORa-fk-or]", "")));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("aibuilder.use")) return List.of();
        List<String> names = builders.values().stream().filter(b -> controls(sender, b)).map(b -> b.name.toLowerCase(Locale.ROOT)).toList();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            var matching = BuilderUi.completions(prefix, names);
            if (!matching.isEmpty()) return matching;
            var choices = new java.util.ArrayList<>(BuilderCommands.ROOT);
            if (sender.isOp()) choices.addAll(List.of("reload", "parent"));
            choices.addAll(names); return BuilderUi.completions(prefix, choices);
        }
        if (args[0].equalsIgnoreCase("select")) return args.length == 2 ? BuilderUi.completions(args[1], names) : List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("call")) return BuilderUi.completions(args[1], List.of("all"));
        if (args[0].equalsIgnoreCase("settings")) return args.length == 2 ? BuilderUi.completions(args[1], List.of("show", "language"))
                : args.length == 3 && args[1].equalsIgnoreCase("language") ? BuilderUi.completions(args[2], List.of("EN", "PT")) : List.of();
        BuilderSession b = named(args[0]);
        if (b != null) return controls(sender, b) ? b.onTabComplete(sender, command, alias, Arrays.copyOfRange(args, 1, args.length)) : List.of();
        b = resolve(sender); return b == null ? List.of() : b.onTabComplete(sender, command, alias, args);
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        String text = event.getMessage();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String mention = text.substring(0, end).toLowerCase(Locale.ROOT);
        boolean namedMention = NAMES.stream().anyMatch(n -> mention.equals("@" + n.toLowerCase(Locale.ROOT)));
        boolean legacy = !namedMention && (mention.equals("@construtor") || mention.equals("@builder") || mention.equals(legacyMention));
        if (!legacy && !namedMention) return;
        String request = text.substring(end).trim();
        if (request.isEmpty()) return;
        event.setCancelled(true);
        getServer().getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            BuilderSession b = legacy ? selectedFor(player) : named(mention.substring(1));
            if (b == null) { tell(player, LocaleText.format(this, "message_f9ae150442a8")); return; }
            player.sendMessage(LocaleText.text(this, "<" + player.getName() + "> " + mention + " " + request));
            b.receiveRequest(player, request);
        });
    }

    @EventHandler public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BuilderMenu menu)) return;
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player && raw >= 0
                && raw < event.getView().getTopInventory().getSize()) menu.click(player, raw);
    }

    @EventHandler public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BuilderMenu) event.setCancelled(true);
    }

    @EventHandler public void onWandUse(PlayerInteractEvent event) { builders.values().forEach(b -> b.onWandUse(event)); }
    @EventHandler public void onWandBreak(BlockBreakEvent event) { builders.values().forEach(b -> b.onWandBreak(event)); }
    /** Offline inventories and unloaded containers are cleaned as soon as they become accessible. */
    boolean isOrphanTool(org.bukkit.inventory.ItemStack item) {
        if (!registryHealthy || item == null || !item.hasItemMeta()) return false;
        var data = item.getItemMeta().getPersistentDataContainer();
        if (!data.has(new org.bukkit.NamespacedKey(this, "wand"), org.bukkit.persistence.PersistentDataType.BYTE)) return false;
        String id = data.get(new org.bukkit.NamespacedKey(this, "builder-id"), org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return false; // Legacy, unbound tools have no attributable builder.
        try { return !builders.containsKey(UUID.fromString(id)); }
        catch (IllegalArgumentException invalid) { return true; }
    }
    @EventHandler public void onToolInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        BuilderTools.remove(event.getInventory(), this::isOrphanTool);
        if (event.getPlayer() instanceof Player player) BuilderTools.remove(player, this::isOrphanTool);
    }
    @EventHandler public void onToolEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (var entity : event.getEntities())
            if (entity instanceof org.bukkit.entity.Item item && isOrphanTool(item.getItemStack())) item.remove();
    }
    @EventHandler public void onToolPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (isOrphanTool(event.getItem().getItemStack())) { event.setCancelled(true); event.getItem().remove(); }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        BuilderTools.remove(event.getPlayer(), this::isOrphanTool);
        builders.values().forEach(b -> b.onJoin(event));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        for (BuilderSession b : List.copyOf(builders.values())) if (b.onQuit(event)) removeBuilder(b);
        selected.remove(event.getPlayer().getUniqueId());
    }

    /** Apaga um construtor cujo dono saiu sem trabalho pendente. Obras salvas em disco continuam no disco. */
    void removeBuilder(BuilderSession builder) {
        builder.dropQuietly();
        if (!builder.deleteBuilderRegistry()) { getLogger().warning(LocaleText.format(this, "message_e7a96efd8aba", builder.name)); return; }
        builder.removeBuilderEntities(getServer().getConsoleSender());
        builder.removeWands();
        builders.remove(builder.id);
        selected.values().removeIf(builder.id::equals);
        getLogger().info(LocaleText.format(this, "message_3dfc79f40dd4", builder.name));
    }

    void loadBuilders() {
        Path root = getDataFolder().toPath();
        try {
            Path directory = root.resolve("builders");
            if (Files.isDirectory(directory)) try (var dirs = Files.list(directory)) {
                for (Path path : dirs.sorted().toList()) {
                    Path file = path.resolve("builder.json");
                    if (!Files.isRegularFile(file)) continue;
                    JsonObject data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    UUID id = UUID.fromString(data.get("id").getAsString());
                    if (!id.toString().equals(path.getFileName().toString())) throw new IOException(LocaleText.format(this, "message_d0e00aa6a376", path.getFileName()));
                    registerRecord(data);
                }
            }
            Path legacy = root.resolve("builder.json");
            if (Files.exists(legacy)) migrateLegacy(legacy);
        } catch (IOException | RuntimeException failure) {
            registryHealthy = false;
            getLogger().severe(LocaleText.format(this, "message_951dc10df018", LocaleText.text(this, failure.getMessage())));
        }
    }

    private BuilderSession registerRecord(JsonObject data) throws IOException {
        UUID id = UUID.fromString(data.get("id").getAsString());
        String name = data.get("name").getAsString();
        if (!NAMES.contains(name) || named(name) != null || builders.containsKey(id) || builders.size() >= 5)
            throw new IOException(LocaleText.format(this, "message_966b9262fbae"));
        UUID entityId = UUID.fromString(data.get("npc").getAsString());
        if (builders.values().stream().anyMatch(b -> b.record != null && entityId.toString().equals(b.record.get("npc").getAsString())))
            throw new IOException(LocaleText.format(this, "message_9aa06f054e2a"));
        data.get("world").getAsString();
        for (String coordinate : List.of("x", "y", "z")) if (!Double.isFinite(data.get(coordinate).getAsDouble())) throw new IOException(LocaleText.format(this, "message_000ea5c89b43"));
        BuilderSession b = new BuilderSession(this, id, name, UUID.fromString(data.get("owner").getAsString()));
        b.record = data.deepCopy();
        b.restoreQuickBuildMode(data.has("quickBuildMode") && data.get("quickBuildMode").getAsBoolean());
        b.settings.set("follow-owner", !data.has("follow") || data.get("follow").getAsBoolean());
        if (data.has("travelRadius")) b.settings.set("travel-radius", Math.max(4, Math.min(64, data.get("travelRadius").getAsInt())));
        if(data.has("entranceDirection")) b.settings.set("entrance-direction", BlueprintOrientation.parse(data.get("entranceDirection").getAsString()));
        b.settings.set("recall-hold", data.has("recallHold") && data.get("recallHold").getAsBoolean());
        if (data.has("zone")) for (var entry : data.getAsJsonObject("zone").entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) throw new IOException(LocaleText.format(this, "message_e920d6ac7bef"));
            b.settings.set("zone." + entry.getKey(), value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
                    : value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : value.getAsString());
        }
        b.areaUsed = data.has("areaUsed") && data.get("areaUsed").getAsBoolean();
        b.loadProgress();
        builders.put(id, b);
        return b;
    }

    private void migrateLegacy(Path legacy) throws IOException {
        JsonObject data = JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
        // Deterministic identity makes interrupted migrations safe to retry.
        UUID id = UUID.nameUUIDFromBytes(("legacy:" + data.get("npc").getAsString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!builders.containsKey(id)) {
            data.addProperty("id", id.toString()); data.addProperty("name", "Alfred");
            data.addProperty("follow", getConfig().getBoolean("follow-owner", true));
            JsonObject zone = new JsonObject();
            var section = getConfig().getConfigurationSection("zone");
            if (section != null) for (var entry : section.getValues(false).entrySet()) zone.add(entry.getKey(), new com.google.gson.Gson().toJsonTree(entry.getValue()));
            data.add("zone", zone);
            BuilderSession b = registerRecord(data);
            try {
                Path oldBuilds = getDataFolder().toPath().resolve("builds");
                if (Files.isDirectory(oldBuilds)) try (var files = Files.list(oldBuilds)) {
                    for (Path file : files.toList()) if (Files.isRegularFile(file)) {
                        Path target = b.dataDirectory().resolve("builds").resolve(file.getFileName());
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target)) Files.copy(file, target);
                    }
                }
                Path progress = getDataFolder().toPath().resolve("progress.json");
                if (Files.isRegularFile(progress)) {
                    Files.createDirectories(b.dataDirectory());
                    Files.copy(progress, b.dataDirectory().resolve("progress.json"), StandardCopyOption.REPLACE_EXISTING);
                    b.loadProgress();
                }
                if (!b.saveBuilderRegistry()) throw new IOException(LocaleText.format(this, "message_02f689372856"));
            } catch (IOException failure) { builders.remove(id); throw failure; }
        }
        // Keep the original as a backup; it cannot resurrect a deleted migrated builder.
        Path backup = legacy.resolveSibling("builder.legacy.json");
        if (Files.exists(backup)) backup = legacy.resolveSibling("builder.legacy-" + UUID.randomUUID() + ".json");
        Files.move(legacy, backup);
    }
}
