package com.elitonvita.aibuilder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BuilderSession {

    final AIBuilderPlugin plugin;
    final UUID id;
    final String name;
    final org.bukkit.configuration.file.YamlConfiguration settings = new org.bukkit.configuration.file.YamlConfiguration();
    JsonObject record;

    BuilderSession(AIBuilderPlugin plugin, UUID id, String name, UUID owner) {
        this.plugin = plugin; this.id = id; this.name = name; this.ownerId = owner; this.ui = new BuilderUi(plugin);
        settings.set("follow-owner", getConfig().getBoolean("follow-owner", true));
        settings.set("zone.enabled", false);
    }

    org.bukkit.configuration.file.FileConfiguration getConfig() { return plugin.getConfig(); }
    org.bukkit.Server getServer() { return plugin.getServer(); }
    java.io.File getDataFolder() { return plugin.getDataFolder(); }
    java.util.logging.Logger getLogger() { return plugin.getLogger(); }
    void saveConfig() { plugin.saveConfig(); }
    String commandPrefix() { return name.equals("Construtor") ? "/builder" : "/builder " + name.toLowerCase(java.util.Locale.ROOT); }
    private NamespacedKey builderKey() { return new NamespacedKey(plugin, "builder-id"); }
    private boolean isOwnWand(ItemStack item, Player player) {
        if (!isWand(item)) return false;
        String bound = item.getItemMeta().getPersistentDataContainer().get(builderKey(), PersistentDataType.STRING);
        return bound == null ? plugin.selectedFor(player) == this : id.toString().equals(bound);
    }
    String busyDescription() {
        if (terrainWork != null) return LocaleText.format(plugin, "message_1b1382060c41", LocaleText.text(plugin, terrainStageLabel(terrainWork.stage())), terrainWork.plan() == null ? LocaleText.text(plugin, "total ainda nao conhecido") : LocaleText.format(plugin, "message_d2c5a2dbfedf", terrainWork.plan().changes().size()-terrainWork.applied()));
        if (!busy && !requests.isThinking() && pendingActions == null && queue.isEmpty())
            return activeBuild == null ? LocaleText.format(plugin, "message_b118dfe6d42a") : LocaleText.format(plugin, "message_c553f21823db", activeBuild.name);
        String stage = (requests.isThinking() || blueprintPlanning) ? LocaleText.format(plugin, "message_f1e646408c1c") : pendingActions != null ? LocaleText.format(plugin, "message_199d15481899")
                : !queue.isEmpty() ? LocaleText.format(plugin, "message_068fbcc9e661", LocaleText.text(plugin, describe(queue.peek()))) : LocaleText.format(plugin, "message_e45ae2ca0a25");
        String work = activeBuild != null ? LocaleText.format(plugin, "message_ccd19c381fc0", activeBuild.name) : pendingBuild != null ? LocaleText.format(plugin, "message_ccd19c381fc0", pendingBuild.name) : "";
        int remaining = pendingActions != null ? pendingActions.size() : queue.size();
        return LocaleText.format(plugin, "builder.busy", stage, work, remaining,
                requests.isThinking() ? LocaleText.text(plugin, " (total ainda nao conhecido)") : "",
                waitingReason.isEmpty() ? "." : "; " + LocaleText.text(plugin, waitingReason) + ".");
    }


    boolean hasPendingPlan() { return pendingActions != null; }

    boolean quickBuildMode() { return quickBuildMode; }
    int quickBuildLimit() { return plugin.quickBuildActionLimit(); }
    void restoreQuickBuildMode(boolean enabled) { quickBuildMode = enabled; }

    boolean hasDeepTerrainWork() { return terrainWork != null && terrainWork.mode().deep(); }
    int deepTerrainActionLimit() { return Math.max(129,Math.min(4096,getConfig().getInt("terrain.deep-changes-per-step",512))); }

    boolean followsOwner() { return settings.getBoolean("follow-owner", true); }

    ConstructionLimits constructionLimits() { return plugin.constructionLimits(); }

    String entranceDirection() { return BlueprintOrientation.parse(settings.getString("entrance-direction")); }

    private void orientationCommand(CommandSender sender, String[] args) {
        if(args.length==1) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_92d01ecc4c67", LocaleText.text(plugin, BlueprintOrientation.label(entranceDirection()))))); return;
        }
        String direction=args.length==2?BlueprintOrientation.parse(args[1]):null;
        boolean original=args.length==2&&args[1].equalsIgnoreCase("original");
        if(direction==null&&!original) { sender.sendMessage(msg(LocaleText.format(plugin, "message_87e4107bb791")));return; }
        if(isActive()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_b6355c2dc6bd")));return; }
        String previous=settings.getString("entrance-direction");
        settings.set("entrance-direction",direction);
        if(!saveBuilderRegistry()) {settings.set("entrance-direction",previous);sender.sendMessage(msg(LocaleText.format(plugin, "message_ccd22564dba3")));return;}
        sender.sendMessage(msg(LocaleText.format(plugin, "message_a1f7a5a5e2ff", LocaleText.text(plugin, BlueprintOrientation.label(direction)))));
    }

    int travelRadius() { return Math.max(4, Math.min(64, settings.getInt("travel-radius", getConfig().getInt("travel-radius", 32)))); }
    boolean recalled() { return settings.getBoolean("recall-hold", false); }

    private boolean withinTravelLimit(Location location) {
        Player owner = ownerPlayer();
        if (owner != null) return BuilderTravel.within(location, owner.getLocation(), travelRadius());
        // Dono offline: o raio de trabalho fica ancorado no ponto em que ele saiu.
        return ownerLeftAt != null && location.getWorld() != null && location.getWorld().equals(ownerLeftAt.getWorld())
                && BuilderTravel.within(location, ownerLeftAt, travelRadius());
    }

    /** Durante uma obra o caminho deixa de depender da posicao do dono, mas continua no mundo e na borda atuais. */
    private boolean insideWorkMovementBounds(Location location) {
        return location != null && location.getWorld() != null && npc != null && !npc.isDead()
                && location.getWorld().equals(npc.getWorld())
                && location.getWorld().getWorldBorder().isInside(location);
    }

    /** Resumo de status para o menu (mesmas informacoes de /construtor status, em linhas curtas). */
    List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("§7" + LocaleText.format(plugin, "message_5c27be047684", ownerId == null ? "?" : String.valueOf(getServer().getOfflinePlayer(ownerId).getName())));
        out.add("§7" + LocaleText.format(plugin, "message_7670d3dd0d84", LocaleText.text(plugin, busyDescription())));
        out.add("§7Seguir o criador: " + (followsOwner() ? "§asim" : "§cnao"));
        out.add("§7" + LocaleText.format(plugin, "message_0c94b68b8fa3", travelRadius()));
        out.add("§7" + LocaleText.format(plugin, "message_0d46df35f82d", LocaleText.text(plugin, BlueprintOrientation.label(entranceDirection()))));
        if (recalled()) out.add("§e" + LocaleText.format(plugin, "message_b6fd93613f8a"));
        out.add("§7Plano pendente: " + (pendingActions != null ? "§esim" : "§fnao"));
        out.add("§7" + LocaleText.format(plugin, "message_d6b389fb6ce0", activeBuild == null ? "nenhuma" : activeBuild.name));
        out.add("§7" + LocaleText.format(plugin, "message_e45b8d1a844f", LocaleText.format(plugin, quickBuildMode ? "message_fb9cf75606b4" : "message_17eb3c0168d0")));
        out.add("§7Zona marcada: " + (settings.getBoolean("zone.enabled", false) ? "§asim" : "§cnao"));
        if (npc != null && !npc.isDead()) out.add("§7" + LocaleText.format(plugin, "message_fea13205221f", coords(npc.getLocation()), npc.getWorld().getName()));
        return out;
    }

    // Natural terrain the bot may remove to level the ground. Nothing else is breakable.
    private static final Set<Material> BREAKABLE = EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.STONE);

    // Blocks the bot must never place.
    private static final Set<Material> FORBIDDEN_PLACE = EnumSet.of(
            Material.TNT, Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
            Material.BEDROCK, Material.SPAWNER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID,
            Material.JIGSAW, Material.BARRIER, Material.END_PORTAL_FRAME, Material.TRIAL_SPAWNER
    );

    private static final int MAX_AI_ACTIONS_HARD = 10_000;

    private final Gson gson = new Gson();
    Mannequin npc;
    UUID ownerId;
    private final Deque<JsonObject> queue = new ArrayDeque<>();
    private final ActionBudget actionBudget = new ActionBudget();
    private static final int BUILD_AUTOSAVE_BLOCKS = 1000;
    private boolean quickBuildMode;
    private double quickHoverTargetY=Double.NaN;
    private Location selfEscapeOrigin;
    private long selfEscapeRetryAt;
    private Location movementWaitOrigin;
    private long movementWaitSince;
    private String movementWaitAction = "";
    private int queueTickOffset;
    private Build lastCheckpointBuild;
    private int lastCheckpointPlaced;
    private long checkpointRetryAfter;
    private long checkpointWarningAfter;
    private final Map<UUID, List<JsonObject>> history = new HashMap<>();
    // CoreProtect is used via reflection: its main class is not visible to this plugin's classloader.
    private Object coreProtect;
    private boolean busy = false;
    private boolean blueprintPlanning;
    private long blueprintPlanningGeneration;
    /** Onde o dono saiu; limita o trabalho enquanto ele esta offline. Nulo se ele esta online. */
    private Location ownerLeftAt = null;
    private final BuilderUi ui;
    private final NpcWalker walker = new NpcWalker();
    private String movementNotice = "";
    private final RequestGuard requests = new RequestGuard();
    private JsonObject pendingArea;
    private Build pendingBuild;
    // Runtime proof generated by full-plan validation; model-supplied flags are stripped.
    private String validatedGardenWorld = "";
    private Map<CatalogGardenPolicy.Point, CatalogGardenPolicy.Cell> validatedGardenCells = Map.of();
    private Map<CatalogGardenPolicy.Point, Set<CatalogGardenPolicy.Point>> validatedGardenBasins = Map.of();
    // plano de incremento: acoes adicionadas a obra aberta (activeBuild), nao uma obra nova
    private boolean pendingIncrement = false;
    private String pendingRequest = "";
    private long pendingId = 0L;
    private final UUID confirmationEpoch = UUID.randomUUID();
    private String confirmationToken() { return id + ":" + confirmationEpoch + ":" + pendingId; }
    private String waitingReason = "";
    private long nextMovementAttemptAt;
    private String activeCancelToken = "";
    private final Map<String, Integer> rejectionCounts = new java.util.TreeMap<>();
    private int planOk = 0, planRejected = 0;
    private int placedBlocks, removedBlocks, existingBlocks;
    private long nextProgressAt, nextExistingNoticeAt;

    // plano aguardando confirmacao do criador
    private List<JsonObject> pendingActions = null;
    private String pendingSummary = "";
    private long pendingExpires = 0L;

    // primeiro canto da zona, enquanto o criador define o segundo
    private Location zonePos1 = null;
    private long areaPreviewUntil = 0L;

    private void keepAreaPreview(Player player) {
        if (ownerId != null && ownerId.equals(player.getUniqueId())) {
            areaPreviewUntil = System.currentTimeMillis() + 60_000L;
        }
    }
    // obra ativa: planta compilada que o construtor deve completar e conferir
    private static final class Build {
        final String name;
        final String world;
        final List<BlueprintCompiler.Block> blocks;
        final JsonObject area;
        final Map<String, String> blockData = new HashMap<>();
        final Map<String, String> materialsByPosition = new HashMap<>();
        final Set<String> placed = new java.util.LinkedHashSet<>();
        Map<CatalogGardenPolicy.Point,CatalogGardenPolicy.Cell> gardenCells;
        Map<CatalogGardenPolicy.Point,Set<CatalogGardenPolicy.Point>> gardenBasins;
        String catalogId; // Server-assigned only; never accepted from an LLM action.
        boolean catalogFreeMaterials = true; // Captured at proposal time; legacy catalog builds remain free.
        Set<String> catalogPlacements; // Catalog authority never extends to later AI increments.
        // pedidos de incremento ja executados (para a IA saber o que ja existe)
        final List<String> steps = new ArrayList<>();
        final Set<String> preparationTargets = new java.util.HashSet<>();
        Build(String name, String world, List<BlueprintCompiler.Block> blocks, JsonObject area) {
            this.name = name; this.world = world; this.blocks = new ArrayList<>(blocks); this.area = area.deepCopy();
            for (var block : blocks) {
                String key=block.x()+","+block.y()+","+block.z();
                materialsByPosition.put(key,block.material());
                if(block.data()!=null)blockData.put(key,block.data());
            }
        }
        /** Copia da obra com os blocos de um incremento aplicados (substitui os que estao na mesma posicao). */
        Build withIncrement(List<JsonObject> actions, String request) {
            Build next = new Build(name, world, blocks, area);
            next.preparationTargets.addAll(preparationTargets);
            next.catalogId = catalogId;
            next.catalogFreeMaterials = catalogFreeMaterials;
            if (catalogId != null) next.catalogPlacements = new java.util.HashSet<>(
                    catalogPlacements == null ? materialsByPosition.keySet() : catalogPlacements);
            next.blockData.putAll(blockData);
            next.placed.addAll(placed);
            next.steps.addAll(steps);
            for (JsonObject a : actions) {
                String type = a.get("type").getAsString();
                if (!Set.of("place", "place_project", "replace").contains(type)) continue;
                int x = a.get("x").getAsInt(), y = a.get("y").getAsInt(), z = a.get("z").getAsInt();
                String key = x + "," + y + "," + z;
                if (next.catalogPlacements != null) next.catalogPlacements.remove(key);
                next.blocks.removeIf(b -> b.x() == x && b.y() == y && b.z() == z);
                next.blockData.remove(key);
                next.materialsByPosition.put(key,a.get("material").getAsString());
                next.blocks.add(new BlueprintCompiler.Block(x, y, z, a.get("material").getAsString(), 0));
                if (a.has("data")) next.blockData.put(key, a.get("data").getAsString());
            }
            next.steps.add(request);
            next.gardenCells = BuilderSession.gardenCells(next);
            try { next.gardenBasins = CatalogGardenPolicy.basinIndex(next.gardenCells); }
            catch (IllegalArgumentException invalid) { next.gardenBasins = null; }
            return next;
        }
        boolean hasBlock(int x, int y, int z) {
            return occupies(x, y, z);
        }
        private Set<Long> index; // celulas da obra; construida na primeira consulta (blocks so muda antes disso)
        /** Celula que a obra ocupa: indice O(1) para os caminhos e alcance do construtor. */
        boolean occupies(int x, int y, int z) {
            if (index == null) { index = new java.util.HashSet<>(); for (var b : blocks) index.add(key(b.x(), b.y(), b.z())); }
            return index.contains(key(x, y, z));
        }
        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        }
    }

    private Build activeBuild = null;
    private boolean undoInProgress = false;
    private Player undoActor;
    // Mantido para ler registros legados; nao bloqueia reutilizacao da selecao.
    boolean areaUsed = false; // Legacy persisted flag; a valid selection is reusable.
    private String selectionRequest;
    private UUID selectionRequester;
    private long selectionExpires;

    private int boundedInt(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(key, fallback)));
    }

    private double boundedDouble(String key, double fallback, double min, double max) {
        double configured = getConfig().getDouble(key, fallback);
        return Double.isFinite(configured) ? Math.max(min, Math.min(max, configured)) : fallback;
    }

    private int planTimeoutSeconds() { return boundedInt("planning.confirmation-timeout-seconds", 120, 15, 900); }
    private long planTimeoutMillis() { return planTimeoutSeconds() * 1000L; }
    private int selectionTimeoutSeconds() { return boundedInt("planning.selection-timeout-seconds", 600, 30, 3600); }
    private long selectionTimeoutMillis() { return selectionTimeoutSeconds() * 1000L; }
    private int historyMessageLimit() { return boundedInt("conversation.history-messages", 6, 2, 20) & ~1; }
    private int actionReach() { return boundedInt("movement.action-reach", 12, 4, 16); }
    private int moveTargetRange() { return boundedInt("movement.max-target-distance", 40, 8, 64); }
    private int maxAiActions() { return boundedInt("construction.max-ai-actions", 250, 1, MAX_AI_ACTIONS_HARD); }


    // regiao de limpeza marcada com a pa (antes de /construtor terreno limpar sim)
    private Location clearPos1 = null, clearPos2 = null;
    private boolean awaitingClearRegion = false;
    private TerrainGrid.Mode selectionMode = TerrainGrid.Mode.CLEAR;
    private TerrainWork terrainWork;
    private String terrainToken;
    private CommandSender terrainActor;
    private JsonObject terrainArea;
    private String terrainNotice = "";
    private long terrainUiAt;
    private int terrainProtectionDenials, terrainListenerChanges, terrainOtherSkips;
    private final Map<String, TerrainGrid.Cell> terrainCells = new HashMap<>();
    // cantos de um projeto a salvar
    private Location projCorner1 = null, projCorner2 = null;

    // missoes: indice da missao atual e blocos ja construidos nela
    private int missionIndex = 0;
    private int missionProgress = 0;

    void ensureCoreProtect() {
        if (coreProtect == null) coreProtect = loadCoreProtectApi();
        if (coreProtect == null) {
            if (getConfig().getBoolean("allow-break-without-coreprotect-in-selection", true)) {
                getLogger().warning(LocaleText.format(plugin, "message_49427cfbcc48"));
            } else {
                getLogger().warning(LocaleText.format(plugin, "message_0501879a0d31"));
            }
        } else {
            getLogger().info(LocaleText.format(plugin, "message_c70b3445703f"));
        }
    }

    private Object loadCoreProtectApi() {
        try {
            Plugin cp = getServer().getPluginManager().getPlugin("CoreProtect");
            if (cp == null || !cp.isEnabled()) return null;
            Object api = cp.getClass().getMethod("getAPI").invoke(cp);
            boolean enabled = (boolean) api.getClass().getMethod("isEnabled").invoke(api);
            return enabled ? api : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning(LocaleText.format(plugin, "message_fc424bd753b8", e));
            return null;
        }
    }

    void shutdown() {
        blueprintPlanning = false;
        blueprintPlanningGeneration++;
        cancelTerrain(LocaleText.format(plugin, "message_045585be1155"));
        invalidateRequest();
        walker.stop(npc);
        if(npc!=null && !npc.isDead()) { walker.land(npc); npc.setGravity(true); }
        if (activeBuild != null) saveBuild(activeBuild, queue.isEmpty() ? "aberta" : "parada");
        saveBuilderRegistry();
        ui.hideAll();
        // Keep the persistent entity: restart reattaches it by UUID / stable builder tag.
    }

    // ---------- comandos ----------

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "status";
        if (!sender.hasPermission("aibuilder.use")) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_a9ac80086aa9"))); return true; }
        if (!Set.of("status", "help", "ajuda", "missao").contains(sub)
                && sender instanceof Player player && ownerId != null
                && !player.getUniqueId().equals(ownerId) && !sender.isOp()) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_ee14e5ea05fa"))); return true;
        }
        if (Set.of("reload", "pais").contains(sub) && !sender.isOp()) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_80e3b3361240"))); return true;
        }
        switch (sub) {
            case "sim", "confirmar" -> {
                if (matchesPending(sender, args)) confirmPlan(sender);
            }
            case "nao", "cancelar" -> {
                // Untokenized command always interrupts this builder. Old chat buttons
                // remain bound to their own preview / running operation.
                if (args.length == 1) { stopCommand(sender); return true; }
                if (pendingActions == null && terrainWork == null && !queue.isEmpty()
                        && args[1].equals(activeCancelToken)) { stopCommand(sender); return true; }
                if (matchesPending(sender, args)) {
                    clearPending(); invalidateRequest();
                    sender.sendMessage(msg(LocaleText.format(plugin, "message_e2c80c8fb962")));
                }
            }
            case "zona" -> zoneCommand(sender, args.length > 1 ? args[1].toLowerCase() : "mostrar");
            case "projeto" -> projectCommand(sender, args);
            case "missao" -> sender.sendMessage(msg(missionStatus()));
            case "terreno" -> terrainCommand(sender, args);
            case "obra" -> buildCommand(sender, args);
            case "help", "ajuda" -> helpCommand(sender);
            case "stay", "ficar" -> followMode(sender, false);
            case "follow", "seguir" -> followMode(sender, true);
            case "chamar", "teleportar" -> recallCommand(sender);
            case "distancia" -> distanceCommand(sender, args);
            case "orientacao", "orientação" -> orientationCommand(sender, args);
            case "stop", "parar" -> stopCommand(sender);
            case "ferramenta" -> { if (sender instanceof Player fp) giveWand(fp); else sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_07c6d68d4778"))); }
            case "pre-moldado", "premoldado" -> prebuiltCommand(sender, args);
            case "pais" -> parentalCommand(sender, args);
            default -> statusCommand(sender);
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("aibuilder.use")) return List.of();
        List<String> choices = args.length <= 1 ? BuilderCommands.ROOT : args.length == 2 ? switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "zone" -> List.of("set1", "set2", "show", "clear");
            case "orientation" -> List.of("north", "south", "east", "west", "original");
            case "distance" -> List.of("4", "8", "16", "32", "64");
            case "terrain" -> List.of("inspect", "clean", "level", "deep", "cancel");
            case "project" -> List.of("corner1", "corner2", "save", "list", "place");
            case "build" -> sender.isOp() ? List.of("continue", "finish", "undo", "save", "speed") : List.of("continue", "finish", "undo", "save");
            case "prebuilt" -> {
                List<String> ids = new ArrayList<>(List.of("list"));
                if (sender.isOp()) ids.add("reload"); PrebuiltCatalog.all().forEach(e -> ids.add(e.id())); yield ids;
            }
            case "parent" -> sender.isOp() ? List.of("hours", "limit", "history") : List.of();
            default -> List.of();
        } : args.length == 3 && args[0].equalsIgnoreCase("build") && args[1].equalsIgnoreCase("speed") && sender.isOp() ? List.of("on","off")
            : args.length == 3 && args[0].equalsIgnoreCase("prebuilt") && PrebuiltCatalog.find(args[1]) != null ? List.of("north","south","east","west")
            : args.length == 3 && args[0].equalsIgnoreCase("terrain") && args[1].equalsIgnoreCase("deep") ? List.of("clean","level")
            : args.length == 3 && args[0].equalsIgnoreCase("terrain") && Set.of("clean","level").contains(args[1].toLowerCase(java.util.Locale.ROOT)) ? List.of("yes","select")
            : args.length == 4 && args[0].equalsIgnoreCase("terrain") && args[1].equalsIgnoreCase("deep") && Set.of("clean","level").contains(args[2].toLowerCase(java.util.Locale.ROOT)) ? List.of("yes","select") : List.of();
        return BuilderUi.completions(args.length == 0 ? "" : args[args.length - 1], choices);
    }

    /**
     * Dono saiu. Terreno (exige o dono presente) e plano ainda nao confirmado sao cancelados; a fila de acoes
     * continua sem o dono. Retorna true se nao ha trabalho e o construtor deve ser apagado agora.
     */
    public boolean onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        ui.hide(leaving);
        history.remove(leaving.getUniqueId());
        if (!leaving.getUniqueId().equals(ownerId)) return false;
        cancelTerrain(LocaleText.format(plugin, "message_49fea6a8a844"));
        invalidateRequest(); clearPending();
        ownerLeftAt = leaving.getLocation();
        return !isActive() && !waitingMaterials();
    }

    /** Obra pausada por falta de materiais com o dono offline: o construtor fica no mundo ate o dono voltar. */
    boolean waitingMaterials() {
        return record != null && record.has("waitingMaterials") && record.get("waitingMaterials").getAsBoolean();
    }

    private void setWaitingMaterials(boolean value) {
        JsonObject r = record == null ? new JsonObject() : record;
        r.addProperty("waitingMaterials", value);
        record = r;
        saveBuilderRegistry();
    }

    /**
     * Dono offline e sem materiais (bau vazio, fora do criativo): salva a obra como inacabada, esvazia a fila
     * e deixa o construtor no mundo. Retomar com "obra continuar" ou pelo menu, quando o dono voltar.
     */
    private void pauseOfflineForMaterials() {
        boolean hadBuild = activeBuild != null;
        if (hadBuild && !saveBuild(activeBuild, "parada")) {
            log("pause", LocaleText.format(plugin, "message_6e7dd5bb20b2", activeBuild.name));
            return;
        }
        if (hadBuild) log("pause", LocaleText.format(plugin, "message_bd354cd134fd", activeBuild.name));
        activeBuild = null;
        queue.clear(); busy = false; waitingReason = "";
        walker.stop(npc); walker.land(npc);
        setWaitingMaterials(hadBuild);
    }

    /** Apaga o construtor sem mensagens (dono ausente e sem trabalho). Obra aberta e salva antes. */
    void dropQuietly() {
        if (activeBuild != null) saveBuild(activeBuild, "parada");
        activeBuild = null;
        invalidateRequest(); clearPending(); queue.clear(); busy = false;
        walker.stop(npc);
        ui.hideAll();
    }

    /** Para o construtor: esvazia a fila, cancela o plano pendente e pausa a obra (fica salva). */
    void stopCommand(CommandSender sender) {
        cancelTerrain(LocaleText.format(plugin, "message_bce7114ce3d6", sender.getName()));
        awaitingClearRegion = false;
        int acoes = queue.size();
        boolean tinhaPlano = pendingActions != null;
        queue.clear();
        activeCancelToken = ""; nextMovementAttemptAt = 0;
        clearPending(); invalidateRequest(); busy = false; waitingReason = "";
        walker.stop(npc);
        ui.hideAll();
        String obra = "";
        if (activeBuild != null) {
            boolean saved = saveBuild(activeBuild, "parada");
            obra = " Obra " + activeBuild.name + (saved ? " pausada e salva." : " pausada, mas NAO foi salva. Confira o disco antes de sair.");
            if (saved) activeBuild = null;
        }
        log("stop", LocaleText.format(plugin, "message_1ee37850f49c", sender.getName(), acoes));
        sender.sendMessage(msg("parei. " + acoes + " acoes da fila canceladas"
                + (tinhaPlano ? ", plano pendente descartado" : "") + "." + obra));
        if (activeBuild == null) sender.sendMessage(msg(settings.getBoolean("follow-owner", true)
                ? LocaleText.format(plugin, "message_528df5f9c593") : LocaleText.format(plugin, "message_a5ad454eb0ad")));
    }

    void helpCommand(CommandSender sender) {
        {
            for (String line : List.of(
                    LocaleText.format(plugin, "message_e8daebb1a0b5"),
                    LocaleText.format(plugin, "message_63864a2e4510"),
                    LocaleText.format(plugin, "message_742037e49fcd"),
                    LocaleText.format(plugin, "message_8db1f672347e"),
                    LocaleText.format(plugin, "message_ac8c2e152a10"),
                    LocaleText.format(plugin, "message_98e22e011a0a"),
                    LocaleText.format(plugin, "message_cc38a4c3a49c"),
                    LocaleText.format(plugin, "message_55c226882ff1"),
                    LocaleText.format(plugin, "message_b78bef9ac58f"),
                    LocaleText.format(plugin, "message_0925897885a6"),
                    LocaleText.format(plugin, "message_0c1afc1668dd"),
                    LocaleText.format(plugin, "message_4fb2c111899d"),
                    LocaleText.format(plugin, "message_6cb7bb459d06"),
                    LocaleText.format(plugin, "message_ae353da808d0")))
                sender.sendMessage(LocaleText.text(plugin, line));
            return;
        }

    }

    void statusCommand(CommandSender sender) {
        if (npc == null || npc.isDead()) {
            sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_2649c9d74acf")));
            return;
        }
        Location l = npc.getLocation();
        sender.sendMessage(LocaleText.format(plugin, "builder.position", name, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), queue.size(),
                LocaleText.text(plugin, pendingActions == null ? "nao" : "sim"), LocaleText.text(plugin, zoneDescription())));
        sender.sendMessage(msg(LocaleText.format(plugin, "message_03d6fd241e0b", LocaleText.text(plugin, settings.getBoolean("follow-owner", true) ? "Follow" : "Stay"))
                + (movementNotice.isEmpty() ? "" : " | " + movementNotice)));
        sender.sendMessage(msg(LocaleText.format(plugin, "message_99b7e26da3b6", travelRadius())
                + (recalled() ? LocaleText.format(plugin, "message_26a6c2b1af59") : "")));
        sender.sendMessage(msg(LocaleText.format(plugin, "message_29e289bc3fd2", LocaleText.text(plugin, BlueprintOrientation.label(entranceDirection())))));
        if (sender instanceof Player player) BuilderUi.followControls(plugin, player, settings.getBoolean("follow-owner", true), commandPrefix());
        if (terrainWork != null) sender.sendMessage(msg(busyDescription()));
        if (selectionRequest != null) sender.sendMessage(msg(LocaleText.format(plugin, "message_a16b6d42758f", selectionRequest)));
        if (requests.isThinking() || blueprintPlanning) sender.sendMessage(msg(LocaleText.format(plugin, "message_24acbf5d5a6f")));
        if (!waitingReason.isEmpty()) sender.sendMessage(msg(waitingReason));
        if (activeBuild != null) sender.sendMessage(msg(LocaleText.format(plugin, "message_21009abe4d6b", activeBuild.name)));
    }

    private void invalidateRequest() {
        requests.invalidate();
        clearSelectionRequest();
        blueprintPlanning = false;
        blueprintPlanningGeneration++;
    }

    private void clearSelectionRequest() {
        selectionRequest = null; selectionRequester = null; selectionExpires = 0;
    }

    private boolean awaitSelection(Player player, String request) {
        if (settings.getBoolean("zone.enabled", false) && zonePos1 == null && !awaitingClearRegion) return false;
        selectionRequest = request;
        selectionRequester = player.getUniqueId();
        selectionExpires = System.currentTimeMillis() + selectionTimeoutMillis();
        player.sendMessage(BuilderUi.prompt(msg(LocaleText.format(plugin, "message_78e12244681b", request, name, commandPrefix(), commandPrefix(), selectionTimeoutSeconds()))));
        return true;
    }

    private void resumeAfterSelection(Player player) {
        if (selectionRequest == null || !player.getUniqueId().equals(selectionRequester)) return;
        if (System.currentTimeMillis() > selectionExpires) {
            clearSelectionRequest(); player.sendMessage(msg(LocaleText.format(plugin, "message_4be0d7762b03"))); return;
        }
        String request = selectionRequest;
        clearSelectionRequest(); // Exactly once, including failures or permission changes.
        player.sendMessage(msg(LocaleText.format(plugin, "message_082977e9f81d", request)));
        receiveRequest(player, request);
    }

    private void clearPending() {
        pendingActions = null; pendingBuild = null; pendingArea = null; pendingIncrement = false; pendingRequest = "";
        pendingSummary = ""; pendingExpires = 0L; pendingId++;
    }

    private boolean matchesPending(CommandSender sender, String[] args) {
        if (pendingActions == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_9ad6e71ef38f"))); return false; }
        if (args.length > 1 && !args[1].equals(confirmationToken())) {
            refuse(sender, LocaleText.format(plugin, "message_2cf56ecdb2bc"), LocaleText.format(plugin, "message_71a3af891942")); return false;
        }
        return true;
    }

    private void showPending(Player player) {
        try { validateActions(pendingActions, pendingArea); }
        catch (RuntimeException invalid) { clearPending(); player.sendMessage(msg(LocaleText.format(plugin, "message_8e59bc2f362d", LocaleText.text(plugin, invalid.getMessage())))); return; }
        pendingId++;
        String dimensions = (pendingArea.get("x2").getAsInt() - pendingArea.get("x1").getAsInt() + 1)
                + " x " + (pendingArea.get("y2").getAsInt() - pendingArea.get("y1").getAsInt() + 1)
                + " x " + (pendingArea.get("z2").getAsInt() - pendingArea.get("z1").getAsInt() + 1);
        BuilderUi.confirmation(plugin, player, name, LocaleText.format(plugin, "message_b3a8b88d537a", pendingSummary, dimensions), materialsLine(pendingActions), commandPrefix() + " yes " + confirmationToken(), getConfig().getBoolean("ui.native-dialogs", true));
        previewArea(player, pendingArea);
    }

    private String blockKey(JsonObject action) {
        return action.get("x").getAsInt() + "," + action.get("y").getAsInt() + "," + action.get("z").getAsInt();
    }

    private long boundedVolume(long dx, long dy, long dz, long limit) {
        if (dx < 1 || dy < 1 || dz < 1 || dx > limit || dy > limit / dx || dz > limit / (dx * dy)) return -1L;
        return dx * dy * dz;
    }

    private void validateArea(JsonObject area) {
        World world = getServer().getWorld(area.get("world").getAsString());
        if (world == null) throw new IllegalArgumentException(LocaleText.format(plugin, "message_e1679cc17f44"));
        int x1 = area.get("x1").getAsInt(), x2 = area.get("x2").getAsInt();
        int y1 = area.get("y1").getAsInt(), y2 = area.get("y2").getAsInt();
        int z1 = area.get("z1").getAsInt(), z2 = area.get("z2").getAsInt();
        if (Math.abs((long)x1) > 29_999_984 || Math.abs((long)x2) > 29_999_984
                || Math.abs((long)z1) > 29_999_984 || Math.abs((long)z2) > 29_999_984
                || y1 < world.getMinHeight() || y2 >= world.getMaxHeight()
                || !constructionLimits().fits((long)x2-x1+1,(long)y2-y1+1,(long)z2-z1+1)
                || boundedVolume((long)x2 - x1 + 1, (long)y2 - y1 + 1, (long)z2 - z1 + 1, constructionLimits().volume()) < 0)
            throw new IllegalArgumentException(LocaleText.format(plugin, "message_ba53b92b6473", constructionLimits().volume()));
    }

    /** Resolve the complete planned garden geometry before granting any per-action water proof. */
    private void validateGardenActions(List<JsonObject> actions, JsonObject area, Build candidate) {
        Map<CatalogGardenPolicy.Point, CatalogGardenPolicy.Cell> cells = candidate == null
                ? new HashMap<>() : new HashMap<>(gardenCells(candidate));
        for (JsonObject action : actions) {
            action.remove("_aibuilder_contained_water"); // Never trust model-supplied placement authority.
            if (!action.has("type") || !action.has("x") || !action.has("y") || !action.has("z")
                    || !action.get("x").isJsonPrimitive() || !action.get("x").getAsJsonPrimitive().isNumber()
                    || !action.get("y").isJsonPrimitive() || !action.get("y").getAsJsonPrimitive().isNumber()
                    || !action.get("z").isJsonPrimitive() || !action.get("z").getAsJsonPrimitive().isNumber()) continue;
            String type = action.get("type").getAsString();
            if (type.equals("move")) continue;
            var point = new CatalogGardenPolicy.Point(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
            if (type.equals("break")) {
                cells.remove(point);
            } else if (type.startsWith("place") || type.equals("replace")) {
                if (action.has("material") && action.get("material").isJsonPrimitive()) {
                    String material = action.get("material").getAsString();
                    cells.put(point, new CatalogGardenPolicy.Cell(material,
                            action.has("data") ? action.get("data").getAsString() : null));
                }
            }
        }
        String problem = CatalogGardenPolicy.problem(cells);
        if (problem != null) throw new IllegalArgumentException(problem);
        Map<CatalogGardenPolicy.Point, Set<CatalogGardenPolicy.Point>> basins = CatalogGardenPolicy.basinIndex(cells);
        validatedGardenWorld = area.get("world").getAsString();
        validatedGardenCells = Map.copyOf(cells);
        validatedGardenBasins = basins;
        if (candidate != null && candidate.gardenBasins == null)
            candidate.gardenBasins = CatalogGardenPolicy.basinIndex(gardenCells(candidate));
        for (JsonObject action : actions) {
            if (!action.has("type") || !(action.get("type").getAsString().equals("place")
                    || action.get("type").getAsString().equals("place_project"))
                    || !action.has("material") || !"WATER".equals(action.get("material").getAsString())
                    || !action.has("x") || !action.has("y") || !action.has("z")) continue;
            var point = new CatalogGardenPolicy.Point(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
            if (basins.containsKey(point)) action.addProperty("_aibuilder_contained_water", true);
        }
    }

    private void validateActions(List<JsonObject> actions, JsonObject area) {
        validateActions(actions, area, pendingBuild != null ? pendingBuild : activeBuild);
    }

    private void validateActions(List<JsonObject> actions, JsonObject area, Build candidate) {
        if (area == null || actions == null || actions.isEmpty()) throw new IllegalArgumentException(LocaleText.format(plugin, "message_2d6fd5df8ae5"));
        validateArea(area);
        World world = getServer().getWorld(area.get("world").getAsString());
        if (actions.size() > constructionLimits().blocks())
            throw new IllegalArgumentException(LocaleText.format(plugin, "message_35ca5a2f7766"));
        validateGardenActions(actions, area, candidate);
        Map<String, String> changedPositions = new java.util.HashMap<>();
        for (JsonObject action : actions) {
            String type = action.has("type") ? action.get("type").getAsString() : "";
            if (!Set.of("place", "place_project", "break", "move", "replace").contains(type)) throw new IllegalArgumentException(LocaleText.format(plugin, "message_599a7f4cc810"));
            for (String axis : List.of("x", "y", "z")) {
                if (!action.has(axis) || !action.get(axis).isJsonPrimitive() || !action.getAsJsonPrimitive(axis).isNumber())
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_4395e44113bd"));
                action.get(axis).getAsBigDecimal().intValueExact();
            }
            if (!type.equals("move")) {
                String before = changedPositions.put(blockKey(action), type);
                // nivelamento: quebra o terreno e so depois coloca a base na mesma posicao
                if (before != null && !(before.equals("break") && type.startsWith("place")))
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_2a58c1b420c8"));
            }
            Location location = new Location(world, action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
            if (!inAreaJson(area, location) || location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()
                    || Math.abs((long) location.getBlockX()) > 29_999_984 || Math.abs((long) location.getBlockZ()) > 29_999_984)
                throw new IllegalArgumentException(LocaleText.format(plugin, "message_9ca1a4f63e76"));
            if (type.startsWith("place") || type.equals("replace")) {
                Material material = materialOf(action);
                if (!placeable(material, action, candidate)) throw new IllegalArgumentException(LocaleText.format(plugin, "message_3c8c888e95b9"));
                if (action.has("data")) {
                    var data = Bukkit.createBlockData(action.get("data").getAsString());
                    if (data.getMaterial() != material) throw new IllegalArgumentException(LocaleText.format(plugin, "message_9d913a3d2718"));
                    if (data instanceof org.bukkit.block.data.Waterlogged wet && wet.isWaterlogged())
                        throw new IllegalArgumentException(LocaleText.format(plugin, "message_cbc2ea682900"));
                }
            }
            action.add("area", area.deepCopy());
        }
        if (candidate != null) {
            Map<CatalogGardenPolicy.Point,CatalogGardenPolicy.Cell> resulting = new HashMap<>(gardenCells(candidate));
            for (JsonObject action : actions) {
                String type = action.get("type").getAsString();
                if (type.equals("move")) continue;
                var point = new CatalogGardenPolicy.Point(action.get("x").getAsInt(),action.get("y").getAsInt(),action.get("z").getAsInt());
                if (type.equals("break")) resulting.remove(point);
                else resulting.put(point,new CatalogGardenPolicy.Cell(action.get("material").getAsString(),
                        action.has("data") ? action.get("data").getAsString() : null));
            }
            String problem = CatalogGardenPolicy.problem(resulting);
            if (problem != null) throw new IllegalArgumentException(problem);
        }
        waterActionsLast(actions);
    }

    /** Apply source blocks last so a validated basin exists before any water is placed. */
    static void waterActionsLast(List<JsonObject> actions) {
        List<JsonObject> waterActions = new ArrayList<>();
        actions.removeIf(action -> {
            if (action.has("_aibuilder_contained_water")) { waterActions.add(action); return true; }
            return false;
        });
        actions.addAll(waterActions);
    }

    private boolean canPlan(Player player) {
        if (selectionRequest != null || terrainWork != null || requests.isThinking() || blueprintPlanning || pendingActions != null || !queue.isEmpty() || activeBuild != null) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_cdf80b67eb87"))); return false;
        }
        return true;
    }

    /** Pedido do criador: pode ser uma obra nova ou, com obra aberta, um incremento nela. */
    boolean canRequest(Player player) {
        if (terrainWork != null || busy || requests.isThinking() || blueprintPlanning || pendingActions != null || !queue.isEmpty()) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_523d5d857477", LocaleText.text(plugin, busyDescription())))); return false;
        }
        return true;
    }

    private void confirmPlan(CommandSender sender) {
        if (pendingActions == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_9ad6e71ef38f"))); return; }
        if (System.currentTimeMillis() > pendingExpires) {
            clearPending();
            sender.sendMessage(msg(LocaleText.format(plugin, "message_c3d7a9a523c0")));
            return;
        }
        if (npc == null || npc.isDead() || pendingArea == null || !npc.getWorld().getName().equals(pendingArea.get("world").getAsString())) {
            clearPending(); sender.sendMessage(msg(LocaleText.format(plugin, "message_ae6a46542a0e"))); return;
        }
        try {
            validateActions(pendingActions, pendingArea);
            for (JsonObject action : pendingActions) {
                // Preconditions are derived from our saved plan, never trusted from the LLM.
                action.remove("expectedMaterial"); action.remove("expectedData");
                if (!"replace".equals(action.get("type").getAsString())) continue;
                BlueprintCompiler.Block source = activeBuild == null ? null : activeBuild.blocks.stream()
                        .filter(b -> b.x() == action.get("x").getAsInt() && b.y() == action.get("y").getAsInt()
                                && b.z() == action.get("z").getAsInt()).findFirst().orElse(null);
                if (!pendingIncrement || source == null) throw new IllegalArgumentException(LocaleText.format(plugin, "message_47d738983cd6"));
                action.addProperty("expectedMaterial", source.material());
                String sourceData = activeBuild.blockData.get(blockKey(action));
                if (sourceData != null) action.addProperty("expectedData", sourceData);
            }
        } catch (RuntimeException invalid) {
            clearPending(); refuse(sender, LocaleText.format(plugin, "message_8e59bc2f362d", LocaleText.text(plugin, invalid.getMessage())), LocaleText.format(plugin, "message_efc5a492a34d", LocaleText.text(plugin, invalid.getMessage()))); return;
        }
        if (pendingIncrement) {
            if (activeBuild == null) { clearPending(); sender.sendMessage(msg(LocaleText.format(plugin, "message_9dd6315fa12e"))); return; }
            Build next = activeBuild.withIncrement(pendingActions, pendingRequest);
            if (!saveBuild(next, LocaleText.format(plugin, "message_17bd143699ca"))) {
                sender.sendMessage(msg(LocaleText.format(plugin, "message_24f67ad26e47"))); return;
            }
            activeBuild = next;
            activeCancelToken = confirmationToken(); nextMovementAttemptAt = 0;
            queue.addAll(pendingActions);
            log("plan", LocaleText.format(plugin, "message_89a718123f70", sender.getName(), pendingActions.size()));
            clearPending();
            rejectionCounts.clear(); waitingReason = "";
            resetProgress();
            say(LocaleText.format(plugin, "message_e6f74f81abaa", queue.size()));
            return;
        }
        activeBuild = pendingBuild;
        if (activeBuild != null && !saveBuild(activeBuild, LocaleText.format(plugin, "message_17bd143699ca"))) {
            activeBuild = null;
            sender.sendMessage(msg(LocaleText.format(plugin, "message_4827519c9423"))); return;
        }
        if (settings.getBoolean("zone.enabled", false) && currentAreaJson().equals(pendingArea)) areaUsed = true;
        activeCancelToken = confirmationToken(); nextMovementAttemptAt = 0;
        queue.addAll(pendingActions);
        saveBuilderRegistry();
        log("plan", LocaleText.format(plugin, "message_50b795b74868", sender.getName(), pendingActions.size()));
        clearPending();
        rejectionCounts.clear(); waitingReason = "";
        resetProgress();
        say(LocaleText.format(plugin, "message_6a95f1ffb0c4", queue.size()));
    }

    // ---------- zona ----------

    private void zoneCommand(CommandSender sender, String op) {
        if (!(sender instanceof Player p)) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_07c6d68d4778"))); return; }
        if(terrainWork!=null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_11ec561a8bf6"))); return; }
        switch (op) {
            case "pos1" -> {
                zonePos1 = p.getLocation().getBlock().getLocation();
                keepAreaPreview(p);
                sender.sendMessage(msg(LocaleText.format(plugin, "message_abdff3856963", coords(zonePos1))));
            }
            case "pos2" -> {
                if (zonePos1 == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_d41c26c8933a"))); return; }
                Location p2 = p.getLocation().getBlock().getLocation();
                if (!p2.getWorld().equals(zonePos1.getWorld())) { sender.sendMessage(msg(LocaleText.format(plugin, "message_41c4914ebb8f"))); return; }
                saveZone(zonePos1, p2, p);
            }
            case "limpar" -> {
                settings.set("zone.enabled", false);
                saveBuilderRegistry();
                zonePos1 = null;
                sender.sendMessage(msg(LocaleText.format(plugin, "message_c6a8c110fc2e")));
            }
            default -> {
                sender.sendMessage(msg(LocaleText.format(plugin, "message_16a70de8f0b3", zoneDescription())));
                if (settings.getBoolean("zone.enabled", false)) previewArea(p, currentAreaJson());
            }
        }
    }

    private String zoneDescription() {
        if (!settings.getBoolean("zone.enabled", false)) return LocaleText.format(plugin, "message_86eba75487cd");
        return LocaleText.format(plugin, "message_cb06f25154f2", settings.getString("zone.world"), settings.getInt("zone.x1"), settings.getInt("zone.x2"), settings.getInt("zone.y1"), settings.getInt("zone.y2"), settings.getInt("zone.z1"), settings.getInt("zone.z2"));
    }

    /** Area marcada so no chao ganha zone.build-height (padrao 100), limitada pelo teto configurado. */
    private int extendHeight(int y1, int y2) {
        return Math.max(y2, (int)Math.min(Integer.MAX_VALUE, (long)y1 + Math.max(1, Math.min(constructionLimits().height(), getConfig().getInt("zone.build-height", 100))) - 1));
    }

    private void previewArea(Player player, JsonObject area) {
        World world = getServer().getWorld(area.get("world").getAsString());
        if (world != null) BuilderUi.preview(player,
                new Location(world, area.get("x1").getAsInt(), area.get("y1").getAsInt(), area.get("z1").getAsInt()),
                new Location(world, area.get("x2").getAsInt(), area.get("y2").getAsInt(), area.get("z2").getAsInt()));
    }

    private JsonObject currentAreaJson() {
        JsonObject ar = new JsonObject();
        ar.addProperty("world", settings.getString("zone.world", "world"));
        ar.addProperty("x1", settings.getInt("zone.x1"));
        ar.addProperty("y1", settings.getInt("zone.y1"));
        ar.addProperty("z1", settings.getInt("zone.z1"));
        ar.addProperty("x2", settings.getInt("zone.x2"));
        ar.addProperty("y2", settings.getInt("zone.y2"));
        ar.addProperty("z2", settings.getInt("zone.z2"));
        return ar;
    }

    private boolean inAreaJson(JsonObject ar, Location l) {
        if (!l.getWorld().getName().equals(ar.get("world").getAsString())) return false;
        return l.getBlockX() >= ar.get("x1").getAsInt() && l.getBlockX() <= ar.get("x2").getAsInt()
                && l.getBlockY() >= ar.get("y1").getAsInt() && l.getBlockY() <= ar.get("y2").getAsInt()
                && l.getBlockZ() >= ar.get("z1").getAsInt() && l.getBlockZ() <= ar.get("z2").getAsInt();
    }

    /** Area gravada na acao (planejada com ela); sem area gravada, usa a zona atual. */
    private boolean inAreaOf(JsonObject a, Location l) {
        if (a.has("area") && a.get("area").isJsonObject()) return inAreaJson(a.getAsJsonObject("area"), l);
        return inZone(l);
    }

    private boolean inZone(Location l) {
        if (!settings.getBoolean("zone.enabled", false)) return true;
        if (!l.getWorld().getName().equals(settings.getString("zone.world"))) return false;
        return l.getBlockX() >= settings.getInt("zone.x1") && l.getBlockX() <= settings.getInt("zone.x2")
                && l.getBlockY() >= settings.getInt("zone.y1") && l.getBlockY() <= settings.getInt("zone.y2")
                && l.getBlockZ() >= settings.getInt("zone.z1") && l.getBlockZ() <= settings.getInt("zone.z2");
    }

    // ---------- projetos (biblioteca de construcoes) ----------

    private void projectCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_07c6d68d4778"))); return; }
        String op = args.length > 1 ? args[1].toLowerCase() : "listar";
        String name = args.length > 2 ? args[2].toLowerCase() : null;
        switch (op) {
            case "canto1" -> {
                projCorner1 = p.getLocation().getBlock().getLocation();
                sender.sendMessage(msg(LocaleText.format(plugin, "message_534ebf7125d5", coords(projCorner1))));
            }
            case "canto2" -> {
                if (projCorner1 == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_b9d0eef4336b"))); return; }
                projCorner2 = p.getLocation().getBlock().getLocation();
                sender.sendMessage(msg(LocaleText.format(plugin, "message_15d52a01d992")));
            }
            case "salvar" -> saveProject(p, name);
            case "listar" -> listProjects(p);
            case "colocar" -> placeProject(p, name);
            default -> sender.sendMessage(msg(LocaleText.format(plugin, "message_ae596c680cfe")));
        }
    }

    private static boolean validProjectName(String name) {
        return name != null && !name.equals("_ultima") && name.matches("[a-z0-9_-]{1,40}");
    }

    private Path projectsDir() throws IOException {
        Path dir = getDataFolder().toPath().resolve("projects");
        Files.createDirectories(dir);
        return dir;
    }

    private void saveProject(Player p, String name) {
        if (!validProjectName(name)) { p.sendMessage(msg(LocaleText.format(plugin, "message_a85069e44c85"))); return; }
        if (projCorner1 == null || projCorner2 == null) { p.sendMessage(msg(LocaleText.format(plugin, "message_2c97019700ad"))); return; }
        if (!projCorner1.getWorld().equals(projCorner2.getWorld())) { p.sendMessage(msg(LocaleText.format(plugin, "message_644b3a561cc8"))); return; }
        int x1 = Math.min(projCorner1.getBlockX(), projCorner2.getBlockX());
        int y1 = Math.min(projCorner1.getBlockY(), projCorner2.getBlockY());
        int z1 = Math.min(projCorner1.getBlockZ(), projCorner2.getBlockZ());
        int x2 = Math.max(projCorner1.getBlockX(), projCorner2.getBlockX());
        int y2 = Math.max(projCorner1.getBlockY(), projCorner2.getBlockY());
        int z2 = Math.max(projCorner1.getBlockZ(), projCorner2.getBlockZ());
        long volume = boundedVolume((long)x2-x1+1,(long)y2-y1+1,(long)z2-z1+1,constructionLimits().volume());
        if (volume < 0 || !constructionLimits().fits((long)x2-x1+1,(long)y2-y1+1,(long)z2-z1+1)) { p.sendMessage(msg(LocaleText.format(plugin, "message_8110937b53c8", volume))); return; }

        World w = projCorner1.getWorld();
        int max = getConfig().getInt("max-project-blocks", 2500);
        JsonArray blocks = new JsonArray();
        for (int y = y1; y <= y2; y++)
            for (int z = z1; z <= z2; z++)
                for (int x = x1; x <= x2; x++) {
                    Material m = w.getBlockAt(x, y, z).getType();
                    if (m.isAir() || (!m.isSolid() && !m.name().endsWith("_DOOR"))) continue;
                    if (blocks.size() >= max) { p.sendMessage(msg(LocaleText.format(plugin, "message_499c21826ae2", max))); return; }
                    JsonObject o = new JsonObject();
                    o.addProperty("dx", x - x1);
                    o.addProperty("dy", y - y1);
                    o.addProperty("dz", z - z1);
                    o.addProperty("m", m.name());
                    o.addProperty("data", w.getBlockAt(x, y, z).getBlockData().getAsString());
                    blocks.add(o);
                }
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("blocks_count", blocks.size());
        root.add("blocks", blocks);
        try {
            Files.writeString(projectsDir().resolve(name + ".json"), gson.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_4877624f17f4", LocaleText.text(plugin, e.getMessage()))));
            return;
        }
        p.sendMessage(msg(LocaleText.format(plugin, "message_dcd1aaec01e1", name, blocks.size())));
        log("project", LocaleText.format(plugin, "message_568ec96cc167", name, blocks.size(), p.getName()));
    }

    private void listProjects(Player p) {
        try (var files = Files.list(projectsDir())) {
            List<String> names = files.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString().replace(".json", "")).sorted().toList();
            p.sendMessage(msg(names.isEmpty() ? LocaleText.format(plugin, "message_b2e175828d50") : LocaleText.format(plugin, "message_8676f9eb61c2", String.join(", ", names))));
        } catch (IOException e) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_67328272e599", LocaleText.text(plugin, e.getMessage()))));
        }
    }

    /** Monta um plano com os blocos do projeto e espera a confirmacao do criador (mesmo fluxo da IA). */
    private void placeProject(Player p, String name) {
        if (!canPlan(p)) return;
        if (!settings.getBoolean("zone.enabled", false)) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_3e1ecd2dd3dd"))); return;
        }
        if (!validProjectName(name)) { p.sendMessage(msg(LocaleText.format(plugin, "message_97b589bceb32"))); return; }
        if (npc == null || npc.isDead()) { p.sendMessage(msg(LocaleText.format(plugin, "message_7b50ecc8a3b9"))); return; }
        try {
            Path file = projectsDir().resolve(name + ".json");
            if (!Files.exists(file)) { p.sendMessage(msg(LocaleText.format(plugin, "message_40c17c6b39b3", name))); return; }
            if (Files.size(file) > constructionLimits().fileBytes()) throw new IllegalArgumentException(LocaleText.format(plugin, "message_a654b28b2b90"));
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            // origem: 3 blocos ao lado do construtor, para nao construir em cima dele
            JsonObject area = currentAreaJson();
            World world = getServer().getWorld(area.get("world").getAsString());
            if (world == null) throw new IllegalArgumentException(LocaleText.format(plugin, "message_e51a3ca00c32"));
            Location origin = new Location(world, area.get("x1").getAsInt(), area.get("y1").getAsInt(), area.get("z1").getAsInt());
            JsonArray saved = root.getAsJsonArray("blocks");
            if (saved == null || saved.size() > getConfig().getInt("max-project-blocks", 2500))
                throw new IllegalArgumentException(LocaleText.format(plugin, "message_cf38765c43fa"));
            List<JsonObject> plan = new ArrayList<>();
            for (JsonElement el : saved) {
                JsonObject b = el.getAsJsonObject();
                JsonObject a = new JsonObject();
                a.addProperty("type", "place_project");
                a.addProperty("x", Math.addExact(origin.getBlockX(), b.get("dx").getAsInt()));
                a.addProperty("y", Math.addExact(origin.getBlockY(), b.get("dy").getAsInt()));
                a.addProperty("z", Math.addExact(origin.getBlockZ(), b.get("dz").getAsInt()));
                a.addProperty("material", b.get("m").getAsString());
                if (b.has("data")) a.addProperty("data", b.get("data").getAsString());
                a.add("area", area.deepCopy());
                if (!inAreaJson(area, new Location(world, a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt())))
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_95244a41508e"));
                plan.add(a);
            }
            if (plan.isEmpty()) { p.sendMessage(msg(LocaleText.format(plugin, "message_42c2a1a46eff"))); return; }
            pendingActions = plan;
            pendingArea = area;
            List<BlueprintCompiler.Block> projectBlocks = new ArrayList<>();
            for (JsonObject action : plan) projectBlocks.add(new BlueprintCompiler.Block(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt(), action.get("material").getAsString(), 0));
            pendingBuild = new Build(name, world.getName(), projectBlocks, area);
            for (JsonObject action : plan) if (action.has("data")) pendingBuild.blockData.put(blockKey(action), action.get("data").getAsString());
            pendingExpires = System.currentTimeMillis() + planTimeoutMillis();
            pendingSummary = LocaleText.format(plugin, "message_67e4b0cbf08a", name, plan.size());
            showPending(p);
        } catch (IOException | RuntimeException e) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_b01d066b3690", LocaleText.text(plugin, e.getMessage()))));
        }
    }

    /** /construtor pre-moldado [listar | <id>]: casas prontas, sem IA; custo capturado na proposta. */
    private void prebuiltCommand(CommandSender sender, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "listar";
        if (op.equals("recarregar")) {
            if (!sender.isOp()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_c66148f1c0ad"))); return; }
            UserPrebuilts.Load load = loadPrebuilts(getDataFolder().toPath(), constructionLimits(), plugin.maxUserPrebuilts());
            sender.sendMessage(msg("§6" + LocaleText.format(plugin, "message_04f82f92140e", load.entries().size(), load.files())));
            for (String error : load.errors()) sender.sendMessage(msg("§c" + error));
            return;
        }
        if (op.equals("listar")) {
            sender.sendMessage(msg("§6" + LocaleText.format(plugin, "message_96c43bc77e3c", String.join(", ", PrebuiltCatalog.CATEGORIES))));
            for (PrebuiltCatalog.Entry e : PrebuiltCatalog.all())
                sender.sendMessage(msg("§f" + e.id() + " §7- " + e.title() + ": " + e.description()));
            sender.sendMessage(msg("§7" + LocaleText.format(plugin, "message_4ba62637141f", String.join(", ", PrebuiltCatalog.PLANNED), commandPrefix())));
            return;
        }
        PrebuiltCatalog.Entry entry = PrebuiltCatalog.find(op);
        if (entry == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_c17c861a3563", LocaleText.text(plugin, op), commandPrefix()))); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_70c99eff9073"))); return; }
        if (!canPlan(p)) return;
        if (npc == null || npc.isDead()) { p.sendMessage(msg(LocaleText.format(plugin, "message_7b50ecc8a3b9"))); return; }
        if (!settings.getBoolean("zone.enabled", false)) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_d080af38b7b9", commandPrefix()))); return;
        }
        JsonObject marked = currentAreaJson();
        String facing = args.length == 3 ? BlueprintOrientation.parse(args[2]) : entranceDirection();
        if(args.length > 3 || args.length == 3 && facing == null) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_e30c6826841b")));return;
        }
        JsonObject oriented = BlueprintOrientation.orient(entry.blueprint(), facing);
        startOrientedBuild(p, oriented, marked, true);
    }

    /**
     * Linha com o espaco que o pre-moldado ocupa (x, z e y, mais o total de blocos) e se a area marcada cabe.
     * Serve para marcar a area certa para limpeza ou nivelamento antes de construir.
     */
    private String prebuiltSizeLine(BlueprintCompiler.Result r, JsonObject area) {
        if (!r.isValid() || r.blocks.isEmpty()) return "§7" + LocaleText.format(plugin, "message_d634969d257c");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlueprintCompiler.Block b : r.blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
        }
        int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;
        StringBuilder line = new StringBuilder("§6" + LocaleText.format(plugin, "message_7edda3acbb43", w, h, d, w, h, d, (long) w * h * d, r.blocks.size()));
        int aw = area.get("x2").getAsInt() - area.get("x1").getAsInt() + 1;
        int ah = area.get("y2").getAsInt() - area.get("y1").getAsInt() + 1;
        int ad = area.get("z2").getAsInt() - area.get("z1").getAsInt() + 1;
        if (aw < w || ah < h || ad < d)
            line.append(LocaleText.format(plugin, "message_20d0ee4771e0", aw, ah, ad));
        else
            line.append(LocaleText.format(plugin, "message_3ac022fdb0a8", aw, ah, ad));
        return line.toString();
    }

    // ---------- obras (plantas compiladas e conferidas) ----------

    private Path buildsDir() throws IOException {
        Path d = dataDirectory().resolve("builds");
        Files.createDirectories(d);
        return d;
    }

    private void startBuild(Player player, JsonObject bp, JsonObject area) { startBuild(player, bp, area, false); }

    /**
     * creative=true (pre-moldado): as acoes nao consomem materiais e, se o chao da primeira camada estiver
     * ocupado, a base sobe ate 3 blocos procurando uma camada livre. Pedidos da IA mantem a origem fixa.
     */
    private void startBuild(Player player, JsonObject bp, JsonObject area, boolean creative) {
        try { startOrientedBuild(player, BlueprintOrientation.orient(bp.deepCopy(), entranceDirection()), area, creative); }
        catch (IllegalArgumentException e) { refuse(player, LocaleText.format(plugin, "message_ded3d2363e5d", LocaleText.text(plugin, e.getMessage())), LocaleText.format(plugin, "message_e18f93c529d0")); }
    }

    private void startOrientedBuild(Player player, JsonObject bp, JsonObject area, boolean creative) {
        if (blueprintPlanning) { player.sendMessage(msg(LocaleText.format(plugin, "message_f1e646408c1c"))); return; }
        final JsonObject snapshot;
        final JsonObject areaSnapshot;
        final ConstructionLimits limits = constructionLimits();
        final long generation = ++blueprintPlanningGeneration;
        try {
            snapshot = bp.deepCopy();
            areaSnapshot = area.deepCopy();
        } catch (IllegalArgumentException e) {
            refuse(player, LocaleText.format(plugin, "message_ded3d2363e5d", LocaleText.text(plugin, e.getMessage())), LocaleText.format(plugin, "message_e18f93c529d0"));
            return;
        }
        blueprintPlanning = true;
        boolean submitted = plugin.submitBlueprintWork(() -> {
            PreparedPlan prepared;
            try { prepared = prepareBlueprint(snapshot, areaSnapshot, creative, limits); }
            catch (RuntimeException failure) { prepared = new PreparedPlan(snapshot, List.of(), failure); }
            PreparedPlan result = prepared;
            try { getServer().getScheduler().runTask(plugin, () -> {
                if (generation != blueprintPlanningGeneration) return;
                blueprintPlanning = false;
                if (npc == null || npc.isDead() || player == null || !player.isOnline()) return;
                if (result.failure() != null) {
                    refuse(player, LocaleText.format(plugin, "message_ded3d2363e5d", LocaleText.text(plugin, result.failure().getMessage())), LocaleText.format(plugin, "message_e18f93c529d0"));
                    return;
                }
                if (creative && !result.candidates().isEmpty()) {
                    String entrance = result.blueprint().has("entrance") ? result.blueprint().get("entrance").getAsString() : "north";
                    player.sendMessage(msg(LocaleText.format(plugin, "message_d349b7363578", LocaleText.text(plugin, BlueprintOrientation.label(entrance)), prebuiltSizeLine(result.candidates().get(0).result(), areaSnapshot))));
                }
                planOrientedBuild(player, result.blueprint(), areaSnapshot, creative, result.candidates());
            }); } catch (org.bukkit.plugin.IllegalPluginAccessException stopping) {
                // Plugin shutdown invalidates the generation and owns persistence of the builder state.
            }
        });
        if (!submitted) {
            blueprintPlanning = false;
            player.sendMessage(msg(LocaleText.format(plugin, "message_f1e646408c1c")));
        } else player.sendMessage(msg(LocaleText.format(plugin, "message_f1e646408c1c")));
    }

    private record PreparedCandidate(BlueprintCompiler.Result result, int shift, int xOffset, int zOffset, boolean centered) { }
    private record PreparedPlan(JsonObject blueprint, List<PreparedCandidate> candidates, RuntimeException failure) { }

    /** Pure geometry work only; this method must never touch Bukkit world/entity APIs. */
    private static PreparedPlan prepareBlueprint(JsonObject bp, JsonObject area, boolean creative, ConstructionLimits limits) {
        BlueprintCompiler.Result origin = BlueprintCompiler.compile(bp, 0, 0, 0, limits);
        int yShift = 0;
        if (origin.isValid() && !origin.blocks.isEmpty())
            yShift = Math.max(0, -origin.blocks.stream().mapToInt(BlueprintCompiler.Block::y).min().orElse(0));
        int x1 = area.get("x1").getAsInt(), z1 = area.get("z1").getAsInt();
        int areaW = area.get("x2").getAsInt() - x1 + 1, areaD = area.get("z2").getAsInt() - z1 + 1;
        List<PreparedCandidate> candidates = new ArrayList<>();
        for (int s = 0; s <= (creative ? 3 : 0); s++) {
            int ox = 0, oz = 0;
            boolean centered = false;
            if (origin.isValid() && !origin.blocks.isEmpty()) {
                int y = area.get("y1").getAsInt() + s + yShift;
                BlueprintCompiler.Result placedProbe = translated(origin, x1, y, z1);
                if (placedProbe.isValid() && !placedProbe.blocks.isEmpty()) {
                    int[] pl = bounds(placedProbe.blocks.stream().map(b -> new int[]{b.x(), b.y(), b.z()}).toList());
                    boolean outside = pl[0] < x1 || pl[3] > area.get("x2").getAsInt() || pl[2] < z1 || pl[5] > area.get("z2").getAsInt();
                    int planW = pl[3] - pl[0] + 1, planD = pl[5] - pl[2] + 1;
                    if (outside && planW <= areaW && planD <= areaD) {
                        ox = (areaW - planW) / 2 - (pl[0] - x1);
                        oz = (areaD - planD) / 2 - (pl[2] - z1);
                        centered = true;
                    }
                }
            }
            int oy = area.get("y1").getAsInt() + s + yShift;
            BlueprintCompiler.Result compiled = translated(origin, x1 + ox, oy, z1 + oz);
            candidates.add(new PreparedCandidate(compiled, s, ox, oz, centered));
        }
        return new PreparedPlan(bp.deepCopy(), List.copyOf(candidates), null);
    }

    private static BlueprintCompiler.Result translated(BlueprintCompiler.Result source, int ox, int oy, int oz) {
        BlueprintCompiler.Result result = new BlueprintCompiler.Result();
        result.warnings.addAll(source.warnings);
        result.errors.addAll(source.errors);
        if (!source.isValid()) return result;
        for (BlueprintCompiler.Block block : source.blocks) {
            long x = (long) block.x() + ox, y = (long) block.y() + oy, z = (long) block.z() + oz;
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
                result.errors.add("planta invalida: coordenada fora dos limites");
                result.warnings.addAll(result.errors);
                result.blocks.clear();
                return result;
            }
            result.blocks.add(new BlueprintCompiler.Block((int)x, (int)y, (int)z, block.material(), block.phase(), block.data()));
        }
        return result;
    }

    private void planOrientedBuild(Player player, JsonObject bp, JsonObject area, boolean creative, List<PreparedCandidate> prepared) {
        if (npc == null || npc.isDead()) { player.sendMessage(msg(LocaleText.format(plugin, "message_7b50ecc8a3b9"))); return; }
        String name = bp.has("name") && !bp.get("name").isJsonNull() ? bp.get("name").getAsString() : "obra";
        name = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        if (name.isEmpty()) name = "obra";
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.equals("_ultima")) name = "obra";
        World zw = getServer().getWorld(area.get("world").getAsString());
        if (zw == null) { player.sendMessage(msg(LocaleText.format(plugin, "message_108b5eecf68e"))); return; }
        int max = getConfig().getInt("max-project-blocks", 2500);
        List<String> avisos = new ArrayList<>();
        List<BlueprintCompiler.Block> ok = new ArrayList<>();
        List<String> occupied = List.of();
        int shift = 0;
        int x1 = area.get("x1").getAsInt(), z1 = area.get("z1").getAsInt();
        int areaW = area.get("x2").getAsInt() - x1 + 1, areaD = area.get("z2").getAsInt() - z1 + 1;
        boolean centered = false;
        List<JsonObject> leveling = null;
        for (PreparedCandidate candidate : prepared) {
            int s = candidate.shift();
            centered |= candidate.centered();
            BlueprintCompiler.Result r = candidate.result();
            if (!r.isValid()) { refuse(player, LocaleText.format(plugin, "message_6352bb664a92", r.errors.stream().map(error -> LocaleText.text(plugin, error)).toList()), LocaleText.format(plugin, "message_6352bb664a92", r.errors.stream().map(error -> LocaleText.text(plugin, error)).toList())); return; }
            if (r.blocks.stream().anyMatch(block -> !inAreaJson(area, new Location(zw, block.x(), block.y(), block.z())))) {
                int[] pl = bounds(r.blocks.stream().map(b -> new int[]{b.x(), b.y(), b.z()}).toList());
                refuse(player, LocaleText.format(plugin, "message_87091d3c9f9b", pl[0], pl[3], pl[1], pl[4], pl[2], pl[5], area.get("x1").getAsInt(), area.get("x2").getAsInt(), area.get("y1").getAsInt(), area.get("y2").getAsInt(), area.get("z1").getAsInt(), area.get("z2").getAsInt()),
                        LocaleText.format(plugin, "message_9a408e24b95c"));
                return;
            }
            avisos = new ArrayList<>(r.warnings);
            ok = new ArrayList<>();
            for (BlueprintCompiler.Block b : r.blocks) {
                if (Material.matchMaterial(b.material()) == null) {
                    String aviso = LocaleText.format(plugin, "message_6dd1d1a648d8", b.material());
                    if (!avisos.contains(aviso)) avisos.add(aviso);
                    continue;
                }
                ok.add(b);
            }
            if (ok.isEmpty()) { player.sendMessage(msg(LocaleText.format(plugin, "message_0a361d387d1d", avisos))); return; }
            if (ok.size() > max) { player.sendMessage(msg(LocaleText.format(plugin, "message_51dbec93824a", ok.size(), max))); return; }
            leveling = null;
            occupied = occupiedPlacements(blocksToActions(ok), area);
            if (!occupied.isEmpty()) {
                leveling = levelingBreaks(ok, area);
                if (leveling != null) {
                    Set<String> cleared = leveling.stream().map(a -> a.get("x").getAsInt() + " "
                            + a.get("y").getAsInt() + " " + a.get("z").getAsInt()).collect(java.util.stream.Collectors.toSet());
                    occupied = occupied.stream().filter(c -> !cleared.contains(c)).toList();
                }
            }
            shift = s;
            if (occupied.isEmpty()) break;
        }
        if (!occupied.isEmpty()) { refuse(player, occupiedMessage(occupied), LocaleText.format(plugin, "message_ae9d1ecfede7", occupied.size())); return; }
        if (shift > 0) avisos.add(LocaleText.format(plugin, "message_f75d9643d046", shift));
        if (centered) avisos.add(LocaleText.format(plugin, "message_e5c1ee16c6c4"));
        if (leveling != null) avisos.add(LocaleText.format(plugin, "message_98e2a93e7200", leveling.size(), commandPrefix(), commandPrefix()));
        String gardenProblem = CatalogGardenPolicy.problem(CatalogGardenPolicy.cells(ok));
        if (gardenProblem != null) { refuse(player, gardenProblem, gardenProblem); return; }
        Build build = new Build(name, zw.getName(), ok, area);
        if (creative) {
            build.catalogId = name;
            build.catalogFreeMaterials = getConfig().getBoolean("prebuilt.free-materials", true);
            build.catalogPlacements = new java.util.HashSet<>(build.materialsByPosition.keySet());
        }
        pendingBuild = build;
        pendingArea = area.deepCopy();
        List<JsonObject> plan = blocksToActions(ok);
        if (leveling != null) {
            for (JsonObject preparation : leveling) build.preparationTargets.add(blockKey(preparation));
            plan.addAll(0, leveling);
        }
        if (creative) for (JsonObject a : plan) a.addProperty("creative", true);
        pendingActions = plan;
        pendingExpires = System.currentTimeMillis() + planTimeoutMillis();
        pendingSummary = LocaleText.format(plugin, creative ? "prebuilt.summary" : "building.summary", name, ok.size());
        pendingSummary += LocaleText.format(plugin, "message_f107ad6d7068", LocaleText.text(plugin, BlueprintOrientation.label(bp.get("entrance").getAsString())));
        for (String a : avisos) player.sendMessage(msg(LocaleText.format(plugin, "message_1a77bd2eae40", a)));
        showPending(player);
        log("build", LocaleText.format(plugin, "message_3e169ce5c492", name, ok.size()) + (creative ? " (pre-moldado)" : ""));
    }

    /** Min/max de x,y,z: {minX,minY,minZ,maxX,maxY,maxZ}. */
    private static int[] bounds(List<int[]> points) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : points) for (int i = 0; i < 3; i++) { b[i] = Math.min(b[i], p[i]); b[i + 3] = Math.max(b[i + 3], p[i]); }
        return b;
    }

    /**
     * Nivelamento estrutural: a primeira camada da planta cai em terreno natural de ate 2 blocos de altura.
     * Devolve as quebras dessas colunas (saem antes da base). Null se houver outro material, construcao ou
     * mais de 2 blocos de altura: nesse caso o plano segue as regras normais (recusa ou sobe a base).
     */
    private List<JsonObject> levelingBreaks(List<BlueprintCompiler.Block> ok, JsonObject area) {
        World w = getServer().getWorld(area.get("world").getAsString());
        if (w == null || ok.isEmpty()) return null;
        int baseY = ok.stream().mapToInt(BlueprintCompiler.Block::y).min().getAsInt();
        List<JsonObject> breaks = new ArrayList<>();
        Set<String> columns = new java.util.HashSet<>();
        for (BlueprintCompiler.Block b : ok) {
            if (b.y() != baseY) continue;
            Material expected = Material.matchMaterial(b.material());
            if (expected == null) continue;
            Block base = w.getBlockAt(b.x(), baseY, b.z());
            if (base.getType().isAir() || base.getType() == expected) continue;
            if (!columns.add(b.x() + "," + b.z())) continue;
            int height = 0;
            while (height <= 2) {
                Material top = w.getBlockAt(b.x(), baseY + height, b.z()).getType();
                if (top.isAir()) break;
                if (!BREAKABLE.contains(top)) return null;
                height++;
            }
            if (height == 0 || height > 2) return null;
            for (int j = 0; j < height; j++) {
                JsonObject a = new JsonObject();
                a.addProperty("type", "break");
                a.addProperty("x", b.x());
                a.addProperty("y", baseY + j);
                a.addProperty("z", b.z());
                breaks.add(a);
            }
        }
        return breaks.isEmpty() ? null : breaks;
    }

    private List<JsonObject> blocksToActions(List<BlueprintCompiler.Block> blocks) {
        List<JsonObject> out = new ArrayList<>();
        for (BlueprintCompiler.Block b : blocks) {
            JsonObject a = new JsonObject();
            a.addProperty("type", "place_project");
            a.addProperty("x", b.x());
            a.addProperty("y", b.y());
            a.addProperty("z", b.z());
            a.addProperty("material", b.material());
            if (b.data() != null) a.addProperty("data", b.data());
            out.add(a);
        }
        // Install doors after their surroundings, lower/upper halves consecutively. No later
        // neighbour placement should update a temporarily incomplete pair.
        out.sort((a,b) -> {
            int gardenOrder = Integer.compare(CatalogGardenPolicy.order(a.get("material").getAsString()), CatalogGardenPolicy.order(b.get("material").getAsString()));
            if (gardenOrder != 0) return gardenOrder;
            boolean ad=a.get("material").getAsString().endsWith("_DOOR"), bd=b.get("material").getAsString().endsWith("_DOOR");
            if(ad!=bd)return ad?1:-1;
            if(!ad)return 0;
            for(String axis:List.of("x","z","y")) {int compare=Integer.compare(a.get(axis).getAsInt(),b.get(axis).getAsInt());if(compare!=0)return compare;}
            return 0;
        });
        return out;
    }

    /** Blocos da obra que ainda nao estao no mundo com o material esperado. Thread principal. */
    private List<BlueprintCompiler.Block> missingBlocks(Build b) {
        List<BlueprintCompiler.Block> miss = new ArrayList<>();
        World w = getServer().getWorld(b.world);
        if (w == null) return b.blocks;
        for (BlueprintCompiler.Block blk : b.blocks) {
            Material m = Material.matchMaterial(blk.material());
            Block actual = w.getBlockAt(blk.x(), blk.y(), blk.z());
            String data = b.blockData.get(blk.x() + "," + blk.y() + "," + blk.z());
            if (m != null && (actual.getType() != m || (data != null && !BlockStateSpec.matches(data, actual.getBlockData().getAsString())))) miss.add(blk);
        }
        return miss;
    }

    private void buildCommand(CommandSender sender, String[] args) {
        if (args.length > 1 && (args[1].equalsIgnoreCase("end") || args[1].equalsIgnoreCase("encerrar"))) { endBuild(sender); return; }
        if (args.length > 1 && (args[1].equalsIgnoreCase("salvar") || args[1].equalsIgnoreCase("save"))) { saveBuildCommand(sender); return; }
        if (args.length > 1 && args[1].equalsIgnoreCase("speed")) { quickBuildCommand(sender, args); return; }
        if (args.length > 1 && (args[1].equalsIgnoreCase("desfazer") || args[1].equalsIgnoreCase("undo"))) {
            if (activeBuild == null && args.length > 2) activeBuild = loadSavedBuild(sender, args[2]);
            undoBuild(sender); return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("continuar")) {
            boolean resumeRecalledQueue = recalled() && activeBuild != null
                    && !queue.isEmpty() && !requests.isThinking() && pendingActions == null;
            if (requests.isThinking() || pendingActions != null || (!queue.isEmpty() && !resumeRecalledQueue)) {
                sender.sendMessage(msg(LocaleText.format(plugin, "message_7c61926750a2"))); return;
            }
            // A named selection from Build > Continue must not silently continue a different open plan.
            // When idle, checkpoint and pause the current build before switching to the requested one.
            if (activeBuild != null && args.length > 2 && !activeBuild.name.equals(args[2])) {
                if (!queue.isEmpty()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_7c61926750a2"))); return; }
                Build previous = activeBuild;
                if (!saveBuild(previous, "parada")) {
                    sender.sendMessage(msg(LocaleText.format(plugin, "message_e7e5b5e664f1", previous.name)));
                    return;
                }
                activeBuild = null;
                sender.sendMessage(msg(LocaleText.format(plugin, "message_29723fd8804d", previous.name, previous.placed.size(), previous.blocks.size())));
            }
            if (activeBuild == null && args.length <= 2) { listUnfinishedBuilds(sender); return; }
            if (activeBuild == null) activeBuild = loadSavedBuild(sender, args[2]);
            if (activeBuild != null && recalled() && !releaseRecallHold(sender)) return;
            // call preserves the confirmed plan and queue. Releasing the recall hold is enough;
            // do not rebuild the queue or create a second preview for actions already queued.
            if (resumeRecalledQueue && !queue.isEmpty()) return;
        }
        if (activeBuild == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_3e69d0e853cc"))); return; }
        boolean continuar = args.length > 1 && args[1].equalsIgnoreCase("continuar");
        reportBuild(sender, continuar);
    }

    /** Save an explicit checkpoint and pause only after the atomic plan save succeeds. */
    private void saveBuildCommand(CommandSender sender) {
        Build build = activeBuild;
        if (build == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_325ce485011d"))); return; }
        if (!saveBuild(build, "parada")) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_e7e5b5e664f1", build.name)));
            return;
        }
        int cancelled = queue.size();
        queue.clear();
        clearPending(); invalidateRequest();
        activeCancelToken = ""; nextMovementAttemptAt = 0;
        busy = false; waitingReason = "";
        walker.stop(npc); ui.hideAll();
        activeBuild = null;
        sender.sendMessage(msg(LocaleText.format(plugin, "message_29723fd8804d", build.name, build.placed.size(), build.blocks.size())));
        log("save", "build " + build.name + " paused; " + build.placed.size() + "/" + build.blocks.size() + " placed, " + cancelled + " queued actions cancelled");
    }

    /** Server operators can opt one builder into the bounded faster construction cadence. */
    private void quickBuildCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_eb0bb6dc7d73"))); return; }
        if (args.length > 3 || args.length == 3 && !Set.of("on", "off").contains(args[2].toLowerCase(java.util.Locale.ROOT))) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_278388572bab"))); return;
        }
        boolean enabled = args.length == 3 ? args[2].equalsIgnoreCase("on") : !quickBuildMode;
        if (enabled == quickBuildMode) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_daa4ac6bbf88", LocaleText.format(plugin, enabled ? "message_fb9cf75606b4" : "message_17eb3c0168d0"))));
            return;
        }
        boolean previous = quickBuildMode;
        quickBuildMode = enabled;
        if (!saveBuilderRegistry()) {
            quickBuildMode = previous;
            sender.sendMessage(msg(LocaleText.format(plugin, "message_ccd22564dba3")));
            return;
        }
        if(enabled) beginQuickBuildHover();
        else endQuickBuildHover();
        sender.sendMessage(msg(LocaleText.format(plugin, "message_258efbc8a3a4", LocaleText.format(plugin, enabled ? "message_fb9cf75606b4" : "message_17eb3c0168d0"))));
    }

    private void beginQuickBuildHover() {
        if(npc==null || npc.isDead()) return;
        walker.hover(npc);
        Location here=npc.getLocation();
        int x=here.getBlockX(), y=here.getBlockY(), z=here.getBlockZ();
        World world=here.getWorld();
        quickHoverTargetY=Double.NaN;
        if(y+2<world.getMaxHeight() && NpcWalker.clear(world,x,y,z)
                && NpcWalker.clear(world,x,y+1,z) && NpcWalker.clear(world,x,y+2,z))
            quickHoverTargetY=Math.min(world.getMaxHeight()-2,here.getY()+1.25);
    }

    private void maintainQuickBuildHover() {
        if(npc==null || npc.isDead()) return;
        npc.setGravity(false);
        if(Double.isFinite(quickHoverTargetY) && npc.getLocation().getY()+.08<quickHoverTargetY) {
            npc.setVelocity(new org.bukkit.util.Vector(0,Math.min(.18,quickHoverTargetY-npc.getLocation().getY()),0));
            return;
        }
        quickHoverTargetY=Double.NaN;
        walker.hover(npc);
    }

    private void endQuickBuildHover() {
        quickHoverTargetY=Double.NaN;
        if(npc==null || npc.isDead()) return;
        walker.land(npc);
        npc.setGravity(true);
        npc.setVelocity(new org.bukkit.util.Vector());
    }

    /** Move the NPC out of its own edit target before retrying the preserved action. */
    private String requestBuilderSelfClear() {
        if(npc==null || npc.isDead()) return LocaleText.raw("message_b009f8938d54");
        if(selfEscapeOrigin==null) {
            selfEscapeOrigin=npc.getLocation().clone();
            selfEscapeRetryAt=0;
            walker.stop(npc);
        }
        if(!walker.hasGoal() || walker.state()==NpcWalker.State.BLOCKED) startBuilderSelfClearRoute();
        return LocaleText.raw("builder.self-clear");
    }

    private boolean builderSelfClearRouteAllowed(Location location) {
        if(location==null || location.getWorld()==null || npc==null || npc.isDead()
                || !location.getWorld().equals(npc.getWorld())) return false;
        World world=location.getWorld();
        int x=location.getBlockX(), y=location.getBlockY(), z=location.getBlockZ();
        if(y<=world.getMinHeight() || y+2>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4)) return false;
        if(!world.getWorldBorder().isInside(location)
                || !world.getWorldBorder().isInside(location.clone().add(.3,0,.3))
                || !world.getWorldBorder().isInside(location.clone().add(-.3,0,-.3))) return false;
        return NpcWalker.clear(world,x,y,z) && NpcWalker.clear(world,x,y+1,z) && NpcWalker.clear(world,x,y+2,z);
    }

    private void startBuilderSelfClearRoute() {
        if(selfEscapeOrigin==null || npc==null || npc.isDead() || System.currentTimeMillis()<selfEscapeRetryAt) return;
        World world=selfEscapeOrigin.getWorld();
        if(world==null || !world.equals(npc.getWorld())) { selfEscapeRetryAt=System.currentTimeMillis()+100; return; }
        var random=java.util.concurrent.ThreadLocalRandom.current();
        // Try a bounded upward flight exit first, then retain the three horizontal escape options.
        // Vertical displacement also counts toward the 10-block escape threshold.
        for(int attempt=0;attempt<4;attempt++) {
            double angle=random.nextDouble(0,Math.PI*2);
            int x, y, z;
            if(attempt==0) {
                int rise=random.nextInt(10,17);
                int lateral=random.nextInt(0,5);
                x=(int)Math.round(selfEscapeOrigin.getX()+Math.cos(angle)*lateral);
                z=(int)Math.round(selfEscapeOrigin.getZ()+Math.sin(angle)*lateral);
                y=selfEscapeOrigin.getBlockY()+rise;
            } else {
                int distance=random.nextInt(12,21);
                x=(int)Math.round(selfEscapeOrigin.getX()+Math.cos(angle)*distance);
                z=(int)Math.round(selfEscapeOrigin.getZ()+Math.sin(angle)*distance);
                y=selfEscapeOrigin.getBlockY();
            }
            Location target=new Location(world,x+.5,y,z+.5);
            if(target.distanceSquared(selfEscapeOrigin)<100 || !builderSelfClearRouteAllowed(target)) continue;
            var cell=new WalkPathfinder.Cell(x,y,z);
            if(!NpcWalker.canStand(world,cell,l->true) && !fallSafe(world,x,y,z)) continue;
            var body=bodyAt(target);
            if(world.getNearbyEntities(body,entity->entity!=npc && !entity.isDead()).size()>0) continue;
            walker.setBoundary(this::builderSelfClearRouteAllowed);
            walker.flyToWork(npc,target,.25,.28,this::builderSelfClearRouteAllowed);
            walker.tick(npc);
            if(walker.state()!=NpcWalker.State.BLOCKED && walker.hasGoal()) {
                selfEscapeRetryAt=0;
                return;
            }
            walker.stop(npc);
        }
        selfEscapeRetryAt=System.currentTimeMillis()+50;
    }

    private boolean tickBuilderSelfClear() {
        if(selfEscapeOrigin==null || npc==null || npc.isDead()) return false;
        if(npc.getLocation().distanceSquared(selfEscapeOrigin)>=100) {
            selfEscapeOrigin=null;
            selfEscapeRetryAt=0;
            waitingReason="";
            walker.stop(npc);
            if(quickBuildMode) beginQuickBuildHover(); else walker.land(npc);
            return true;
        }
        walker.setBoundary(this::builderSelfClearRouteAllowed);
        if(walker.hasGoal() && walker.state()!=NpcWalker.State.BLOCKED) walker.tick(npc);
        if(npc.getLocation().distanceSquared(selfEscapeOrigin)>=100) return tickBuilderSelfClear();
        if(!walker.hasGoal() || walker.state()==NpcWalker.State.BLOCKED) {
            walker.stop(npc);
            selfEscapeRetryAt=0;
            startBuilderSelfClearRoute();
        }
        return true;
    }

    /** A failed checkpoint is retried without stopping the queue; warnings are throttled. */
    private void checkpointBuildIfDue() {
        Build build = activeBuild;
        if (build == null) return;
        if (lastCheckpointBuild != build) {
            lastCheckpointBuild = build;
            lastCheckpointPlaced = build.placed.size();
            checkpointRetryAfter = 0;
        }
        int placed = build.placed.size();
        if (placed - lastCheckpointPlaced < BUILD_AUTOSAVE_BLOCKS || System.currentTimeMillis() < checkpointRetryAfter) return;
        if (saveBuild(build, "in progress")) {
            lastCheckpointPlaced = placed;
            checkpointRetryAfter = 0;
            log("checkpoint", "build " + build.name + " checkpointed at " + placed + "/" + build.blocks.size() + "; construction continues");
            Player owner = ownerPlayer();
            if (owner != null) owner.sendMessage(msg(LocaleText.format(plugin, "message_de33fb6ca4f1", placed)));
        } else {
            checkpointRetryAfter = System.currentTimeMillis() + 5000L;
            if (System.currentTimeMillis() >= checkpointWarningAfter) {
                checkpointWarningAfter = System.currentTimeMillis() + 60_000L;
                log("checkpoint", "failed to save build " + build.name + " at " + placed + "; construction continues");
                say(LocaleText.format(plugin, "message_4a4e4e923bf4", placed));
            }
        }
    }

    /** Encerra a obra aberta. So com a fila vazia e sem plano pendente. */
    private void endBuild(CommandSender sender) {
        Build b = activeBuild;
        if (b == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_5bd73633dda9"))); return; }
        if (!queue.isEmpty() || pendingActions != null) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_0d739fb39984"))); return;
        }
        int faltam = missingBlocks(b).size();
        if (!saveBuild(b, faltam == 0 ? "concluida" : "parada")) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_8bb67f26b173"))); return;
        }
        activeBuild = null;
        log("build", LocaleText.format(plugin, "message_45a86bcd3d4a", b.name, b.blocks.size(), faltam));
        sender.sendMessage(msg(LocaleText.format(plugin, "message_a2ffa7cc345d", b.name, b.blocks.size(), faltam > 0 ? LocaleText.format(plugin, "message_fa84210efee8", faltam) : "")));
    }

    /** Remove em lotes os blocos do plano que ainda correspondem ao que o NPC colocou. */
    private void undoBuild(CommandSender sender) {
        if (!getConfig().getBoolean("features.undo.enabled", true)) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_4eca9bb9606a"))); return;
        }
        Build build = activeBuild;
        if (build == null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_84659a6f8789"))); return; }
        if (undoInProgress) { sender.sendMessage(msg(LocaleText.format(plugin, "message_003f42c8b9b0"))); return; }
        undoActor=sender instanceof Player p?p:ownerPlayer();
        if(undoActor==null || !undoActor.isOnline()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_20b185a3db5b"))); return; }
        World world = getServer().getWorld(build.world);
        if (world == null || npc == null || npc.isDead() || !npc.getWorld().equals(world)) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_bb4b84888741"))); return;
        }
        // Migrates open plans created before placement provenance was persisted, while requiring exact expected states.
        for (BlueprintCompiler.Block target : build.blocks) {
            Block block = world.getBlockAt(target.x(), target.y(), target.z());
            Material expected = Material.matchMaterial(target.material());
            String data = build.blockData.get(target.x() + "," + target.y() + "," + target.z());
            if (expected != null && block.getType() == expected
                    && (data == null || BlockStateSpec.matches(data, block.getBlockData().getAsString())))
                build.placed.add(target.x() + "," + target.y() + "," + target.z());
        }
        if (build.placed.isEmpty()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_c0fa90df6647"))); return; }
        if (!saveBuild(build, "desfazendo")) { sender.sendMessage(msg(LocaleText.format(plugin, "message_22b26668fb9e"))); return; }
        invalidateRequest(); clearPending(); queue.clear(); waitingReason = "";
        resetProgress();
        List<String> positions = new ArrayList<>(build.placed);
        positions.sort((a,b) -> Integer.compare(parseY(b), parseY(a)));
        for (String position : positions) {
            String[] xyz = position.split(",", -1);
            JsonObject action = new JsonObject(); action.addProperty("type", "undo");
            action.addProperty("x", Integer.parseInt(xyz[0])); action.addProperty("y", Integer.parseInt(xyz[1])); action.addProperty("z", Integer.parseInt(xyz[2]));
            action.addProperty("material", build.materialsByPosition.getOrDefault(position, ""));
            String data = build.blockData.get(position); if (data != null) action.addProperty("data", data);
            action.add("area", build.area.deepCopy()); queue.add(action);
        }
        undoInProgress = true; busy = true; walker.stop(npc);
        sender.sendMessage(msg(LocaleText.format(plugin, "message_8ecb1854d81d", queue.size())));
        log("undo", LocaleText.format(plugin, "message_e7628c3c259f", sender.getName(), queue.size(), build.name));
    }

    private int parseY(String position) {
        try { String[] xyz=position.split(",",-1); return Integer.parseInt(xyz[1]); }
        catch (RuntimeException invalid) { return Integer.MIN_VALUE; }
    }

    private void finishUndo() {
        Build build=activeBuild;
        if(build==null) { undoInProgress=false; undoActor=null; return; }
        int remaining=build.placed.size();
        if(remaining==0) {
            if(!deleteBuildFiles(build)) {
                saveBuild(build,"desfeita");
                say(LocaleText.format(plugin, "message_021e400f6f4e"));
            } else say(LocaleText.format(plugin, "message_c099e4b9fbe0", build.name));
            activeBuild=null;
        } else {
            saveBuild(build,"parada");
            say(LocaleText.format(plugin, "message_898456824b49", remaining));
            // Keep the surviving work open so the owner can retry the undo after protections change.
        }
        undoInProgress=false; undoActor=null; waitingReason="";
        log("undo", remaining==0?LocaleText.format(plugin, "message_fbf87a128caa"):LocaleText.format(plugin, "message_0be35bbdddf3", remaining));
        Player owner=ownerPlayer(); if(owner!=null) ui.hide(owner);
    }

    private boolean deleteBuildFiles(Build build) {
        try {
            Files.deleteIfExists(buildsDir().resolve(build.name+".json"));
            Path latest=buildsDir().resolve("_ultima.json");
            if(Files.exists(latest)) {
                try {
                    JsonObject saved=JsonParser.parseString(Files.readString(latest,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(saved.has("name") && saved.get("name").getAsString().equals(build.name)
                            && (!saved.has("owner") || ownerId==null || saved.get("owner").getAsString().equals(ownerId.toString()))) Files.deleteIfExists(latest);
                } catch(RuntimeException ignored) { }
            }
            return true;
        } catch(IOException e) { getLogger().warning(LocaleText.format(plugin, "message_f66a6351eb7a", build.name, LocaleText.text(plugin, e.getMessage()))); return false; }
    }

    private void reportBuild(CommandSender sender, boolean allowContinue) {
        Build b = activeBuild;
        if (b == null) return;
        List<BlueprintCompiler.Block> miss = missingBlocks(b);
        int total = b.blocks.size();
        sender.sendMessage(msg(LocaleText.format(plugin, "message_b48e0c60efd6", b.name, total - miss.size(), total, miss.size())));
        if (miss.isEmpty()) {
            // a obra fica aberta: o criador pede incrementos (janelas, portas, decoracao) ate /construtor obra end
            sender.sendMessage(msg(LocaleText.format(plugin, "message_74d091432865", b.name)));
            if (!saveBuild(b, "aberta")) sender.sendMessage(msg(LocaleText.format(plugin, "message_176503545896")));
            return;
        }
        if (!allowContinue) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_38a45aba37f6")));
            return;
        }
        pendingActions = blocksToActions(miss);
        List<JsonObject> preparation = levelingBreaks(b.blocks, b.area);
        b.preparationTargets.clear();
        if (preparation != null) {
            for (JsonObject action : preparation) b.preparationTargets.add(blockKey(action));
            pendingActions.addAll(0, preparation);
        }
        pendingBuild = b; pendingArea = b.area.deepCopy();
        for (JsonObject action : pendingActions) {
            action.add("area", b.area.deepCopy());
            String data = b.blockData.get(blockKey(action));
            if (data != null) action.addProperty("data", data);
        }
        pendingExpires = System.currentTimeMillis() + planTimeoutMillis();
        pendingSummary = LocaleText.format(plugin, "message_1961df5d39ca", b.name, miss.size());
        if (sender instanceof Player player) showPending(player);
        else sender.sendMessage(msg(LocaleText.format(plugin, "message_85612282d175", pendingSummary)));
        activeBuild = null;
    }

    private boolean saveBuild(Build b, String status) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("name", b.name);
            o.addProperty("world", b.world);
            o.addProperty("status", status);
            if (b.catalogId != null) o.addProperty("catalogId", b.catalogId);
            o.addProperty("catalogFreeMaterials", b.catalogFreeMaterials);
            if (b.catalogPlacements != null) {
                JsonArray catalogPlacements = new JsonArray();
                for (String position : b.catalogPlacements) catalogPlacements.add(position);
                o.add("catalogPlacements", catalogPlacements);
            }
            o.add("area", b.area.deepCopy());
            if (ownerId != null) o.addProperty("owner", ownerId.toString());
            JsonArray arr = new JsonArray();
            for (BlueprintCompiler.Block blk : b.blocks) {
                JsonObject x = new JsonObject();
                x.addProperty("x", blk.x());
                x.addProperty("y", blk.y());
                x.addProperty("z", blk.z());
                x.addProperty("m", blk.material());
                String data = b.blockData.get(blk.x() + "," + blk.y() + "," + blk.z());
                if (data != null) x.addProperty("data", data);
                arr.add(x);
            }
            o.add("blocks", arr);
            JsonArray placed = new JsonArray();
            for (String position : b.placed) placed.add(position);
            o.add("placed", placed);
            JsonArray stepsArr = new JsonArray();
            for (String s : b.steps) stepsArr.add(s);
            o.add("steps", stepsArr);
            writeAtomic(buildsDir().resolve(b.name + ".json"), gson.toJson(o));
            writeAtomic(buildsDir().resolve("_ultima.json"), gson.toJson(o));
            return true;
        } catch (IOException e) {
            getLogger().warning(LocaleText.format(plugin, "message_2e507b426fb1", LocaleText.text(plugin, e.getMessage())));
            return false;
        }
    }

    private void writeAtomic(Path target, String value) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".aibuilder-", ".tmp");
        try {
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            try { Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private Build loadSavedBuild(CommandSender sender, String name) {
        if (name != null && !validProjectName(name)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_cf165cc9a7a3"))); return null; }
        try {
            Path own = buildsDir().resolve(name == null ? "_ultima.json" : name + ".json");
            Path path = own;
            // Obra inacabada de um construtor apagado: retoma aqui e move o arquivo para este construtor.
            if (name != null && !Files.exists(own)) for (Path other : unfinishedBuildFiles(plugin))
                if (other.getFileName().toString().equals(name + ".json")) { path = other; break; }
            if (!Files.exists(path)) return null;
            if (Files.size(path) > constructionLimits().fileBytes()) throw new IllegalArgumentException(LocaleText.format(plugin, "message_bf53ffcd54bc"));
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if ("concluida".equals(saved.get("status").getAsString())) { sender.sendMessage(msg(LocaleText.format(plugin, "message_58f85be24daf"))); return null; }
            if (!saved.has("area")) throw new IllegalArgumentException(LocaleText.format(plugin, "message_d9c6c9fba6a0"));
            if (sender instanceof Player player && saved.has("owner") && !player.isOp()
                    && !saved.get("owner").getAsString().equals(player.getUniqueId().toString())) throw new IllegalArgumentException(LocaleText.format(plugin, "message_34001d4a166f"));
            JsonArray blocks = saved.getAsJsonArray("blocks");
            if (blocks == null || blocks.size() > constructionLimits().blocks()) throw new IllegalArgumentException(LocaleText.format(plugin, "message_a32293c00fff"));
            List<BlueprintCompiler.Block> list = new ArrayList<>();
            List<JsonObject> actions = new ArrayList<>();
            for (JsonElement element : blocks) {
                JsonObject block = element.getAsJsonObject();
                JsonObject action = block.deepCopy(); action.addProperty("type", "place_project"); action.add("material", block.get("m")); actions.add(action);
                list.add(new BlueprintCompiler.Block(block.get("x").getAsInt(), block.get("y").getAsInt(), block.get("z").getAsInt(), block.get("m").getAsString(), 0, block.has("data") ? block.get("data").getAsString() : null));
            }
            JsonObject area = saved.getAsJsonObject("area");
            if (npc == null || npc.isDead() || !npc.getWorld().getName().equals(saved.get("world").getAsString()))
                throw new IllegalArgumentException(LocaleText.format(plugin, "message_95c01c3bcba3"));
            if (!saved.get("world").getAsString().equals(area.get("world").getAsString())) throw new IllegalArgumentException(LocaleText.format(plugin, "message_15a871e6ce8e"));
            Build build = new Build(saved.get("name").getAsString(), saved.get("world").getAsString(), list, area);
            if (saved.has("catalogId") && PrebuiltCatalog.find(saved.get("catalogId").getAsString()) != null)
                build.catalogId = saved.get("catalogId").getAsString();
            build.catalogFreeMaterials = !saved.has("catalogFreeMaterials") || saved.get("catalogFreeMaterials").getAsBoolean();
            if (saved.has("catalogPlacements")) {
                build.catalogPlacements = new java.util.HashSet<>();
                for (JsonElement position : saved.getAsJsonArray("catalogPlacements"))
                    build.catalogPlacements.add(position.getAsString());
            }
            validateActions(actions, area, build);
            if (!validProjectName(build.name)) throw new IllegalArgumentException(LocaleText.format(plugin, "message_393a5bd955a7"));
            if (saved.has("steps")) for (JsonElement s : saved.getAsJsonArray("steps")) build.steps.add(s.getAsString());
            boolean placementJournal = saved.has("placed") && saved.get("placed").isJsonArray();
            if (placementJournal)
                for (JsonElement position : saved.getAsJsonArray("placed")) if (position.isJsonPrimitive()) build.placed.add(position.getAsString());
            // Older records have no provenance journal; recover exact matches only for that legacy format.
            // Newer journals are authoritative so matching player-placed blocks are never claimed as NPC work.
            World savedWorld=getServer().getWorld(build.world);
            for (BlueprintCompiler.Block target : build.blocks) {
                Block actual=savedWorld.getBlockAt(target.x(),target.y(),target.z());
                Material expected=Material.matchMaterial(target.material());
                String expectedData=build.blockData.get(target.x()+","+target.y()+","+target.z());
                String key=target.x()+","+target.y()+","+target.z();
                boolean matches=expected!=null && actual.getType()==expected
                        && (expectedData==null || BlockStateSpec.matches(expectedData,actual.getBlockData().getAsString()));
                if(placementJournal) { if(!matches) build.placed.remove(key); }
                else if(matches) build.placed.add(key);
            }
            for (JsonObject action : actions) if (action.has("data")) build.blockData.put(blockKey(action), action.get("data").getAsString());
            if (!path.equals(own)) Files.move(path, own, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return build;
        } catch (IOException | RuntimeException error) { sender.sendMessage(msg(LocaleText.format(plugin, "message_0f04d2234fe3", LocaleText.text(plugin, error.getMessage())))); return null; }
    }

    /** Arquivos .json de obras de todos os construtores (inclui os apagados), exceto o ponteiro _ultima. */
    static List<Path> unfinishedBuildFiles(AIBuilderPlugin plugin) throws IOException {
        List<Path> out = new ArrayList<>();
        Path root = plugin.getDataFolder().toPath().resolve("builders");
        if (!Files.isDirectory(root)) return out;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                Path builds = dir.resolve("builds");
                if (!Files.isDirectory(builds)) continue;
                try (var files = Files.list(builds)) {
                    for (Path f : files.toList()) {
                        String n = f.getFileName().toString();
                        if (n.endsWith(".json") && !n.equals("_ultima.json") && !n.startsWith(".")) out.add(f);
                    }
                }
            }
        }
        return out;
    }

    /** Obras inacabadas do criador (name, status, blocks), de todos os construtores dele. */
    static List<JsonObject> unfinishedFor(AIBuilderPlugin plugin, UUID owner) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        for (Path file : unfinishedBuildFiles(plugin)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!o.has("owner") || !o.has("name") || !o.get("owner").getAsString().equals(owner.toString())) continue;
                String status = o.has("status") ? o.get("status").getAsString() : "";
                if ("concluida".equals(status)) continue;
                JsonObject row = new JsonObject();
                row.addProperty("name", o.get("name").getAsString());
                row.addProperty("status", status);
                row.addProperty("blocks", o.has("blocks") ? o.getAsJsonArray("blocks").size() : 0);
                out.add(row);
            } catch (RuntimeException unreadable) { /* arquivo corrompido: ignora */ }
        }
        return out;
    }

    /** Apaga somente arquivos incompletos pertencentes ao jogador; construcoes concluidas e outros donos ficam intactos. */
    static int deleteUnfinishedFor(AIBuilderPlugin plugin, UUID owner) throws IOException {
        int removed=0;
        for(Path file:unfinishedBuildFiles(plugin)) {
            try {
                JsonObject saved=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
                if(!saved.has("owner") || !owner.toString().equals(saved.get("owner").getAsString())
                        || !saved.has("name") || "concluida".equals(saved.has("status")?saved.get("status").getAsString():"")) continue;
                String name=saved.get("name").getAsString();
                boolean currentlyOpen=plugin.builders.values().stream().anyMatch(builder -> owner.equals(builder.ownerId)
                        && builder.activeBuild!=null && name.equals(builder.activeBuild.name));
                if(currentlyOpen) continue;
                if(Files.deleteIfExists(file)) removed++;
                Path latest=file.getParent().resolve("_ultima.json");
                if(Files.exists(latest)) {
                    try {
                        JsonObject pointer=JsonParser.parseString(Files.readString(latest,StandardCharsets.UTF_8)).getAsJsonObject();
                        if(pointer.has("name") && name.equals(pointer.get("name").getAsString())
                                && pointer.has("owner") && owner.toString().equals(pointer.get("owner").getAsString())) Files.deleteIfExists(latest);
                    } catch(RuntimeException ignored) { }
                }
            } catch(RuntimeException unreadable) { /* registros corrompidos nao sao apagados em lote */ }
        }
        return removed;
    }

    /** obra continuar (sem nome): lista as obras inacabadas do criador para retomar com obra continuar <nome>. */
    private void listUnfinishedBuilds(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_c09ae8433ddd"))); return; }
        List<String> lines = new ArrayList<>();
        try {
            for (JsonObject row : unfinishedFor(plugin, player.getUniqueId())) {
                lines.add("§f" + LocaleText.format(plugin, "message_a933d03251b0", row.get("name").getAsString(), row.get("status").getAsString(), row.get("blocks").getAsInt(), commandPrefix(), row.get("name").getAsString()));
            }
        } catch (IOException | RuntimeException error) { sender.sendMessage(msg(LocaleText.format(plugin, "message_4f1ee1b84b98", LocaleText.text(plugin, error.getMessage())))); return; }
        if (lines.isEmpty()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_4f1f3529b76b"))); return; }
        sender.sendMessage(msg("§6" + LocaleText.format(plugin, "message_8e524b2585bb")));
        for (String line : lines) sender.sendMessage(msg(line));
    }

    // ---------- terreno (levantamento e execucao nativos) ----------

    /** Materiais reconhecidos como vegetacao; madeira/logs tambem podem ser usados em construcoes. */
    private void terrainCommand(CommandSender sender, String[] args) {
        boolean deep=args.length>1 && args[1].equalsIgnoreCase("deep");
        int operationIndex=deep?2:1;
        String op = args.length > operationIndex ? args[operationIndex].toLowerCase(java.util.Locale.ROOT) : "analisar";
        if (op.equals("cancelar")) {
            if(args.length>operationIndex+1 && !terrainButtonMatches(sender,args[operationIndex+1],false)) return;
            cancelTerrain(LocaleText.format(plugin, "message_bce7114ce3d6", sender.getName())); awaitingClearRegion=false; return;
        }
        if (!Set.of("limpar", "nivelar").contains(op)) {
            if(deep) { sender.sendMessage(msg(LocaleText.format(plugin, "message_29c8acd3cb05"))); return; }
            if (!settings.getBoolean("zone.enabled", false)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_4d46bedfd4bc"))); return; }
            analyzeZone(sender); return;
        }
        if(deep && !getConfig().getBoolean("terrain.deep-clean.enabled",false)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_05ce7060c43f"))); return; }
        if(deep && !sender.isOp()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_65e7e00fcd30"))); return; }
        boolean level=op.equals("nivelar");
        TerrainGrid.Mode mode=deep?(level?TerrainGrid.Mode.DEEP_LEVEL:TerrainGrid.Mode.DEEP_CLEAR):(level?TerrainGrid.Mode.LEVEL:TerrainGrid.Mode.CLEAR);
        int confirmationIndex=operationIndex+1;
        if (args.length > confirmationIndex && args[confirmationIndex].equalsIgnoreCase("sim")) {
            if(args.length>confirmationIndex+1 && !terrainButtonMatches(sender,args[confirmationIndex+1])) return;
            if (terrainWork == null || terrainWork.mode()!=mode) { sender.sendMessage(msg("primeiro use /construtor terreno " + (deep?"deep ":"") + op + " para preparar uma previa.")); return; }
            if (!terrainWork.confirm(System.currentTimeMillis())) sender.sendMessage(msg(LocaleText.format(plugin, "message_6bfa36d3dd83")));
            else sender.sendMessage(msg(LocaleText.format(plugin, "message_6c6154f6f2eb")));
            return;
        }
        if (terrainWork != null) { sender.sendMessage(msg(LocaleText.format(plugin, "message_e529c124f1c8"))); return; }
        if(deep && !(sender instanceof Player)) { sender.sendMessage(msg(LocaleText.format(plugin, "message_8aa057d7909d"))); return; }
        if (!canPlanForTerrain(sender)) return;
        selectionMode = mode;
        if (settings.getBoolean("zone.enabled", false) && !(args.length > operationIndex+1 && args[operationIndex+1].equalsIgnoreCase("selecionar"))) {
            startTerrain(sender, mode, currentAreaJson());
        } else {
            clearPos1=null; clearPos2=null; awaitingClearRegion=true;
            sender.sendMessage(msg("marque a regiao de " + (deep?"deep clean ":"") + op + " com a pa: esquerdo no canto 1, direito no canto 2. Depois mostrarei a previa."));
        }
    }

    private boolean terrainButtonMatches(CommandSender sender, String token) { return terrainButtonMatches(sender,token,true); }
    private boolean terrainButtonMatches(CommandSender sender, String token, boolean readyOnly) {
        if(terrainWork==null || readyOnly && terrainWork.stage()!=TerrainWork.Stage.READY || !token.equals(terrainToken)) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_7b3a995eea5a")));
            return false;
        }
        return true;
    }

    private String terrainConfirmCommand() {
        return commandPrefix()+" terrain "+(terrainWork.mode().deep()?"deep ":"")+(terrainWork.mode().level()?"level":"clean")+" yes "+terrainToken;
    }
    private String terrainCancelCommand() { return commandPrefix() + " terrain cancel " + terrainToken; }

    private boolean canPlanForTerrain(CommandSender sender) {
        if (requests.isThinking() || busy || pendingActions != null || !queue.isEmpty() || activeBuild != null) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_1ea361470ffc"))); return false;
        }
        return true;
    }

    private World zoneWorld() {
        return getServer().getWorld(settings.getString("zone.world", "world"));
    }

    private void analyzeZone(CommandSender sender) {
        if(terrainWork!=null || requests.isThinking()) { sender.sendMessage(msg(LocaleText.format(plugin, "message_bd6e1ca26acc"))); return; }
        long token=requests.begin();
        JsonObject area=currentAreaJson().deepCopy();
        surveyArea(sender,area,token,true,context -> {
            if(!requests.complete(token)) return;
            if(sender instanceof Player player) ui.hide(player);
            int table=context.indexOf(LocaleText.english(plugin) ? "\nSurface grid" : "\nGrade");
            sender.sendMessage(msg(table<0?context:context.substring(0,table)));
            sender.sendMessage(msg(LocaleText.format(plugin, "message_878879703ebc")));
        });
    }

    private boolean isVegetation(Material m) {
        String n = m.name().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("_wood") || n.equals("short_grass")
                || n.equals("tall_grass") || n.equals("fern") || n.equals("large_fern") || n.equals("dandelion")
                || n.endsWith("_sapling") || n.endsWith("_tulip") || n.endsWith("_mushroom")
                || Set.of("poppy","snow","blue_orchid","allium","azure_bluet","oxeye_daisy","cornflower","lily_of_the_valley",
                        "sunflower","lilac","rose_bush","peony","dead_bush","vine","glow_lichen","hanging_roots","moss_carpet",
                        "bamboo","sugar_cane","cactus","pink_petals","wildflowers","leaf_litter","bush","firefly_bush").contains(n);
    }

    private int terrainVolumeLimit() {
        Object configured=getConfig().get("max-clear-volume",262_144);
        long requested=262_144L;
        boolean valid=true;
        if(configured instanceof Number number) {
            try { requested=new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch(NumberFormatException|ArithmeticException invalid) { valid=false; }
        } else if(configured instanceof String text) {
            try { requested=Long.parseLong(text.trim()); } catch(NumberFormatException invalid) { valid=false; }
        } else valid=false;
        if(!valid||requested<1) {
            plugin.getLogger().warning("Invalid max-clear-volume='"+configured+"'; using default 262144.");
            return 262_144;
        }
        if(requested>TerrainGrid.MAX_VOLUME) {
            plugin.getLogger().warning("max-clear-volume requested "+requested+" exceeds technical ceiling "+TerrainGrid.MAX_VOLUME+"; effective limit is "+TerrainGrid.MAX_VOLUME+".");
            return Math.toIntExact(TerrainGrid.MAX_VOLUME);
        }
        return Math.toIntExact(requested);
    }
    private int terrainDepth() { return Math.max(1, Math.min(64, getConfig().getInt("terrain.level-depth", 16))); }
    private int scanBatch() { return Math.max(128, Math.min(8192, getConfig().getInt("terrain.scan-per-tick", 2048))); }
    private int changeBatch() { return Math.max(1, Math.min(512, getConfig().getInt("terrain.changes-per-step", 128))); }

    private TerrainGrid.Bounds surveyBounds(JsonObject area, boolean below) {
        World world=getServer().getWorld(area.get("world").getAsString());
        if(world==null) throw new IllegalArgumentException(LocaleText.format(plugin, "message_e51a3ca00c32"));
        int base=area.get("y1").getAsInt();
        int bottom=below?(int)Math.max((long)world.getMinHeight(),(long)base-terrainDepth()):base;
        int top=area.get("y2").getAsInt();
        if(bottom<world.getMinHeight() || top>=world.getMaxHeight()) throw new IllegalArgumentException(LocaleText.format(plugin, "message_24a33bd30493"));
        int x1=area.get("x1").getAsInt(),z1=area.get("z1").getAsInt(),x2=area.get("x2").getAsInt(),z2=area.get("z2").getAsInt();
        long width=(long)x2-x1+1,height=(long)top-bottom+1,depth=(long)z2-z1+1,volume;
        int limit=terrainVolumeLimit();
        try { volume=Math.multiplyExact(Math.multiplyExact(width,height),depth); }
        catch(ArithmeticException overflow) {
            throw new IllegalArgumentException(LocaleText.format(plugin, "message_5f2dca90a3b3", width, height, depth, limit));
        }
        if(width<1||height<1||depth<1) throw new IllegalArgumentException(LocaleText.format(plugin, "message_2c2e4a045142", width, height, depth));
        if(volume>limit) throw new IllegalArgumentException(LocaleText.format(plugin, "message_72f8cf5c0e1e", width, height, depth, volume, limit));
        return new TerrainGrid.Bounds(x1,bottom,z1,x2,top,z2);
    }

    private JsonObject areaFor(TerrainGrid.Bounds bounds, String world) {
        JsonObject result=new JsonObject(); result.addProperty("world",world);
        result.addProperty("x1",bounds.x1()); result.addProperty("y1",bounds.y1()); result.addProperty("z1",bounds.z1());
        result.addProperty("x2",bounds.x2()); result.addProperty("y2",bounds.y2()); result.addProperty("z2",bounds.z2());
        return result;
    }

    private JsonObject areaAfterLeveling(JsonObject selected, int floor) {
        JsonObject result=selected.deepCopy();
        result.addProperty("y1",floor);
        // Keep the marked upper bound. Preparation must not grant extra build height.
        return result;
    }

    private static final Set<String> NATURAL_TERRAIN=Set.of("DIRT","GRASS_BLOCK","STONE","COARSE_DIRT","ROOTED_DIRT", "PODZOL","MYCELIUM","SAND","RED_SAND","GRAVEL","CLAY","TERRACOTTA","DEEPSLATE","SNOW_BLOCK","GRANITE","DIORITE","ANDESITE","BASALT");

    private Block surveyedBlock(World world, TerrainGrid.Point point) {
        if(!world.isChunkLoaded(point.x()>>4, point.z()>>4)) throw new IllegalArgumentException(LocaleText.format(plugin, "message_d8b5d5aead70"));
        Location location=new Location(world,point.x(),point.y(),point.z());
        if(!world.getWorldBorder().isInside(location)) throw new IllegalArgumentException(LocaleText.format(plugin, "message_5002d568ccaa"));
        return world.getBlockAt(point.x(),point.y(),point.z());
    }

    private TerrainGrid.Cell readTerrain(World world, TerrainGrid.Point point) {
        Block block=surveyedBlock(world, point);
        Material material=block.getType();
        String data=block.getBlockData().getAsString();
        return terrainCells.computeIfAbsent(data, ignored -> new TerrainGrid.Cell(material.name(),data,
                material.isAir()?TerrainGrid.Kind.AIR: block.isLiquid()?TerrainGrid.Kind.LIQUID:
                isVegetation(material)?TerrainGrid.Kind.VEGETATION:NATURAL_TERRAIN.contains(material.name())?TerrainGrid.Kind.NATURAL:
                material.isSolid()?TerrainGrid.Kind.SOLID:TerrainGrid.Kind.OTHER));
    }

    private void startTerrain(CommandSender sender, TerrainGrid.Mode mode, JsonObject selected) {
        if(!sender.hasPermission("aibuilder.use") || !plugin.controls(sender,this) || !canPlanForTerrain(sender)) return;
        try {
            if(mode.deep() && (!sender.isOp() || !getConfig().getBoolean("terrain.deep-clean.enabled",false)))
                throw new IllegalArgumentException(LocaleText.format(plugin, "message_14ccfd1465cc"));
            // Normal leveling must stay inside the marked selection. Only destructive deep-level
            // needs a below-selection search to find natural terrain under covered structures.
            TerrainGrid.Bounds bounds=surveyBounds(selected,mode==TerrainGrid.Mode.DEEP_LEVEL);
            // Reject malformed horizontal coordinates before surveying any world blocks.
            if(Math.abs((long)bounds.x1())>29_999_984 || Math.abs((long)bounds.x2())>29_999_984 || Math.abs((long)bounds.z1())>29_999_984 || Math.abs((long)bounds.z2())>29_999_984)
                throw new IllegalArgumentException(LocaleText.format(plugin, "message_2321c1f0c302"));
            terrainCells.clear();
            terrainWork=new TerrainWork(new TerrainGrid(bounds),mode,Math.max(1,Math.min(262_144,getConfig().getInt("terrain.max-changes",262_144))),selected.get("y1").getAsInt());
            terrainToken=id+":"+UUID.randomUUID();
            terrainActor=sender; terrainArea=areaFor(bounds,selected.get("world").getAsString()); terrainNotice="";
            terrainProtectionDenials=terrainListenerChanges=terrainOtherSkips=0;
            walker.stop(npc);
            sender.sendMessage(msg(LocaleText.format(plugin, "message_27428a4fa270", mode.name(), bounds.width(), bounds.depth(), bounds.y1(), bounds.y2())));
        } catch(RuntimeException error) { sender.sendMessage(msg(error.getMessage())); }
    }

    private TerrainWork.Access terrainAccess(World world, TerrainGrid.Mode mode) {
        return new TerrainWork.Access() {
            public TerrainGrid.Cell read(TerrainGrid.Point point) { return readTerrain(world,point); }
            public void validate() {
                if(!mode.deep()) return;
                if(!getConfig().getBoolean("terrain.deep-clean.enabled",false))
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_7c7832c3f5ab"));
                TerrainGrid.Bounds bounds=terrainWork.grid().bounds();
                org.bukkit.util.BoundingBox selection=new org.bukkit.util.BoundingBox(bounds.x1(),bounds.y1(),bounds.z1(),bounds.x2()+1.0,bounds.y2()+1.0,bounds.z2()+1.0);
                if (terrainWork.stage()==TerrainWork.Stage.APPLY || terrainWork.stage()==TerrainWork.Stage.RECHECK || terrainWork.stage()==TerrainWork.Stage.FINAL_CHECK)
                    for(Player player:world.getPlayers()) if(player.getBoundingBox().overlaps(selection))
                        throw new TerrainWork.PauseException(LocaleText.format(plugin, "terrain.easy.step-out"));
                if(!(terrainActor instanceof Player actor) || !actor.isOnline() || !actor.isOp())
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_e5467bf8f91d"));
            }
            public void check(TerrainGrid.Change change) {
                Block block=surveyedBlock(world,change.point());
                if(!inAreaJson(terrainArea,block.getLocation()) || (!mode.deep() && inSpawnZone(block.getLocation())) || claimed(block)
                        || (!mode.deep() && playerModified(block,terrainArea)))
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_89e27785c6bd"));
                if(mode.deep()) return; // Spawn/claim ya aplicados; solo el historico CoreProtect queda en eventos nativos.
                if(!change.removes()) {
                    var p=change.point();
                    var box=new org.bukkit.util.BoundingBox(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1);
                    var blockers=world.getNearbyEntities(box, entity -> entity instanceof Player
                            || entity instanceof org.bukkit.entity.LivingEntity && !isIgnoredTerrainMob(entity));
                    if(blockers.size()==1 && blockers.iterator().next()==npc) {
                        requestBuilderSelfClear();
                        throw new TerrainWork.PauseException(LocaleText.format(plugin,"builder.self-clear"));
                    }
                    if(!blockers.isEmpty())
                        throw new IllegalArgumentException(LocaleText.format(plugin, "message_0e12b3b437d5"));
                }
                if(change.before().kind()!=TerrainGrid.Kind.AIR && change.before().kind()!=TerrainGrid.Kind.NATURAL && change.before().kind()!=TerrainGrid.Kind.VEGETATION)
                    throw new IllegalArgumentException(LocaleText.format(plugin, "message_03f4eb0479e3"));
            }
            public void apply(TerrainGrid.Change change) {
                surveyedBlock(world,change.point()).setType(Material.valueOf(change.material()),false);
            }
            public boolean tryApply(TerrainGrid.Change change) {
                if(!mode.deep()) { apply(change); return true; }
                if(!(terrainActor instanceof Player actor) || !actor.isOnline() || !actor.isOp()) { terrainOtherSkips++; return false; }
                Block block=surveyedBlock(world,change.point());
                if(!change.before().equals(read(change.point()))) return false;
                if(!block.getType().isAir()) {
                    BlockBreakEvent breakEvent=new BlockBreakEvent(block,actor);
                    breakEvent.setDropItems(false); breakEvent.setExpToDrop(0);
                    plugin.dispatchBuilderBreak(breakEvent);
                    breakEvent.setDropItems(false); breakEvent.setExpToDrop(0);
                    if(breakEvent.isCancelled()) { terrainProtectionDenials++; return false; }
                    if(!change.before().equals(read(change.point()))) { terrainListenerChanges++; return false; }
                }
                Material target=Material.matchMaterial(change.material());
                if(target==null) throw new IllegalArgumentException(LocaleText.format(plugin, "message_43efbdfe75bf"));
                if(target!=Material.AIR) {
                    BlockPlaceEvent placeEvent=new BlockPlaceEvent(block,block.getState(),block.getRelative(org.bukkit.block.BlockFace.DOWN),
                            new ItemStack(target),actor,true,EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(placeEvent);
                    if(placeEvent.isCancelled() || !placeEvent.canBuild()) { terrainProtectionDenials++; return false; }
                    if(!change.before().equals(read(change.point()))) { terrainListenerChanges++; return false; }
                }
                if(!actor.isOnline() || !actor.isOp()) { terrainOtherSkips++; return false; }
                block.setType(target,false);
                return true;
            }
        };
    }

    private String terrainStageLabel(TerrainWork.Stage stage) {
        return switch(stage) {
            case SURVEY -> LocaleText.format(plugin, "message_cfd1b55a60b9");
            case PLANNING -> LocaleText.format(plugin, "message_2ff966d903bf");
            case PREFLIGHT, FINAL_CHECK -> LocaleText.format(plugin, "message_6e823221e238");
            case READY -> LocaleText.format(plugin, "message_199d15481899");
            case RECHECK -> LocaleText.format(plugin, "message_68075255c576");
            case APPLY -> LocaleText.format(plugin, "message_cfbc1121d7bc");
            case DONE -> "concluido";
            case FAILED, CANCELLED -> "interrompido";
        };
    }

    private void cancelTerrain(String reason) {
        if(terrainWork==null) return;
        terrainWork.cancel();
        if(terrainActor!=null) terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_32d63d917e19", LocaleText.text(plugin, reason), terrainWork.applied())));
        terrainWork=null; terrainToken=null; terrainActor=null; terrainArea=null;clearPos1=null;clearPos2=null;awaitingClearRegion=false;terrainCells.clear();ui.hideAll();
    }

    private void adviseDeep(CommandSender sender, boolean leveling, boolean missingGround) {
        String key = missingGround ? "terrain.advice.ground" : leveling ? "terrain.advice.level" : "terrain.advice.clean";
        sender.sendMessage(msg(LocaleText.format(plugin, key, commandPrefix())));
        sender.sendMessage(msg(LocaleText.format(plugin, "terrain.advice.warning")));
        if (!getConfig().getBoolean("terrain.deep-clean.enabled", false))
            sender.sendMessage(msg(LocaleText.format(plugin, "terrain.advice.disabled")));
    }

    void processTerrain(long deadlineNanos) {
        if (!recalled() && terrainWork!=null) tickTerrain(deadlineNanos);
    }
    int processTerrain(long deadlineNanos,int changeBudget) {
        TerrainWork work=terrainWork;
        if(recalled() || work==null) return 0;
        int before=work.processed();
        tickTerrain(deadlineNanos,changeBudget);
        return Math.max(0,work.processed()-before);
    }
    private void tickTerrain() { tickTerrain(Long.MAX_VALUE); }
    private void tickTerrain(long deadlineNanos) { tickTerrain(deadlineNanos,-1); }
    private void tickTerrain(long deadlineNanos,int requestedChangeBudget) {
        if(terrainWork==null) return;
        if(terrainActor==null || !terrainActor.hasPermission("aibuilder.use") || !plugin.controls(terrainActor,this)
                || terrainActor instanceof Player player && !player.isOnline()) { cancelTerrain(LocaleText.format(plugin, "message_a66412ae3c35")); return; }
        World world=getServer().getWorld(terrainArea.get("world").getAsString());
        if(world==null) { cancelTerrain(LocaleText.format(plugin, "message_bf43fe72da66")); return; }
        int changeBudget=terrainWork.mode().deep()
                ? Math.min(deepTerrainActionLimit(),requestedChangeBudget>0?requestedChangeBudget:deepTerrainActionLimit())
                : changeBatch();
        terrainWork.advance(terrainAccess(world,terrainWork.mode()),scanBatch(),changeBudget,System.currentTimeMillis(),deadlineNanos);
        String stage=terrainWork.stage().name()+"|"+terrainWork.paused();
        boolean stageChanged=!stage.equals(terrainNotice);
        if(stageChanged && !terrainWork.paused().isEmpty()) terrainActor.sendMessage(msg(terrainWork.paused()));
        if(!stage.equals(terrainNotice)) {
            terrainNotice=stage;
            if(terrainWork.stage()==TerrainWork.Stage.READY) {
                var plan=terrainWork.plan();
                if (terrainWork.mode()==TerrainGrid.Mode.CLEAR && terrainWork.grid().containsStructuresOrLiquids())
                    adviseDeep(terrainActor, false, false);
                String title = LocaleText.format(plugin, terrainWork.mode().level()?"terrain.easy.level-label":"terrain.easy.clear",
                        terrainWork.grid().bounds().width(),terrainWork.grid().bounds().depth());
                String summary = LocaleText.format(plugin, "terrain.easy.summary", title, plan.removed(),plan.filled())
                        + (terrainWork.mode().level()?" "+LocaleText.format(plugin,"terrain.easy.clean-first"):"")
                        + (terrainWork.mode().level()?LocaleText.format(plugin,"terrain.easy.floor",plan.level()):"");
                String warning = terrainWork.mode().deep()?LocaleText.format(plugin,"terrain.easy.destructive"):LocaleText.format(plugin,"terrain.easy.safe");
                terrainActor.sendMessage(BuilderUi.prompt(msg(summary)));
                terrainActor.sendMessage(BuilderUi.prompt(msg(warning)));
                if(terrainActor instanceof Player player) {
                    player.sendMessage(BuilderUi.terrainChoices(plugin,terrainConfirmCommand(),terrainCancelCommand()));
                    // One confirmation in chat; no second modal over the game.
                } else terrainActor.sendMessage(LocaleText.format(plugin,"message_c9bb4a7a538b",terrainConfirmCommand(),terrainCancelCommand()));
            }
        }
        if(terrainActor instanceof Player player && (stageChanged || System.currentTimeMillis()>=terrainUiAt)) {
            terrainUiAt=System.currentTimeMillis()+200;
            boolean surveying=terrainWork.stage()==TerrainWork.Stage.SURVEY;
            int done=surveying?terrainWork.grid().readCount():terrainWork.processed();
            int total=surveying?(int)terrainWork.grid().bounds().volume():terrainWork.plan()==null?0:terrainWork.plan().changes().size();
            String label=terrainWork.paused().isEmpty()?terrainStageLabel(terrainWork.stage()):terrainWork.paused();
            ui.update(player,name+" • "+label,done,total);
        }
        if(!terrainWork.terminal()) return;
        boolean selectionReady = false;
        if(terrainWork.stage()==TerrainWork.Stage.DONE) {
            if (terrainWork.skipped()>0) {
                terrainActor.sendMessage(msg(terrainWork.applied()==0
                        ? LocaleText.format(plugin, "terrain.result.blocked", terrainWork.skipped())
                        : LocaleText.format(plugin, "terrain.result.partial", terrainWork.applied(), terrainWork.skipped())));
                terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_90a11e0b1b66", terrainWork.skipped(), terrainProtectionDenials, terrainListenerChanges, terrainOtherSkips)));
            } else terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_46f1e8b12fd3", terrainWork.applied())));
            if (terrainWork.skipped()==0) {
                JsonObject next=terrainArea.deepCopy();
                if(terrainWork.mode().level()) {
                    int floor=terrainWork.plan().level()+1;
                    next=areaAfterLeveling(next,floor);
                }
                try {
                    validateArea(next);
                    for(String axis:List.of("world","x1","y1","z1","x2","y2","z2")) settings.set("zone."+axis,axis.equals("world")?next.get(axis).getAsString():next.get(axis).getAsInt());
                    settings.set("zone.enabled",true);areaUsed=false;
                    if (!saveBuilderRegistry()) throw new IllegalStateException(LocaleText.format(plugin, "message_62565011d1eb"));
                    selectionReady = true;
                    zonePos1 = null;
                    terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_1b45669578b2", zoneDescription())));
                } catch(RuntimeException tooLarge) { terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_8ba34b7f65af", LocaleText.text(plugin, tooLarge.getMessage())))); }
            }
            log("terrain",LocaleText.format(plugin, "message_0e2097600621", terrainWork.mode(), terrainWork.applied(), terrainWork.skipped()));
        } else terrainActor.sendMessage(msg(LocaleText.format(plugin, "message_301981281612", terrainWork.mode().name(), LocaleText.text(plugin, terrainWork.failure()), terrainWork.applied())));
        if (terrainWork.mode()==TerrainGrid.Mode.LEVEL && terrainWork.advice()!=TerrainGrid.Advice.NONE)
            adviseDeep(terrainActor, true, terrainWork.advice()==TerrainGrid.Advice.MISSING_GROUND);
        Player resumePlayer = selectionReady && terrainActor instanceof Player player ? player : null;
        terrainWork=null;terrainToken=null;terrainActor=null;terrainArea=null;clearPos1=null;clearPos2=null;awaitingClearRegion=false;terrainCells.clear();ui.hideAll();
        if (resumePlayer != null) resumeAfterSelection(resumePlayer);
    }

    // ---------- ferramenta (pa de diamante) para definir a zona ----------

    private NamespacedKey wandKey() {
        return new NamespacedKey(plugin, "wand");
    }

    private ItemStack makeWand() {
        ItemStack it = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§6" + LocaleText.format(plugin, "message_25454685059f", name));
        meta.setLore(List.of("§7" + LocaleText.format(plugin, "message_bb0c53e56fdb"), "§7" + LocaleText.format(plugin, "message_841a69ddc1d7")));
        meta.getPersistentDataContainer().set(wandKey(), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(builderKey(), PersistentDataType.STRING, id.toString());
        it.setItemMeta(meta);
        return it;
    }

    private boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(wandKey(), PersistentDataType.BYTE);
    }

    private void giveWand(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isWand(it) && id.toString().equals(it.getItemMeta().getPersistentDataContainer().get(builderKey(), PersistentDataType.STRING))) return;
        }
        p.getInventory().addItem(makeWand()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(msg(LocaleText.format(plugin, "message_c4942d3a544b")));
    }

    public void onWandUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!isOwnWand(e.getItem(), e.getPlayer())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("aibuilder.use") || ownerId == null || !p.getUniqueId().equals(ownerId)) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_642484479532")));
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (terrainWork != null) { p.sendMessage(msg(LocaleText.format(plugin, "message_ce78735c8d99"))); return; }
        if (awaitingClearRegion) {
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                clearPos1 = b.getLocation();
                keepAreaPreview(p);
                p.sendMessage(msg(LocaleText.format(plugin, "message_32d4f446c436", coords(clearPos1))));
            } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (clearPos1 == null) { p.sendMessage(msg(LocaleText.format(plugin, "message_5cb2f0b97f14"))); return; }
                if(!clearPos1.getWorld().equals(b.getWorld())) { p.sendMessage(msg(LocaleText.format(plugin, "message_644b3a561cc8"))); return; }
                clearPos2 = b.getLocation();
                keepAreaPreview(p);
                awaitingClearRegion = false;
                try {
                JsonObject selected=areaFor(new TerrainGrid.Bounds(Math.min(clearPos1.getBlockX(),clearPos2.getBlockX()),Math.min(clearPos1.getBlockY(),clearPos2.getBlockY()),Math.min(clearPos1.getBlockZ(),clearPos2.getBlockZ()),
                        Math.max(clearPos1.getBlockX(),clearPos2.getBlockX()),Math.min(clearPos1.getWorld().getMaxHeight()-1,extendHeight(Math.min(clearPos1.getBlockY(),clearPos2.getBlockY()),Math.max(clearPos1.getBlockY(),clearPos2.getBlockY()))),Math.max(clearPos1.getBlockZ(),clearPos2.getBlockZ())),clearPos1.getWorld().getName());
                startTerrain(p,selectionMode,selected);
                } catch(RuntimeException invalid) { p.sendMessage(msg(LocaleText.format(plugin, "message_c2b8fdbad205", LocaleText.text(plugin, invalid.getMessage())))); }
            }
            return;
        }
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            zonePos1 = b.getLocation();
            keepAreaPreview(p);
            p.sendMessage(msg(LocaleText.format(plugin, "message_47e7f283384f", coords(zonePos1))));
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (zonePos1 == null) {
                p.sendMessage(msg(LocaleText.format(plugin, "message_ef7eb8cdfa61")));
                return;
            }
            saveZone(zonePos1, b.getLocation(), p);
        }
    }

    /** Impede que a ferramenta quebre blocos (o clique serve so para marcar a zona). */
    public void onWandBreak(BlockBreakEvent e) {
        if (plugin.isBuilderBreakEvent(e)) return;
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    private void saveZone(Location a, Location b, Player p) {
        if (!a.getWorld().equals(b.getWorld())) { p.sendMessage(msg(LocaleText.format(plugin, "message_41c4914ebb8f"))); return; }
        long volume = boundedVolume((long)Math.abs(a.getBlockX() - (long)b.getBlockX()) + 1,
                (long)extendHeight(Math.min(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockY(), b.getBlockY())) - Math.min(a.getBlockY(), b.getBlockY()) + 1,
                (long)Math.abs(a.getBlockZ() - (long)b.getBlockZ()) + 1, constructionLimits().volume());
        if (volume < 0 || !constructionLimits().fits((long)Math.abs(a.getBlockX()-(long)b.getBlockX())+1,
                (long)extendHeight(Math.min(a.getBlockY(),b.getBlockY()),Math.max(a.getBlockY(),b.getBlockY()))-Math.min(a.getBlockY(),b.getBlockY())+1,
                (long)Math.abs(a.getBlockZ()-(long)b.getBlockZ())+1) || extendHeight(Math.min(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockY(), b.getBlockY())) >= a.getWorld().getMaxHeight()) {
            p.sendMessage(msg(LocaleText.format(plugin, "message_87ed852760a1"))); return;
        }
        settings.set("zone.enabled", true);
            areaUsed = false;
        settings.set("zone.world", b.getWorld().getName());
        settings.set("zone.x1", Math.min(a.getBlockX(), b.getBlockX()));
        settings.set("zone.y1", Math.min(a.getBlockY(), b.getBlockY()));
        settings.set("zone.z1", Math.min(a.getBlockZ(), b.getBlockZ()));
        settings.set("zone.x2", Math.max(a.getBlockX(), b.getBlockX()));
        settings.set("zone.y2", extendHeight(Math.min(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockY(), b.getBlockY())));
        settings.set("zone.z2", Math.max(a.getBlockZ(), b.getBlockZ()));
        if (!saveBuilderRegistry()) { p.sendMessage(msg(LocaleText.format(plugin, "message_08236a8937fc"))); return; }
        p.sendMessage(msg(LocaleText.format(plugin, "message_2f192455c212", zoneDescription())));
        keepAreaPreview(p);
        BuilderUi.preview(p, new Location(b.getWorld(), settings.getInt("zone.x1"), settings.getInt("zone.y1"), settings.getInt("zone.z1")),
                new Location(b.getWorld(), settings.getInt("zone.x2"), settings.getInt("zone.y2"), settings.getInt("zone.z2")));
        zonePos1 = null;
        resumeAfterSelection(p);
    }

    // ---------- spawn e seguir ----------

    void spawnNpc(Location loc, Player owner) {
        if (activeBuild != null) saveBuild(activeBuild, "parada");
        invalidateRequest(); clearPending(); queue.clear(); activeBuild = null; busy = false; ui.hideAll();
        walker.stop(npc);
        if (npc != null && !npc.isDead()) npc.remove();
        ownerId = owner.getUniqueId();
        World w = loc.getWorld();
        npc = w.spawn(loc, Mannequin.class, this::configureNpc);
        applyBuilderSkin(owner);
        if(quickBuildMode) beginQuickBuildHover();
        if (!saveBuilderRegistry()) throw new IllegalStateException(LocaleText.format(plugin, "message_191b0818abfb"));
        giveWand(owner);
        BuilderUi.followControls(plugin, owner, settings.getBoolean("follow-owner", true), commandPrefix());
    }

    private void configureNpc(Mannequin m) {
        m.setCustomName(name);
        m.setCustomNameVisible(true);
        m.setImmovable(false);
        m.setGravity(true);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
        // Mannequin is a LivingEntity, not a Mob: equipment drop-chance setters throw.
        m.setInvulnerable(true);
        m.setPersistent(true);
        // Identidade estavel do construtor: independente de recriacoes da entidade.
        m.getPersistentDataContainer().set(npcKey(), PersistentDataType.STRING, id.toString());
    }

    private NamespacedKey npcKey() { return new NamespacedKey(plugin, "builder-npc"); }

    /** Contorno persistente: para quem segura a pa, e para o criador enquanto a obra nao termina. */
    void showAreaMarkers() {
        boolean building = activeBuild != null || pendingActions != null || !queue.isEmpty() || terrainWork != null;
        World zoneWorld = getServer().getWorld(settings.getString("zone.world", ""));
        boolean hasZone = settings.getBoolean("zone.enabled", false) && zoneWorld != null;
        Location zone1 = hasZone ? new Location(zoneWorld, settings.getInt("zone.x1"), settings.getInt("zone.y1"), settings.getInt("zone.z1")) : null;
        Location zone2 = hasZone ? new Location(zoneWorld, settings.getInt("zone.x2"), settings.getInt("zone.y2"), settings.getInt("zone.z2")) : null;
        for (Player p : getServer().getOnlinePlayers()) {
            boolean isOwner = p.getUniqueId().equals(ownerId);
            boolean holding = isOwner && isOwnWand(p.getInventory().getItemInMainHand(), p);
            boolean recentSelection = isOwner && System.currentTimeMillis() < areaPreviewUntil;
            if (!holding && !(building && isOwner) && !recentSelection) continue;
            if (clearPos1 != null && clearPos2 != null) {
                int minY = Math.min(clearPos1.getBlockY(), clearPos2.getBlockY());
                int maxY = Math.max(clearPos1.getBlockY(), clearPos2.getBlockY());
                if (terrainWork != null) {
                    minY = terrainWork.grid().bounds().y1();
                    maxY = terrainWork.grid().bounds().y2();
                }
                World markerWorld = clearPos1.getWorld();
                int floorMinY = minY, floorMaxY = maxY;
                BuilderUi.preview(p, clearPos1, clearPos2,
                        (x, z) -> markerSurfaceY(markerWorld, x, z, floorMinY, floorMaxY));
            } else if (zonePos1 != null) {
                World markerWorld = zonePos1.getWorld();
                int floorY = zonePos1.getBlockY();
                BuilderUi.preview(p, zonePos1, zonePos1,
                        (x, z) -> markerSurfaceY(markerWorld, x, z, floorY, floorY));
            } else if (zone1 != null) {
                int floorMinY = zone1.getBlockY(), floorMaxY = zone2.getBlockY();
                if (terrainWork != null) {
                    floorMinY = terrainWork.grid().bounds().y1();
                    floorMaxY = terrainWork.grid().bounds().y2();
                }
                int markerMinY = floorMinY, markerMaxY = floorMaxY;
                BuilderUi.preview(p, zone1, zone2,
                        (x, z) -> markerSurfaceY(zoneWorld, x, z, markerMinY, markerMaxY));
            }
        }
    }

    private int markerSurfaceY(World world, int x, int z, int minY, int maxY) {
        // Never load chunks just to draw the outline; use the current surface where available.
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return minY;
        int surface = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return Math.max(minY, Math.min(maxY, surface));
    }

    private boolean isIgnoredTerrainMob(org.bukkit.entity.Entity entity) {
        return entity instanceof org.bukkit.entity.Animals
                || entity instanceof org.bukkit.entity.WaterMob
                || entity instanceof org.bukkit.entity.Ambient
                || entity instanceof org.bukkit.entity.Enemy;
    }

    private boolean isBuilderNpc(Mannequin m) {
        return id.toString().equals(m.getPersistentDataContainer().get(npcKey(), PersistentDataType.STRING));
    }

    /** Remove somente entidades com a identidade deste construtor. */
    int removeBuilderEntities(CommandSender sender) {
        Map<UUID, Mannequin> found = new HashMap<>();
        if (npc != null && !npc.isDead()) found.put(npc.getUniqueId(), npc);
        for (World w : getServer().getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (isBuilderNpc(m)) found.put(m.getUniqueId(), m);
            }
        }
        if (sender instanceof Player p) {
            Location at = p.getLocation();
            World w = at.getWorld();
            int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (!w.isChunkLoaded(cx + dx, cz + dz) && !w.loadChunk(cx + dx, cz + dz, false)) continue;
                    for (var e : w.getChunkAt(cx + dx, cz + dz).getEntities()) {
                        if (e instanceof Mannequin m && isBuilderNpc(m)) found.put(m.getUniqueId(), m);
                    }
                }
            }
        }
        for (Mannequin m : found.values()) if (!m.isDead()) m.remove();
        return found.size();
    }

    /** Ha trabalho em andamento que impede apagar o construtor. Obra aberta (salva em disco) nao conta. */
    boolean isActive() {
        return busy || requests.isThinking() || pendingActions != null || !queue.isEmpty() || terrainWork != null;
    }

    private boolean isBoundWand(ItemStack it) {
        return isWand(it) && id.toString().equals(it.getItemMeta().getPersistentDataContainer().get(builderKey(), PersistentDataType.STRING));
    }

    /** Remove a pa deste construtor dos inventarios online e do chao. Retorna quantas removeu. */
    int removeWands() {
        int removed = 0;
        for (Player p : getServer().getOnlinePlayers()) removed += BuilderTools.remove(p, this::isBoundWand);
        for (World w : getServer().getWorlds()) {
            for (var item : w.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                if (isBoundWand(item.getItemStack())) { item.remove(); removed++; }
            }
        }
        return removed;
    }

    Path dataDirectory() { return plugin.getDataFolder().toPath().resolve("builders").resolve(id.toString()); }
    Path builderFile() { return dataDirectory().resolve("builder.json"); }

    boolean saveBuilderRegistry() {
        if (ownerId == null) return false;
        try {
            JsonObject o = record == null ? new JsonObject() : record.deepCopy();
            if (npc != null && !npc.isDead()) {
                Location l = npc.getLocation();
                o.addProperty("npc", npc.getUniqueId().toString());
                o.addProperty("world", l.getWorld().getName());
                o.addProperty("x", l.getX()); o.addProperty("y", l.getY()); o.addProperty("z", l.getZ());
            }
            if (!o.has("npc")) return false;
            o.addProperty("id", id.toString()); o.addProperty("name", name);
            o.addProperty("owner", ownerId.toString());
            o.addProperty("follow", settings.getBoolean("follow-owner", true));
            o.addProperty("quickBuildMode", quickBuildMode);
            o.addProperty("travelRadius", travelRadius());
            o.addProperty("recallHold", recalled());
            if(entranceDirection()!=null)o.addProperty("entranceDirection",entranceDirection());
            else o.remove("entranceDirection");
            o.addProperty("areaUsed", areaUsed);
            JsonObject zone = new JsonObject();
            var section = settings.getConfigurationSection("zone");
            if (section != null) for (var entry : section.getValues(false).entrySet())
                zone.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
            o.add("zone", zone);
            Files.createDirectories(dataDirectory());
            writeAtomic(builderFile(), gson.toJson(o));
            record = o;
            return true;
        } catch (IOException | RuntimeException e) {
            getLogger().warning(LocaleText.format(plugin, "message_ae41eeddd855", name, LocaleText.text(plugin, e.getMessage())));
            return false;
        }
    }

    boolean deleteBuilderRegistry() {
        try { Files.deleteIfExists(builderFile()); return true; }
        catch (IOException e) { getLogger().warning(LocaleText.format(plugin, "message_7b84f3690508", name, LocaleText.text(plugin, e.getMessage()))); return false; }
    }

    private String lastBuildStatus() {
        try {
            Path p = buildsDir().resolve("_ultima.json");
            if (!Files.exists(p)) return "";
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().get("status").getAsString();
        } catch (IOException | RuntimeException e) { return ""; }
    }

    /** Religa o dono e o NPC salvos. Se o NPC nao estiver carregado, cria outro no mesmo lugar. */
    void restoreBuilder() {
        if (npc != null && !npc.isDead() || record == null) return;
        try {
            JsonObject o = record;
            World w = getServer().getWorld(o.get("world").getAsString());
            if (w == null) { getLogger().warning(LocaleText.format(plugin, "message_5a221d8b27d4", name)); return; }
            UUID npcId = UUID.fromString(o.get("npc").getAsString());
            Location loc = new Location(w, o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
            Mannequin found = null;
            if (getServer().getEntity(npcId) instanceof Mannequin m && !m.isDead()) found = m;
            if (found == null) for (org.bukkit.entity.Entity entity : w.getChunkAt(loc).getEntities()) {
                if (entity instanceof Mannequin m && !m.isDead() && isBuilderNpc(m)) { found = m; break; }
            }
            npc = found != null ? found : w.spawn(loc, Mannequin.class, this::configureNpc);
            configureNpc(npc); // upgrades legacy UUID tags to the stable builder id
            if(quickBuildMode) beginQuickBuildHover();
            applyBuilderSkin(getServer().getConsoleSender());
            if (activeBuild == null && "aberta".equals(lastBuildStatus())) activeBuild = loadSavedBuild(getServer().getConsoleSender(), null);
            saveBuilderRegistry();
            getLogger().info(name + " restaurado (" + (found != null ? "NPC existente" : "NPC recriado") + ").");
        } catch (RuntimeException e) { getLogger().warning(LocaleText.format(plugin, "message_5e8b64155018", name, e)); }
    }

    void applyBuilderSkin(CommandSender sender) {
        if (npc == null || npc.isDead()) return;
        try { BuilderAppearance.apply(npc, getConfig().getString("skin.texture", BuilderAppearance.DEFAULT_TEXTURE)); }
        catch (RuntimeException invalid) {
            getLogger().warning(LocaleText.format(plugin, "message_647376a8cacd"));
            BuilderAppearance.apply(npc, BuilderAppearance.DEFAULT_TEXTURE);
            sender.sendMessage(msg(LocaleText.format(plugin, "message_ec1e5a0599f1")));
        }
    }

    private void followMode(CommandSender sender, boolean follow) {
        boolean wasHeld = recalled(), wasFollowing = followsOwner();
        if (follow) settings.set("recall-hold", false);
        settings.set("follow-owner", follow);
        if (!saveBuilderRegistry()) {
            settings.set("recall-hold", wasHeld);
            settings.set("follow-owner", wasFollowing);
            sender.sendMessage(msg(LocaleText.format(plugin, "message_97c06b53f710"))); return;
        }
        movementNotice = "";
        // Stay changes idle following only. A confirmed construction keeps priority.
        if (queue.isEmpty()) walker.stop(npc);
        sender.sendMessage(msg(follow ? LocaleText.format(plugin, "message_bcd7da39fac0")
                : LocaleText.format(plugin, "message_b4f5983345bf")));
        if (sender instanceof Player player) BuilderUi.followControls(plugin, player, follow, commandPrefix());
    }

    /** Build > Continue can release a call pause while preserving the chosen Follow/Stay mode. */
    private boolean releaseRecallHold(CommandSender sender) {
        if (!recalled()) return true;
        settings.set("recall-hold", false);
        if (!saveBuilderRegistry()) {
            settings.set("recall-hold", true);
            sender.sendMessage(msg(LocaleText.format(plugin, "message_97c06b53f710")));
            return false;
        }
        movementNotice = "";
        nextMovementAttemptAt = 0;
        sender.sendMessage(msg(LocaleText.format(plugin, "builder.continue-after-call")));
        return true;
    }

    private void distanceCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_732af37b7522", travelRadius()))); return;
        }
        int radius;
        try {
            if (args.length != 2) throw new NumberFormatException();
            radius = Integer.parseInt(args[1]);
            if (radius < 4 || radius > 64) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            sender.sendMessage(msg(LocaleText.format(plugin, "message_3369745b3b93"))); return;
        }
        Object previous = settings.get("travel-radius");
        settings.set("travel-radius", radius);
        if (!saveBuilderRegistry()) {
            settings.set("travel-radius", previous);
            sender.sendMessage(msg(LocaleText.format(plugin, "message_f3808456f45b"))); return;
        }
        walker.stop(npc);
        sender.sendMessage(msg(LocaleText.format(plugin, "message_946cf906898a", radius)));
    }

    void recallCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_07c6d68d4778"))); return; }
        if (!player.hasPermission("aibuilder.use") || !plugin.controls(player, this)) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_b29236594648"))); return;
        }
        // A recall must not move somebody else's builder away from its owner's limit.
        if (!player.getUniqueId().equals(ownerId)) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_b4ec75397579"))); return;
        }
        if (npc == null || npc.isDead() || !npc.isValid()) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_9edaa40352a8"))); return;
        }
        Location destination = BuilderTravel.recallSpot(player.getLocation(), spot -> {
            var border = spot.getWorld().getWorldBorder();
            if (!border.isInside(spot) || !border.isInside(spot.clone().add(.3, 0, .3))
                    || !border.isInside(spot.clone().add(-.3, 0, -.3))) return false;
            return ClaimProtection.canAccess(getServer().getPluginManager(), spot, player);
        }, body -> player.getWorld().getNearbyEntities(body).stream()
                .anyMatch(entity -> entity != npc && !entity.isDead()));
        if (destination == null) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_81f00f97d100"))); return;
        }
        finishRecall(player, destination);
    }

    private void finishRecall(Player player, Location destination) {
        // Paper may cancel the teleport. In that case preserve the current work and route.
        if (!npc.teleport(destination)) { player.sendMessage(msg(LocaleText.format(plugin, "message_b4de09ba649d"))); return; }
        walker.stop(npc);
        if(quickBuildMode) beginQuickBuildHover(); else walker.land(npc);
        npc.setVelocity(new org.bukkit.util.Vector());
        npc.setFallDistance(0);
        analysisAnchor = null;
        settings.set("recall-hold", true);
        ui.hideAll();
        movementNotice = LocaleText.format(plugin, "message_8d2996c7c149");
        boolean saved = saveBuilderRegistry();
        player.sendMessage(msg(LocaleText.format(plugin, "message_93dcafb93abc", commandPrefix()) + (saved ? "" : " Nao consegui salvar a nova posicao; confira o disco.")));
    }

    // ---------- analise visual enquanto o pedido e planejado ----------
    private Location analysisAnchor;
    private int analysisTicks, nextWanderAt;

    /** Anda pouco perto do ponto em que comecou a analisar e alterna o olhar entre o chao e a obra. */
    private void analysisStep() {
        if (analysisAnchor == null || !analysisAnchor.getWorld().equals(npc.getWorld())) {
            analysisAnchor = npc.getLocation().clone(); analysisTicks = 0; nextWanderAt = 20;
        }
        analysisTicks++;
        if (walker.state() != NpcWalker.State.WALKING && analysisTicks >= nextWanderAt) {
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            Location target = analysisAnchor.clone().add(rnd.nextInt(-3, 4), 0, rnd.nextInt(-3, 4));
            walker.request(npc, target, .4, .12, l -> l.distanceSquared(analysisAnchor) <= 25
                    && !inSpawnZone(l) && !claimed(l.getBlock()) && freeOfBuild(l));
            nextWanderAt = analysisTicks + rnd.nextInt(60, 121);
        }
        walker.tick(npc);
        if (walker.state() == NpcWalker.State.WALKING) return;
        float sway = (float) (20 * Math.sin(analysisTicks / 70.0 * 2 * Math.PI));
        npc.setRotation(analysisAnchor.getYaw() + sway, NpcWalker.lookPitch(analysisTicks));
        npc.setBodyYaw(analysisAnchor.getYaw());
    }

    // ---------- combate: machado de netherite contra monstros hostis ----------
    // Alvos: apenas org.bukkit.entity.Enemy (monstros). Jogadores, NPCs e animais nunca sao alvo.
    private double combatRange() { return boundedDouble("combat.target-range", 8, 2, 16); }
    private double strikeReach() { return boundedDouble("combat.strike-reach", 3.2, 1, 4.5); }
    private int strikeCooldownTicks() { return boundedInt("combat.cooldown-ticks", 20, 1, 1200); }
    private static final double MAX_STRIKE_DAMAGE = 1_000_000;
    private int strikeCooldown;

    private double strikeDamage() {
        double configured = getConfig().getDouble("combat.damage", MAX_STRIKE_DAMAGE);
        return Double.isFinite(configured) && configured > 0 ? Math.min(configured, MAX_STRIKE_DAMAGE) : MAX_STRIKE_DAMAGE;
    }

    /** Monstro hostil mais perto do construtor ou do criador (raio de 8 blocos). Nunca jogadores. */
    private org.bukkit.entity.LivingEntity hostileNear() {
        Player owner = ownerPlayer();
        double range = combatRange();
        List<org.bukkit.entity.Entity> pool = new ArrayList<>(npc.getNearbyEntities(range, range, range));
        if (owner != null && owner.getWorld().equals(npc.getWorld()))
            pool.addAll(owner.getNearbyEntities(range, range, range));
        org.bukkit.entity.LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e : pool) {
            if (!(e instanceof org.bukkit.entity.Enemy living) || e instanceof Player || living.isDead()) continue;
            if (!withinTravelLimit(living.getLocation())) continue;
            double d = living.getLocation().distanceSquared(npc.getLocation());
            if (d < bestD) { bestD = d; best = living; }
        }
        return best;
    }

    /**
     * Ataca monstros perto do construtor ou do criador. Com a fila vazia, anda ate o alvo; com a fila ocupada,
     * so bate se o alvo ja estiver no alcance (nao mexe na movimentacao da obra). Retorna true se andou para lutar.
     */
    private boolean combatStep() {
        if (!getConfig().getBoolean("combat.enabled", true)) return false;
        if (strikeCooldown > 0) strikeCooldown--;
        if (terrainWork != null) return false;
        org.bukkit.entity.LivingEntity target = hostileNear();
        if (target == null) return false;
        double dist = target.getLocation().distance(npc.getLocation());
        double reach = strikeReach();
        if (dist <= reach && npc.hasLineOfSight(target)) {
            Location look = npc.getLocation().setDirection(target.getLocation().toVector().subtract(npc.getLocation().toVector()));
            npc.setRotation(look.getYaw(), look.getPitch());
            if (strikeCooldown == 0) {
                // Mannequin's native attack must not determine whether a hit deals damage.
                // Keep Bukkit damage events, attribution, armour and protection plugins in the loop.
                target.damage(strikeDamage(), npc);
                npc.swingMainHand();
                strikeCooldown = strikeCooldownTicks();
            }
            if (queue.isEmpty()) walker.stop(npc);
            return queue.isEmpty();
        }
        if (!queue.isEmpty()) return false; // a obra segue sua propria movimentacao
        walker.request(npc, target.getLocation(), Math.max(.35, reach - .8), Math.min(.28, walkingSpeed() * 1.4),
                l -> !inSpawnZone(l) && !claimed(l.getBlock()));
        walker.tick(npc);
        return walker.state() == NpcWalker.State.WALKING;
    }

    /** Se o construtor ficou dentro de um bloco (ex.: depois de uma obra), sobe ate achar espaco livre. */
    private void unstick() {
        Location l = npc.getLocation();
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        if (NpcWalker.clear(w, x, l.getBlockY(), z) && NpcWalker.clear(w, x, l.getBlockY() + 1, z)) return;
        for (int up = 1; up <= 3; up++) {
            int y = l.getBlockY() + up;
            Location safeSpot = new Location(w, x + .5, y, z + .5);
            if ((queue.isEmpty() ? withinTravelLimit(safeSpot) : insideWorkMovementBounds(safeSpot))
                    && NpcWalker.clear(w, x, y, z) && NpcWalker.clear(w, x, y + 1, z)
                    && !plannedAt(x, y, z)
                    && freeOfBuild(new Location(w, x + .5, y, z + .5))) {
                walker.stop(npc);
                npc.teleport(new Location(w, x + .5, y, z + .5, l.getYaw(), l.getPitch()));
                return;
            }
        }
    }

    private boolean asideActive;

    /** Quando um jogador encosta no construtor parado, ele anda 2-3 blocos para o lado. Retorna true enquanto anda. */
    private boolean stepAside() {
        if (asideActive) {
            walker.tick(npc);
            if (walker.state() == NpcWalker.State.WALKING) return true;
            asideActive = false;
            return false;
        }
        if (busy || requests.isThinking() || pendingActions != null || terrainWork != null || !queue.isEmpty()) return false;
        Location me = npc.getLocation();
        for (Player p : me.getWorld().getPlayers()) {
            Location pl = p.getLocation();
            double dx = me.getX() - pl.getX(), dz = me.getZ() - pl.getZ();
            if (Math.abs(pl.getY() - me.getY()) > 1.5 || dx * dx + dz * dz > 1.6 * 1.6) continue;
            double len = Math.max(.01, Math.sqrt(dx * dx + dz * dz));
            Location target = me.clone().add(dx / len * 2.5, 0, dz / len * 2.5);
            walker.request(npc, target, .4, walkingSpeed(), l -> !inSpawnZone(l) && !claimed(l.getBlock()) && freeOfBuild(l));
            asideActive = true;
            walker.tick(npc);
            return true;
        }
        return false;
    }

    void followOwner() {
        if (npc == null || npc.isDead() || ownerId == null) return;
        if(selfEscapeOrigin!=null) {
            if(recalled()) { selfEscapeOrigin=null; walker.stop(npc); }
            else { tickBuilderSelfClear(); return; }
        }
        if(quickBuildMode) { maintainQuickBuildHover(); return; }
        // A confirmed `move` action is dequeued as soon as its route starts. Keep treating that
        // route as work until the walker arrives; otherwise the idle owner leash stops it next tick.
        boolean workMovement = activeBuild != null && walker.hasGoal();
        boolean working = !queue.isEmpty() || workMovement;
        walker.setBoundary(working ? this::insideWorkMovementBounds : this::withinTravelLimit);
        if (recalled() || (!working && !withinTravelLimit(npc.getLocation()))) {
            walker.stop(npc); walker.land(npc);
            movementNotice = recalled() ? LocaleText.format(plugin, "message_fb802873e1c4") : LocaleText.format(plugin, "message_c8733b56b76c");
            return;
        }
        if (movementNotice.startsWith(LocaleText.format(plugin, "message_f9c4e2793b5a"))) movementNotice = "";
        unstick();
        if (combatStep()) return;
        if (working) {
            walker.tick(npc);
            return;
        }
        if (requests.isThinking() && terrainWork == null && !busy && pendingActions == null) { analysisStep(); return; }
        analysisAnchor = null;
        if (stepAside()) return;
        if (terrainWork != null || !settings.getBoolean("follow-owner", true) || busy || activeBuild != null
                || pendingActions != null || requests.isThinking()) { walker.stop(npc); return; }
        Player owner = ownerPlayer();
        if (owner == null) { walker.stop(npc); return; }
        if (!owner.getWorld().equals(npc.getWorld())) {
            walker.stop(npc); movementNotice = LocaleText.format(plugin, "message_e8b6b79f8d98"); return;
        }
        double distance = Math.max(1.5, Math.min(12, getConfig().getDouble("follow-distance", 3)));
        if (owner.getLocation().distanceSquared(npc.getLocation()) > 64 * 64) {
            walker.stop(npc); movementNotice = LocaleText.format(plugin, "message_9ce075200d10"); return;
        }
        walker.request(npc, owner.getLocation(), distance, walkingSpeed(), location ->
                location.getWorld().getWorldBorder().isInside(location));
        walker.tick(npc);
        movementNotice = walker.state() == NpcWalker.State.BLOCKED ? LocaleText.format(plugin, "message_aa218ad42c81") : "";
    }

    private double walkingSpeed() { return getConfig().getDouble("walk-speed", .20); }

    /** Saudacao social quando o criador entra no servidor. */
    public void onJoin(PlayerJoinEvent e) {
        if (npc == null || npc.isDead() || ownerId == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(ownerId)) return;
        ownerLeftAt = null;
        if (waitingMaterials()) setWaitingMaterials(false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (npc == null || npc.isDead() || !p.isOnline()) return;
            npc.swingMainHand();
            p.sendMessage(BuilderUi.prompt(msg(LocaleText.format(plugin, "message_f1709ec9a1de", p.getName()))));
        }, 40L);
    }

    private Player ownerPlayer() {
        return ownerId == null ? null : getServer().getPlayer(ownerId);
    }

    private void say(String text) {
        Player owner = ownerPlayer();
        if (owner != null && getConfig().getBoolean("announce-actions", true)) {
            owner.sendMessage(msg(text));
        }
    }

    private String msg(String text) {
        String plain = LocaleText.text(plugin, text).replaceAll("§[0-9A-FK-ORa-fk-or]", "");
        return "[" + name + "] " + BuilderCommands.inMessage(plain.replace("/construtor", commandPrefix()).replace("@construtor", "@builder"));
    }

    // ---------- controle parental ----------

    private ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "Europe/Luxembourg"));
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Horario permitido e limite diario por jogador. Envia o motivo ao jogador se bloquear. */
    private boolean parentalAllows(Player player) {
        String hours = getConfig().getString("parental.allowed-hours", "0-24");
        int from = 0, to = 24;
        try {
            String[] hs = hours.split("-");
            from = Integer.parseInt(hs[0].trim());
            to = Integer.parseInt(hs[1].trim());
        } catch (RuntimeException ignored) {
            // formato invalido: considera o dia todo
        }
        int h = LocalTime.now(zone()).getHour();
        boolean open = from <= to ? (h >= from && h < to) : (h >= from || h < to);
        if (!open) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_1df17b10ef8d", from, to)));
            return false;
        }
        int limit = getConfig().getInt("parental.daily-limit", 0);
        try {
            if (plugin.dailyQuota == null) throw new IOException("daily quota is unavailable");
            if (plugin.dailyQuota.consume(player.getUniqueId(), LocalDate.now(zone()), limit)) return true;
            player.sendMessage(msg(LocaleText.format(plugin, "message_f4447b8bf807", limit)));
        } catch (IOException failure) {
            getLogger().warning("AI daily quota could not be saved: " + failure.getMessage());
            player.sendMessage(msg(LocaleText.format(plugin, "message_338c817305d6")));
        }
        return false;
    }

    void parentalCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_49860541acdb"))); return; }
        String op = args.length > 1 ? args[1].toLowerCase() : "ver";
        switch (op) {
            case "limite" -> {
                if (args.length < 3) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_971a601c60cd"))); return; }
                try {
                    int n = Math.max(0, Integer.parseInt(args[2]));
                    getConfig().set("parental.daily-limit", n);
                    saveConfig();
                    sender.sendMessage(msg("limite diario por jogador: " + (n == 0 ? "sem limite" : n + " pedidos")));
                } catch (NumberFormatException e) {
                    sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_6a6a4d895496")));
                }
            }
            case "horario" -> {
                if (args.length < 4) { sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_f52564f4a011"))); return; }
                try {
                    int a = Integer.parseInt(args[2]), b = Integer.parseInt(args[3]);
                    if (a < 0 || a > 23 || b < 0 || b > 24 || a == b) throw new NumberFormatException();
                    getConfig().set("parental.allowed-hours", a + "-" + b);
                    saveConfig();
                    sender.sendMessage(msg(LocaleText.format(plugin, "message_51253b5982ac", a, b)));
                } catch (NumberFormatException e) {
                    sender.sendMessage(LocaleText.text(plugin, LocaleText.format(plugin, "message_e7b314b3fd6a")));
                }
            }
            case "historico" -> {
                int n = 15;
                if (args.length > 2) try { n = Math.max(1, Math.min(100, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { }
                sender.sendMessage(msg(LocaleText.format(plugin, "message_d0e392c462e5", n)));
                for (String line : tailLines(n)) sender.sendMessage(LocaleText.text(plugin, "§8" + line));
            }
            default -> sender.sendMessage(msg(LocaleText.format(plugin, "message_0f13cef8d81d", getConfig().getString("parental.allowed-hours", "0-24"), getConfig().getInt("parental.daily-limit", 0))));
        }
    }

    private List<String> tailLines(int n) {
        try {
            Path f = getDataFolder().toPath().resolve("actions.log");
            if (!Files.exists(f)) return List.of(LocaleText.format(plugin, "message_5c128229c126"));
            Deque<String> last = new ArrayDeque<>();
            try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
                lines.forEach(line -> { if (last.size() == n) last.removeFirst(); last.addLast(line); });
            }
            return List.copyOf(last);
        } catch (IOException e) {
            return List.of(LocaleText.format(plugin, "message_1cc187fbced3", LocaleText.text(plugin, e.getMessage())));
        }
    }

    // ---------- missoes ----------

    private List<Map<?, ?>> missions() {
        return getConfig().getMapList("missions");
    }

    private String missionStatus() {
        List<Map<?, ?>> ms = missions();
        if (ms.isEmpty()) return LocaleText.format(plugin, "message_61a4e22b138e");
        if (missionIndex >= ms.size()) return LocaleText.format(plugin, "message_4c05b70a3fe2");
        Map<?, ?> m = ms.get(missionIndex);
        return LocaleText.format(plugin, "message_86b304ee7f18", missionIndex + 1, LocaleText.text(plugin, String.valueOf(m.get("description"))), missionProgress, ((Number) m.get("goal")).intValue());
    }

    private void onBlocksPlaced(int count) {
        List<Map<?, ?>> ms = missions();
        if (ms.isEmpty() || missionIndex >= ms.size()) return;
        missionProgress += count;
        Player owner = ownerPlayer();
        while (missionIndex < ms.size()) {
            Map<?, ?> m = ms.get(missionIndex);
            int goal = ((Number) m.get("goal")).intValue();
            if (missionProgress < goal) break;
            missionProgress -= goal;
            String rewardName = String.valueOf(m.get("reward-material"));
            int amount = m.containsKey("reward-amount") ? ((Number) m.get("reward-amount")).intValue() : 1;
            Material reward = Material.matchMaterial(rewardName);
            if (owner != null) {
                owner.sendMessage(msg(LocaleText.format(plugin, "message_8f5c167cbeb1", LocaleText.text(plugin, String.valueOf(m.get("description"))))));
                if (reward != null) {
                    for (ItemStack rest : owner.getInventory().addItem(new ItemStack(reward, amount)).values())
                        owner.getWorld().dropItemNaturally(owner.getLocation(), rest);
                    owner.sendMessage(msg(LocaleText.format(plugin, "message_cd759af5db36", amount, reward.name())));
                }
            }
            missionIndex++;
            log("mission", LocaleText.format(plugin, "message_09509bfbcdc4", missionIndex, rewardName, amount));
        }
        if (missionIndex >= ms.size()) missionProgress = 0;
        saveProgress();
    }

    void loadProgress() {
        try {
            Path f = dataDirectory().resolve("progress.json");
            if (!Files.exists(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            missionIndex = o.get("missionIndex").getAsInt();
            missionProgress = o.get("missionProgress").getAsInt();
        } catch (IOException | RuntimeException e) {
            getLogger().warning(LocaleText.format(plugin, "message_9ed1758e8ab1", LocaleText.text(plugin, e.getMessage())));
        }
    }

    private void saveProgress() {
        try {
            Files.createDirectories(dataDirectory());
            JsonObject o = new JsonObject();
            o.addProperty("missionIndex", missionIndex);
            o.addProperty("missionProgress", missionProgress);
            Files.writeString(dataDirectory().resolve("progress.json"), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning(LocaleText.format(plugin, "message_4e4e03dc0e02", LocaleText.text(plugin, e.getMessage())));
        }
    }

    // ---------- chat -> IA ----------

    void receiveRequest(Player player, String request) {
        if (!player.isOnline() || !player.hasPermission("aibuilder.use") || !player.getUniqueId().equals(ownerId)) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_427548c8b158"))); return;
        }
        if (npc == null || npc.isDead()) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_dc0ea5e6a81d")));
            return;
        }
        // Apenas esta etapa na thread principal acessa mundo, permissoes e estado.
        if (!canRequest(player)) return;
        // modo tutor: explica sem construir
        String lower = request.toLowerCase();
        boolean tutor = lower.startsWith("ensina") || lower.startsWith("explica") || lower.startsWith("teach") || lower.startsWith("explain");
        // com obra aberta, o pedido e um incremento: usa a area fixa da obra
        Build open = tutor ? null : activeBuild;
        boolean increment = open != null;
        if (increment && !missingBlocks(open).isEmpty()) {
            player.sendMessage(msg(LocaleText.format(plugin, "message_70b66dfcd4e4", open.name))); return;
        }
        // o mapa precisa ser lido na thread principal (acesso aos blocos)
        if (!tutor && !increment && awaitSelection(player, request)) return;
        clearSelectionRequest();
        if (!parentalAllows(player)) return;
        if (!tutor && !increment) player.sendMessage(msg(LocaleText.format(plugin, "message_ab3dc3554d17", zoneDescription())));
        JsonObject area = tutor ? null : increment ? open.area.deepCopy() : currentAreaJson().deepCopy();
        if (area != null) {
            try {
                validateArea(area);
                if (!npc.getWorld().getName().equals(area.get("world").getAsString())) throw new IllegalArgumentException(LocaleText.format(plugin, "message_b0e9e3e7aeeb"));
            } catch (RuntimeException invalid) { player.sendMessage(msg(invalid.getMessage())); return; }
        }
        String position = tutor ? "" : positionLine(player, area);
        String buildCtx = increment ? buildContext(open) : "";
        List<JsonObject> savedTurns = history.getOrDefault(player.getUniqueId(), List.of());
        List<JsonObject> turns = savedTurns.subList(Math.max(0, savedTurns.size() - historyMessageLimit()), savedTurns.size())
                .stream().map(JsonObject::deepCopy).toList();
        String model = getConfig().getString("model", "deepseek/deepseek-v4-flash-0731");
        String apiUrl = getConfig().getString("api-url");
        String apiKey = getConfig().getString("api-key");
        if (apiKey == null || apiKey.isBlank() || apiKey.toLowerCase(java.util.Locale.ROOT).contains("cole_sua_chave")
                || apiKey.toLowerCase(java.util.Locale.ROOT).contains("your_api_key")) {
            String missingKey = LocaleText.english(plugin)
                    ? LocaleText.format(plugin, "message_6919a667c743")
                    : LocaleText.format(plugin, "message_a7767a325db3");
            player.sendMessage(msg(missingKey));
            return;
        }
        int maxActions = maxAiActions();
        int maxResponseTokens = boundedInt("ai.max-response-tokens", 8000, 256, 16000);
        int requestTimeoutSeconds = boundedInt("ai.request-timeout-seconds", 90, 10, 300);
        int configuredActionReach = actionReach();
        int configuredMoveRange = moveTargetRange();
        long token = requests.begin();
        ui.update(player, name + " • " + (tutor ? "Preparando sua explicacao..." : increment ? "Planejando o incremento..." : "Planejando sua construcao..."), 0, 0);
        java.util.function.Consumer<String> dispatch = terrain -> Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> askAI(player, request, terrain, position, tutor, area, turns, model, apiUrl, apiKey, token, buildCtx,
                        increment, maxActions, maxResponseTokens, requestTimeoutSeconds, configuredActionReach, configuredMoveRange));
        if(tutor) dispatch.accept(""); else surveyArea(player,area,token,dispatch);
    }

    /** Resumo da obra aberta enviado a IA: o que ja existe, por material e onde, e as etapas anteriores. */
    private String buildContext(Build b) {
        Map<String, int[]> boxes = new java.util.TreeMap<>(); // material -> {count, minX, minY, minZ, maxX, maxY, maxZ}
        for (BlueprintCompiler.Block blk : b.blocks) {
            int[] s = boxes.computeIfAbsent(blk.material(), k -> new int[]{0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
            s[0]++;
            s[1] = Math.min(s[1], blk.x()); s[2] = Math.min(s[2], blk.y()); s[3] = Math.min(s[3], blk.z());
            s[4] = Math.max(s[4], blk.x()); s[5] = Math.max(s[5], blk.y()); s[6] = Math.max(s[6], blk.z());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("OBRA ABERTA: \"").append(b.name).append("\" com ").append(b.blocks.size())
          .append(" blocos ja construidos. A area e fixa; nao refaca a estrutura.\n");
        sb.append("Blocos existentes por material (contagem e caixa x,y,z):\n");
        boxes.entrySet().stream().sorted((p, q) -> Integer.compare(q.getValue()[0], p.getValue()[0])).limit(25).forEach(en -> {
            int[] s = en.getValue();
            sb.append("- ").append(en.getKey()).append(": ").append(s[0]).append(" blocos, x=").append(s[1]).append("..").append(s[4])
              .append(" y=").append(s[2]).append("..").append(s[5]).append(" z=").append(s[3]).append("..").append(s[6]).append('\n');
        });
        if (!b.steps.isEmpty()) {
            sb.append("Incrementos anteriores ja feitos:\n");
            List<String> recent = b.steps.subList(Math.max(0, b.steps.size() - 10), b.steps.size());
            for (String s : recent) sb.append("- ").append(s).append('\n');
        }
        return sb.toString();
    }

    private String positionLine(Player player, JsonObject area) {
        Location n = npc.getLocation();
        Location p = player.getLocation();
        String base = (usesOwnerInventory() ? "Materiais: usa o inventario do criador.\n" : "Materiais: ilimitados conforme configuracao.\n")
                + "Voce (construtor) esta em " + n.getWorld().getName() + " x=" + n.getBlockX()
                + " y=" + n.getBlockY() + " z=" + n.getBlockZ() + ".\n"
                + "O jogador " + player.getName() + " esta em x=" + p.getBlockX() + " y=" + p.getBlockY()
                + " z=" + p.getBlockZ() + ".";
        if (area==null) return base;
        int x1 = area.get("x1").getAsInt(), x2 = area.get("x2").getAsInt();
        int y1 = area.get("y1").getAsInt(), y2 = area.get("y2").getAsInt();
        int z1 = area.get("z1").getAsInt(), z2 = area.get("z2").getAsInt();
        World w = getServer().getWorld(area.get("world").getAsString());
        String bioma = w == null || !w.isChunkLoaded(((x1+x2)/2)>>4,((z1+z2)/2)>>4) ? "desconhecido" : w.getBiome((x1 + x2) / 2, y1, (z1 + z2) / 2).getKey().getKey();
        return base + "\nAREA DE CONSTRUCAO: " + area.get("world").getAsString() + " x=" + x1 + ".." + x2
                + ", y=" + y1 + ".." + y2 + ", z=" + z1 + ".." + z2 + " (largura " + (x2 - x1 + 1)
                + " x profundidade " + (z2 - z1 + 1) + " x altura " + (y2 - y1 + 1) + " blocos). Bioma: " + bioma + ".\n"
                + "Construa tudo dentro dessa area. Na planta (blueprint), as coordenadas sao relativas ao canto minimo "
                + "da area (0,0,0), que e o ponto de origem. Escolha materiais que combinem com o bioma.";
    }

    private void surveyArea(CommandSender actor, JsonObject area, long token, java.util.function.Consumer<String> completed) {
        surveyArea(actor, area, token, false, completed);
    }

    private void surveyArea(CommandSender actor, JsonObject area, long token, boolean userFacing, java.util.function.Consumer<String> completed) {
        final TerrainGrid grid;
        final World world=getServer().getWorld(area.get("world").getAsString());
        final Map<String,TerrainGrid.Cell> previousCache=new HashMap<>();
        try { grid=new TerrainGrid(surveyBounds(area,false)); }
        catch(RuntimeException error) { requests.complete(token); if(actor instanceof Player player) ui.hide(player);actor.sendMessage(msg(error.getMessage()));return; }
        new org.bukkit.scheduler.BukkitRunnable() {
            public void run() {
                if(!requests.accepts(token)) { cancel();return; }
                if(!actor.hasPermission("aibuilder.use") || !plugin.controls(actor,BuilderSession.this) || actor instanceof Player player && !player.isOnline()) {
                    requests.complete(token);if(actor instanceof Player player) ui.hide(player);cancel();return;
                }
                try {
                    grid.scan(point -> {
                        Block block=surveyedBlock(world,point);
                        Material material=block.getType();String data=block.getBlockData().getAsString();
                        return previousCache.computeIfAbsent(data,ignored->new TerrainGrid.Cell(material.name(),data,
                                material.isAir()?TerrainGrid.Kind.AIR:block.isLiquid()?TerrainGrid.Kind.LIQUID:isVegetation(material)?TerrainGrid.Kind.VEGETATION:
                                NATURAL_TERRAIN.contains(material.name())?TerrainGrid.Kind.NATURAL:material.isSolid()?TerrainGrid.Kind.SOLID:TerrainGrid.Kind.OTHER));
                    },scanBatch());
                    if(actor instanceof Player player) ui.update(player,LocaleText.format(plugin, "message_d548ecad3c8b", name),grid.readCount(),Math.toIntExact(grid.bounds().volume()));
                    if(grid.complete()) { cancel();if(requests.accepts(token)) completed.accept(userFacing ? grid.context(text -> LocaleText.text(plugin, text)) : grid.context()); }
                } catch(RuntimeException error) { cancel();requests.complete(token);if(actor instanceof Player player) ui.hide(player);actor.sendMessage(msg(LocaleText.format(plugin, "message_630914fbf584", LocaleText.text(plugin, error.getMessage())))); }
            }
        }.runTaskTimer(plugin,1L,1L);
    }

    private static final String SYSTEM_PROMPT = """
        Voce e o Construtor, um jogador real do servidor Minecraft E.L.L.Y (Paper 26.3).
        Voce planeja como um arquiteto. O servidor valida materiais, area e protecoes
        antes de executar; nunca prometa materiais gratuitos ou permissoes extras. Publico infantil: nada inapropriado, nada violento.

        COMO VOCE CONSTROI
        - Planeje a obra inteira antes de responder: forma, dimensoes, fundacao, paredes,
          piso, telhado, portas, janelas e detalhes. Pense em voz de arquiteto.
        - Construa de baixo para cima: fundacao, paredes, piso, telhado, depois detalhes.
        - Nas plantas blueprint, desenhe a entrada principal no lado SUL (z maximo), com portas facing=south.
          O servidor gira a planta e os estados conforme a direcao escolhida pelo jogador. Nao aplique rotacao manual.
        - Uma construcao completa (ex.: casa) tem entre 80 e 200 acoes: fundacao, paredes,
          janelas, porta, piso, telhado. NAO pare no meio nem faca so a base.
        - Use varios materiais, como na vida real, combinando por funcao:
          madeira (OAK_PLANKS para paredes e pisos, OAK_LOG para vigas e troncos),
          pedra (STONE_BRICKS, COBBLESTONE, ANDESITE para base e muros),
          vidro (GLASS e GLASS_PANE para janelas), tijolo (BRICKS),
          telhado com escadas (OAK_STAIRS, STONE_BRICK_STAIRS) e lajes (OAK_SLAB),
          cercas (OAK_FENCE) e blocos de la para decoracao.
        - MATERIAIS REAIS: a construcao segue o que existiria na vida real.
          * Arvore: tronco de OAK_LOG (ou BIRCH_LOG, SPRUCE_LOG) e copa de OAK_LEAVES.
          * Casa: fundacao de COBBLESTONE, paredes de OAK_PLANKS ou tijolo, janelas de GLASS,
            telhado em degraus de blocos inteiros, abertura de entrada e piso de madeira ou pedra.
          * Ponte: pilares de pedra e tabuleiro de madeira, com corrimao de OAK_FENCE.
          * Poco/fonte: WATER e permitido somente como agua-fonte dentro de uma bacia desenhada inteira,
            com fundo e quatro paredes de blocos inteiros vedantes (ex.: STONE, STONE_BRICKS, COBBLESTONE,
            DEEPSLATE, QUARTZ_BLOCK, GLASS ou CONCRETE). Use data=\"minecraft:water[level=0]\".
            Planeje primeiro fundo e paredes, depois a agua; o servidor tambem move as fontes para o fim.
            Nao use WATER[level>0], nao deixe lados/fundo abertos e nunca use LAVA.
          * Torre: base de pedra, interior de madeira, ameias de pedra.
        - FISICA: SAND e GRAVEL caem se nao tiverem apoio, entao nao os deixe no ar.
          Escadas, lajes e blocos comuns podem flutuar no Minecraft; para boa arquitetura,
          planeje apoio visual. Paredes apoiam o telhado; o telhado deve ter beiral.
        - ARQUITETURA: simetria e proporcao, andares de 3 a 4 blocos de altura, portas com
          2 blocos de altura, janelas nas paredes, base firme, detalhes para dar identidade.
        - A AREA DE CONSTRUCAO fornecida ja foi selecionada pelo jogador. Nao peca para selecionar de novo.
        - Use o levantamento de toda a selecao para escolher a posicao e a fundacao.
          A grade e resumida: nao presuma que celulas omitidas estao vazias.
          Respeite os limites XYZ da AREA DE CONSTRUCAO; coloque apenas em ar.
          Se o relevo impedir uma base segura ou o terreno precisar de limpeza/nivelamento,
          nao gere acoes "break". Responda com "ask" e "actions": [] pedindo ao criador
          que prepare o terreno com {TERRAIN_LEVEL} ou {TERRAIN_CLEAR}; depois ele envia
          novamente o pedido de construcao. O servidor deve mostrar a previa e pedir confirmacao.
          Nunca planeje quebrar/construir fora da area para compensar o relevo.
        - Para construcoes grandes, mova-se para perto do alvo antes de colocar blocos longe de voce.
          Alcance de colocar/quebrar: {ACTION_REACH} blocos.

        ACOES (JSON compacto, uma por item)
        - {"type":"move","x":N,"y":N,"z":N}  ate {MOVE_RANGE} blocos de voce, sobre chao livre (ar nos dois blocos).
        - {"type":"place","x":N,"y":N,"z":N,"material":"NOME_DO_MATERIAL"}  so em ar, ate {ACTION_REACH} blocos de voce.
        - Nao gere acoes "break" para limpeza ou fundacao; essas operacoes exigem a previa e confirmacao do comando de terreno.
        Use nomes validos de material do Minecraft (ex.: OAK_PLANKS, STONE_BRICKS, OAK_LOG).
        Nao gere portas, camas ou plantas de dois blocos: exigem colocacao coordenada ainda nao suportada pela IA.
        Deixe a abertura para o jogador instalar a porta. Nao prometa porta funcional.
        Agua: so WATER com data \"minecraft:water[level=0]\", em bacia completa neste mesmo plano: cada
        celula de agua precisa de fundo e paredes laterais com blocos inteiros vedantes. Use materiais
        como STONE, STONE_BRICKS, COBBLESTONE, DEEPSLATE, QUARTZ_BLOCK, GLASS ou CONCRETE; lajes,
        escadas, vidracas, folhas, portas e blocos waterlogged nao vedam. O limite e 4096 celulas de
        vedacao/apoio por bacia. Em modo de sobrevivencia, cada fonte consome um WATER_BUCKET e devolve
        um BUCKET vazio quando a colocacao funciona. A pre-validacao recusa qualquer fonte que possa
        escapar; nao tente agua corrente ou cascata aberta. O construtor coloca a agua depois de terminar as paredes.
        A API aceita materiais de bloco Paper 26.3+; inclua BlockData valido quando o estado importar.
        Para escadas/lajes orientadas, actions pode incluir "data" com BlockData valido do mesmo material.
        Blueprint nao suporta estados: prefira blocos inteiros nos telhados.
        Pedido de construcao exige place ou blueprint: nunca responda so com \"move\".
        Maximo de {MAX_AI_ACTIONS} acoes por resposta. Se a obra for maior, faca a primeira parte e diga
        na fala que continua depois.

        PLANTA (para construcoes completas: casa, torre, muro, ponte)
        Em vez de "actions", responda com "blueprint": {"name": "nome", "parts": [...]}. Tipos de parte:
        - box: {"id": "...", "type": "box", "from": [x,y,z], "to": [x,y,z], "material": "NOME"} preenche um bloco retangular.
        - walls: {"id": "...", "type": "walls", "from": [x,y,z], "to": [x,y,z], "height": N, "material": "NOME", "openings": [...]} paredes de um retangulo fechado.
        - wall: {"id": "...", "type": "wall", "from": [x,y,z], "to": [x,y,z], "height": N, "material": "NOME", "openings": [...]} parede reta (mesmo x ou mesmo z).
        - roof_steps: {"id": "...", "type": "roof_steps", "from": [x,y,z], "to": [x,y,z], "layers": N, "material": "NOME"} telhado em degraus.
        Aberturas: {"at": posicao ao longo da parede a partir do canto, "width": N, "height": N, "y": altura da base (0 para porta, 1 para janela), "side": "north|south|east|west" (so em walls), "kind": "door|window"}.
        Coordenadas: origem no chao; y=0 e a camada da fundacao; "to" inclusivo. Piso dentro das paredes; telhado apoiado no topo. Em walls e roof_steps, "to" usa o mesmo y de "from"; a altura vem de height/layers.
        Use blueprint para casas, torres, muros e pontes. Use actions so para ajustes pequenos.

        FORMATO DA RESPOSTA
        Responda SOMENTE com um objeto JSON: {"say": "fala curta em portugues", "ask": "", "actions": [...]}.
        - Se o pedido for ambiguo (tamanho, lugar, estilo), use "ask" com UMA pergunta curta e deixe "actions" vazio.
        - Se for para construir, o plano DEVE conter muitas acoes "place" (nunca so "move").
        - Nunca invente comandos. Nunca quebre nada que nao seja terra, grama ou pedra.
        """;

    private static final String TUTOR_PROMPT = String.join("\n",
            "Voce e o Construtor, tutor de Minecraft para criancas no servidor E.L.L.Y.",
            "Explique o tema pedido de forma curta, clara e animada, em portugues, em no maximo 5 frases.",
            "NAO construa nada. Responda SOMENTE com um objeto JSON: {\"say\": \"sua explicacao\"}.");

    private static final String INCREMENT_PROMPT = String.join("\n",
            "MODO INCREMENTO (obra aberta):",
            "- Voce esta ACRESCENTANDO a uma obra ja construida, dentro da mesma area. Nao refaca a estrutura.",
            "- Responda SOMENTE com actions (nada de blueprint). Tipos: place (bloco novo em ar), replace (troca um bloco da propria obra, ex.: parede por janela ou porta), move.",
            "- replace so funciona com coordenadas de blocos que ja existem na obra (veja a caixa x,y,z de cada material no resumo). Nao existe quebrar nesse modo.",
            "- Ideias: janelas e portas (replace), tochas e lanternas nas paredes, cercas, flores, caminhos, varanda, decoracao interna (mesas, camas, bau, estantes, tapete), iluminacao.",
            "- Respeite o levantamento e os limites da obra: place so em ar; blocos existentes da obra so podem ser trocados com replace.",
            "- Maximo {MAX_AI_ACTIONS} acoes. Na fala, diga o que foi acrescentado.");

    private void askAI(Player player, String request, String terrain, String position, boolean tutor,
            JsonObject area, List<JsonObject> turns, String model, String apiUrl, String apiKey, long token,
            String buildCtx, boolean increment, int maxAiActions, int maxResponseTokens, int requestTimeoutSeconds,
            int actionReach, int moveRange) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", maxResponseTokens);
            body.addProperty("reasoning_effort", "none");
            body.add("response_format", JsonParser.parseString("{\"type\":\"json_object\"}"));
            JsonArray messages = new JsonArray();
            String systemPrompt = SYSTEM_PROMPT.replace("{MAX_AI_ACTIONS}", Integer.toString(maxAiActions))
                    .replace("{ACTION_REACH}", Integer.toString(actionReach))
                    .replace("{MOVE_RANGE}", Integer.toString(moveRange))
                    .replace("{TERRAIN_LEVEL}", commandPrefix() + " terrain level")
                    .replace("{TERRAIN_CLEAR}", commandPrefix() + " terrain clean");
            if (increment) systemPrompt += "\n\n" + INCREMENT_PROMPT.replace("{MAX_AI_ACTIONS}", Integer.toString(maxAiActions));
            String responseLanguage = LocaleText.language(plugin);
            String localizedPrompt = (tutor ? TUTOR_PROMPT : systemPrompt).replace("em portugues", "in the configured language");
            localizedPrompt += "\nRESPONSE LANGUAGE: " + (responseLanguage.equals("EN") ? "English" : "Portuguese")
                    + ". Every user-facing say, ask, name and explanation must use this language. All commands must use English syntax: /builder terrain level, /builder terrain clean, /builder yes, /builder cancel. JSON keys, material IDs and player data remain unchanged.";
            messages.add(msg("system", localizedPrompt));
            for (JsonObject t : turns) messages.add(t);
            String extra = increment ? buildCtx + "\n" : "";
            messages.add(msg("user", position + "\n\n" + terrain + "\n" + extra + "Pedido: " + request));
            body.add("messages", messages);

            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = plugin.http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log("api-error", "HTTP " + resp.statusCode());
                requestFailed(player, token, LocaleText.format(plugin, "message_d12a43545ec7"));
                return;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("choices")) {
                log("api-error", LocaleText.format(plugin, "message_1ccdbec8b0e8"));
                requestFailed(player, token, LocaleText.format(plugin, "message_a19da4ebec3f"));
                return;
            }
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonObject plan = parsePlan(content);
            if (plan == null) {
                log("parse-error", LocaleText.format(plugin, "message_53221af63549"));
                requestFailed(player, token, LocaleText.format(plugin, "message_7f89926983e5"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!requests.complete(token)) return;
                ui.hide(player);
                if (!player.isOnline() || !player.getUniqueId().equals(ownerId)) return;
                try { handleReply(player, request, plan, tutor, area, increment, maxAiActions); }
                catch (RuntimeException invalid) { clearPending(); refuse(player, LocaleText.format(plugin, "message_5488ed3ae8dc"), LocaleText.format(plugin, "message_7367c1990648", LocaleText.text(plugin, invalid.getMessage()))); }
            });
        } catch (IOException | RuntimeException ex) {
            log("error", ex.toString());
            requestFailed(player, token, LocaleText.format(plugin, "message_d89440606d71"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            requestFailed(player, token, LocaleText.format(plugin, "message_d89440606d71"));
        }
    }

    private void handleReply(Player player, String request, JsonObject plan, boolean tutor, JsonObject area, boolean increment, int maxAiActions) {
        UUID uid = player.getUniqueId();
        String say = plan.has("say") && !plan.get("say").isJsonNull() ? plan.get("say").getAsString() : "";

        if (tutor) {
            // tutor: so explica para quem perguntou, sem acoes
            String text = say.isEmpty() ? LocaleText.format(plugin, "message_4f12d308aa63") : say;
            player.sendMessage(BuilderUi.prompt(msg(text)));
            remember(uid, request, LocaleText.format(plugin, "message_1d3fecb4d0a1", text));
            return;
        }
        if (npc == null || npc.isDead()) return;
        if (plan.has("blueprint") && plan.get("blueprint").isJsonObject()) {
            if (increment) {
                player.sendMessage(msg(LocaleText.format(plugin, "message_35dda9c7a336")));
                remember(uid, request, LocaleText.format(plugin, "message_57cfb61e4df7"));
                return;
            }
            if (!say.isEmpty()) broadcastBot(say);
            startBuild(player, plan.getAsJsonObject("blueprint"), area);
            remember(uid, request, LocaleText.format(plugin, "message_45fad3f802bb"));
            return;
        }
        String ask = plan.has("ask") && !plan.get("ask").isJsonNull() ? plan.get("ask").getAsString() : "";
        List<JsonObject> actions = new ArrayList<>();
        if (plan.has("actions") && plan.get("actions").isJsonArray()) {
            for (JsonElement el : plan.getAsJsonArray("actions")) {
                if (actions.size() >= maxAiActions || !el.isJsonObject()) {
                    player.sendMessage(msg(LocaleText.format(plugin, "message_5ca17d3bc7a4"))); return;
                }
                JsonObject action = el.getAsJsonObject().deepCopy();
                action.remove("creative"); // Free/remote catalog assembly is granted only by server-owned Build state.
                action.remove("_aibuilder_contained_water"); // Placement proof is generated from the complete server-validated plan.
                actions.add(action);
            }
        }

        if (!increment && actions.stream().anyMatch(a -> a.has("type") && a.get("type").isJsonPrimitive()
                && a.get("type").getAsString().equals("break"))) {
            String prompt = LocaleText.format(plugin, "message_4144a195466c", commandPrefix(), commandPrefix());
            refuse(player, prompt, LocaleText.format(plugin, "message_910a5fa2d24b"));
            remember(uid, request, LocaleText.format(plugin, "message_554041edf6c4", prompt));
            return;
        }

        if (!say.isEmpty()) broadcastBot(say);

        if (actions.isEmpty()) {
            String reply = !ask.isEmpty() ? ask : (say.isEmpty() ? LocaleText.format(plugin, "message_e6c56f1f751c") : "");
            if (!reply.isEmpty()) {
                broadcastBot(reply);
                remember(uid, request, LocaleText.format(plugin, "message_ce064074da29", reply));
            }
            return;
        }

        validateActions(actions, area);

        if (!increment && actions.stream().allMatch(a -> a.get("type").getAsString().equals("move"))) {
            refuse(player, LocaleText.format(plugin, "message_886b0f7b122c"), LocaleText.format(plugin, "message_55f94183bb4d"));
            return;
        }
        if (!increment) {
            List<String> occupied = occupiedPlacements(actions, area);
            if (!occupied.isEmpty()) { refuse(player, occupiedMessage(occupied), LocaleText.format(plugin, "message_07d23b7c5f78", occupied.size())); return; }
        }

        if (increment) {
            // incremento: so coloca em ar, substitui blocos da propria obra e anda; nada de quebrar
            if (activeBuild == null) return;
            for (JsonObject action : actions) {
                String type = action.has("type") ? action.get("type").getAsString() : "";
                if (type.equals("replace") && !activeBuild.hasBlock(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt())) {
                    player.sendMessage(msg(LocaleText.format(plugin, "message_4750c5289ef9", blockKey(action))));
                    remember(uid, request, LocaleText.format(plugin, "message_9c121f442e74"));
                    return;
                }
                if (!Set.of("place", "replace", "move").contains(type)) {
                    player.sendMessage(msg(LocaleText.format(plugin, "message_33b2d1c67f22")));
                    remember(uid, request, LocaleText.format(plugin, "message_1702acec4e5c"));
                    return;
                }
            }
        }

        // pedido com acoes: mostra o resumo e espera o criador confirmar
        pendingActions = actions;
        pendingArea = area.deepCopy();
        pendingIncrement = increment;
        pendingRequest = request;
        for (JsonObject action : actions) action.add("area", area.deepCopy());
        pendingExpires = System.currentTimeMillis() + planTimeoutMillis();
        pendingSummary = (increment ? LocaleText.format(plugin, "message_d114c1e23969", activeBuild.name) : "") + summarize(actions);
        String confirmText = LocaleText.format(plugin, "message_9a66d49b1afe", pendingSummary, LocaleText.text(plugin, materialsLine(actions)), planTimeoutSeconds());
        Player owner = ownerPlayer();
        if (owner != null) showPending(owner);
        remember(uid, request, LocaleText.format(plugin, "message_dd8e021e961f", pendingSummary));
        log("plan-pending", LocaleText.format(plugin, "message_0894f114d7ea", pendingSummary, player.getName()));
    }

    /** Responde ao jogador e grava a recusa no actions.log (antes so ia para o chat). */
    private void refuse(CommandSender to, String text, String logDetail) {
        to.sendMessage(msg(text));
        log("recusado", logDetail);
    }

    /** Colocacoes que cairiam em blocos ja existentes (ex.: o chao). Pode ser chamado so na thread principal. */
    private List<String> occupiedPlacements(List<JsonObject> actions, JsonObject area) {
        List<String> out = new ArrayList<>();
        World w = getServer().getWorld(area.get("world").getAsString());
        if (w == null) return out;
        for (JsonObject a : actions) {
            String type = a.get("type").getAsString();
            if (!type.equals("place") && !type.equals("place_project")) continue;
            Material m = materialOf(a);
            Block b = w.getBlockAt(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
            if (m != null && b.getType() == m) continue; // ja construido
            if (!b.getType().isAir()) out.add(coords(b.getLocation()));
        }
        return out;
    }

    private String occupiedMessage(List<String> occupied) {
        return LocaleText.format(plugin, "message_f83461971ef6", occupied.size(), occupied.get(0));
    }

    /** Lista os materiais do plano e, se possivel, quanto o criador ja tem no inventario. */
    private String materialsLine(List<JsonObject> actions) {
        if (pendingBuild != null && pendingBuild.catalogId != null && pendingBuild.catalogFreeMaterials)
            return LocaleText.format(plugin, "message_76fbc3c42ace");
        Map<String, Integer> need = new java.util.TreeMap<>();
        for (JsonObject a : actions) {
            String t = a.has("type") ? a.get("type").getAsString() : "";
            if ((t.equals("place") || t.equals("place_project") || t.equals("replace")) && a.has("material")) {
                String material = a.get("material").getAsString();
                need.merge("WATER".equals(material) ? "WATER_BUCKET" : material, 1, Integer::sum);
            }
        }
        if (need.isEmpty()) return LocaleText.format(plugin, "message_4621173061b6");
        if (getConfig().getBoolean("creative-mode", false)) return LocaleText.format(plugin, "message_24f62c8a27fe");
        Player owner = ownerPlayer();
        boolean useInv = usesOwnerInventory();
        StringBuilder sb = new StringBuilder(LocaleText.format(plugin, "message_41f60fee0ec2"));
        int shown = 0;
        for (Map.Entry<String, Integer> en : need.entrySet()) {
            if (shown > 0) sb.append(", ");
            sb.append(en.getValue()).append("x ").append(en.getKey());
            if ("WATER_BUCKET".equals(en.getKey())) sb.append(LocaleText.format(plugin, "message_33628ebdbe07"));
            if (useInv && owner != null) {
                Material m = Material.matchMaterial(en.getKey());
                int have = m == null ? 0 : countItem(owner, m);
                sb.append(have >= en.getValue() ? " (ok)" : LocaleText.format(plugin, "message_ca69cc95b8d6", have));
            }
            shown++;
            if (shown >= 12 && need.size() > 12) { sb.append(LocaleText.format(plugin, "message_9c2cdcae7302")).append(need.size() - 12).append(LocaleText.format(plugin, "message_2f3b7e929c5d")); break; }
        }
        if (useInv && owner == null) sb.append(LocaleText.format(plugin, "message_c3cdf43f19e2"));
        return sb.toString();
    }

    private int countItem(Player p, Material m) {
        int c = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == m) c += it.getAmount();
        }
        return c;
    }

    private String summarize(List<JsonObject> actions) {
        int place = 0, breaks = 0, moves = 0;
        for (JsonObject a : actions) {
            String t = a.has("type") ? a.get("type").getAsString() : "";
            if (t.equals("place") || t.equals("place_project")) place++;
            else if (t.equals("break")) breaks++;
            else if (t.equals("move")) moves++;
        }
        return LocaleText.format(plugin, "message_1b46f0e682b5", actions.size(), place, breaks, moves);
    }

    private void broadcastBot(String text) {
        Player owner = ownerPlayer();
        if (owner != null) owner.sendMessage(BuilderUi.prompt(msg(text)));
    }

    private void remember(UUID uid, String request, String botReply) {
        List<JsonObject> list = history.computeIfAbsent(uid, k -> new ArrayList<>());
        list.add(msg("user", request));
        list.add(msg("assistant", botReply));
        while (list.size() > historyMessageLimit()) list.remove(0);
    }

    private void requestFailed(Player player, long token, String text) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!requests.complete(token)) return;
            ui.hide(player);
            if (player.isOnline()) player.sendMessage(msg(text));
        });
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static JsonObject parsePlan(String content) {
        int a = content.indexOf('{'), b = content.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return JsonParser.parseString(content.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- executar acoes (thread principal) ----------

    void processScheduledQueue() {
        processScheduledQueue(Long.MAX_VALUE);
    }

    void processScheduledQueue(long quickDeadlineNanos) {
        queueTickOffset = (queueTickOffset + 1) & 3;
        if (!quickBuildMode && queueTickOffset != 0) return;
        if (quickBuildMode) processQueue(quickDeadlineNanos); else processQueue();
    }

    void processQueue() {
        processQueue(Long.MAX_VALUE);
    }

    private void processQueue(long sharedDeadlineNanos) {
        if (recalled()) return; // Preserve queue and terrain state until the owner explicitly releases it.
        if(quickBuildMode && selfEscapeOrigin==null) maintainQuickBuildHover();
        boolean workMovement = activeBuild != null && walker.hasGoal();
        walker.setBoundary(selfEscapeOrigin!=null ? this::builderSelfClearRouteAllowed
                : queue.isEmpty() && !workMovement ? this::withinTravelLimit : this::insideWorkMovementBounds);
        if (selectionRequest != null && System.currentTimeMillis() > selectionExpires) {
            clearSelectionRequest(); say(LocaleText.format(plugin, "message_c5bea4b13692"));
        }
        if(terrainWork!=null) return;
        if (pendingActions != null && System.currentTimeMillis() > pendingExpires) {
            clearPending(); say(LocaleText.format(plugin, "message_52097e16d694"));
        }
        if (npc == null || npc.isDead()) return;
        // Dono ausente e nada a fazer: o construtor some. Com fila, continua trabalhando sem o dono.
        if (ownerPlayer() == null && !isActive() && !waitingMaterials()) { plugin.removeBuilder(this); return; }
        if (queue.isEmpty()) {
            walker.clearLookTarget();
            if (activeBuild != null && walker.hasGoal()) return; // followOwner advances the confirmed route
            if (busy) {
                activeCancelToken = "";
                busy = false; walker.stop(npc);
                if(quickBuildMode) walker.hover(npc); else walker.land(npc);
                if (undoInProgress) { finishUndo(); return; }
                say(constructionProgress());
                if (!rejectionCounts.isEmpty()) say(LocaleText.format(plugin, "message_0879699b53e4", localizedCounts(rejectionCounts)));
                Player owner = ownerPlayer(); if (owner != null) ui.hide(owner);
                if (activeBuild != null && ownerPlayer() != null) reportBuild(ownerPlayer(), false);
            }
            return;
        }
        busy = true;
        int actionLimit = quickBuildMode ? plugin.quickBuildActionLimit() : 512;
        long ownDeadline = System.nanoTime() + (quickBuildMode ? plugin.quickBuildBudgetNanos() : 2_000_000L);
        long deadlineNanos = Math.min(sharedDeadlineNanos, ownDeadline);
        int completed=0;
        for (int checked = 0; checked < actionLimit && !queue.isEmpty(); checked++) {
            // SPEED guarantees a 128-action floor when work is ready; the configurable time
            // budget limits further work according to server capacity.
            if (System.nanoTime() >= deadlineNanos && (!quickBuildMode || completed>=128)) break;
            int queued=queue.size();
            if (!processConstructionAction()) break; // Normal mode waits for movement; SPEED drains successful queued actions.
            if(queue.size()<queued) completed++;
        }
    }

    private void resetProgress() {
        planOk = 0; planRejected = 0;
        placedBlocks = 0; removedBlocks = 0; existingBlocks = 0;
        nextProgressAt = 0; nextExistingNoticeAt = 0;
    }

    private String constructionProgress() {
        return LocaleText.format(plugin, "construction.progress", placedBlocks, existingBlocks, removedBlocks, planRejected);
    }

    private void updateConstructionProgress(String label) {
        if (System.currentTimeMillis() < nextProgressAt) return;
        nextProgressAt = System.currentTimeMillis() + 200;
        Player viewer = ownerPlayer();
        if (viewer != null) ui.update(viewer, label, planOk + planRejected, planOk + planRejected + queue.size());
    }

    private boolean processConstructionAction() {
        if (!quickBuildMode && System.currentTimeMillis() < nextMovementAttemptAt) return false;
        JsonObject action = queue.peek();
        if (selfEscapeOrigin != null) {
            tickBuilderSelfClear();
            waitingReason = LocaleText.raw("builder.self-clear");
            updateConstructionProgress(name + " • " + LocaleText.text(plugin, waitingReason));
            return false;
        }
        if (!quickBuildMode && action.has("area") && !npc.getWorld().getName().equals(action.getAsJsonObject("area").get("world").getAsString())) {
            walker.stop(npc);
            waitingReason = LocaleText.raw("message_5b61ca6d8057");
            return false; // A cross-world recall must never discard actions captured in the old world.
        }
        String type = action.has("type") ? action.get("type").getAsString() : "";
        if (!quickBuildMode && Set.of("place", "place_project", "replace", "break", "undo").contains(type)) {
            Block target = blockAt(action);
            if (target != null) walker.lookAt(npc, target.getLocation().add(.5, .5, .5));
        } else {
            walker.clearLookTarget();
        }
        String result;
        try {
            result = execute(action);
        } catch (RuntimeException ex) {
            result = LocaleText.raw("message_a436daf7989e", ex.getMessage());
        }
        if (!quickBuildMode && movementBlocked(result)) {
            nextMovementAttemptAt = System.currentTimeMillis() + 1500;
            result = LocaleText.raw("construction.waiting-route", commandPrefix());
        }
        if (result.startsWith(LocaleText.raw("message_8bde467e10c9")) && ownerPlayer() == null) { pauseOfflineForMaterials(); return false; }
        if (isWaitingResult(result)) {
            if (!result.equals(waitingReason)) { waitingReason = result; say(LocaleText.format(plugin, "message_3dc0b9549b65", LocaleText.text(plugin, result))); }
            updateConstructionProgress(name + " • " + LocaleText.text(plugin, result));
            return false;
        }
        waitingReason = ""; queue.poll();
        boolean already = result.equals(LocaleText.raw("message_e8e91dc6b587"));
        if (!already) log("action", action.toString() + " -> " + result);
        boolean ok = result.startsWith("ok");
        if (ok) {
            planOk++;
            if (already) existingBlocks++;
            else if (Set.of("place", "place_project", "replace").contains(type)) {
                placedBlocks++;
                if (!type.equals("replace")) onBlocksPlaced(1);
            } else if (type.equals("break") || type.equals("undo")) removedBlocks++;
        } else {
            planRejected++;
            rejectionCounts.merge(result, 1, Integer::sum);
        }
        // projetos tem muitos blocos: so avisa a cada 50 construidos e as recusas; senao avisa cada acao
        if (type.equals("undo")) {
            if (ok && planOk % 50 == 0) say(LocaleText.format(plugin, "message_a652be0e5b5f", planOk));
            else if (!ok && rejectionCounts.getOrDefault(result, 0) == 1) say(LocaleText.format(plugin, "message_b7473289eb53", LocaleText.text(plugin, result)));
        } else if (type.equals("place_project")) {
            if (ok && !already && placedBlocks % 50 == 0) say(constructionProgress());
            else if (already && System.currentTimeMillis() >= nextExistingNoticeAt) {
                nextExistingNoticeAt = System.currentTimeMillis() + 3000;
                say(LocaleText.format(plugin, "construction.existing", existingBlocks));
            }
            else if (!ok && rejectionCounts.getOrDefault(result, 0) == 1) say(LocaleText.format(plugin, "message_4603ab13bef4", LocaleText.text(plugin, result)));
        } else {
            say(describe(action) + (ok ? LocaleText.format(plugin, "presentation.done") : LocaleText.format(plugin, "message_3e2e4ee09742", LocaleText.text(plugin, result))));
        }
        updateConstructionProgress(constructionProgress());
        return already || quickBuildMode && ok;
    }

    private String localizedCounts(Map<String, Integer> counts) {
        return counts.entrySet().stream().map(entry -> LocaleText.text(plugin, entry.getKey()) + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    private String describe(JsonObject a) {
        String type = a.has("type") ? a.get("type").getAsString() : "?";
        String xyz = (a.has("x") ? a.get("x").getAsInt() : 0) + " " + (a.has("y") ? a.get("y").getAsInt() : 0)
                + " " + (a.has("z") ? a.get("z").getAsInt() : 0);
        return switch (type) {
            case "move" -> LocaleText.format(plugin, "message_db4f977d1f4a", xyz);
            case "place", "place_project" -> LocaleText.format(plugin, "message_1b797620dc22", a.has("material") ? a.get("material").getAsString() : "bloco", xyz);
            case "break" -> LocaleText.format(plugin, "message_4aecc0cb3c7a", xyz);
            case "undo" -> LocaleText.format(plugin, "message_fd3342263488", xyz);
            default -> LocaleText.format(plugin, "message_c893be7ad088", type);
        };
    }

    private String execute(JsonObject a) {
        String type = a.has("type") ? a.get("type").getAsString() : "";
        switch (type) {
            case "move": {
                if(quickBuildMode) return "ok: movement skipped in stationary speed mode";
                Location target = coords(a);
                if (target == null) return LocaleText.raw("message_9159dd3c89fd");
                if (target.distance(npc.getLocation()) > moveTargetRange()) return LocaleText.raw("message_019b8854177f");
                if (!inAreaOf(a, target)) return LocaleText.raw("message_3ef56d4e1345");
                if (!target.getWorld().getWorldBorder().isInside(target)) return LocaleText.raw("message_4e9501e34899");
                if (inSpawnZone(target)) return LocaleText.raw("message_8ca33d2d9b07");
                if (claimed(target.getBlock())) return LocaleText.raw("message_bf667b7b5814");
                if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable())
                    return LocaleText.raw("message_eae848de03b1");
                Location destination = target.clone().add(.5, 0, .5);
                if (npc.getLocation().distanceSquared(destination) <= .65 * .65) {
                    walker.stop(npc); return LocaleText.raw("message_e92b6fc3ffbf");
                }
                walker.requestWork(npc, destination, .65, walkingSpeed(), location -> inAreaOf(a, location)
                        && !inSpawnZone(location) && !claimed(location.getBlock()));
                if (walker.state() == NpcWalker.State.BLOCKED) { walker.stop(npc); return LocaleText.raw("message_c4b132c8e758"); }
                return LocaleText.raw("message_e11566c0e085");
            }
            case "place", "place_project": {
                Block b = blockAt(a);
                if (b == null) return LocaleText.raw("message_9159dd3c89fd");
                if (!inAreaOf(a, b.getLocation())) return LocaleText.raw("message_3ef56d4e1345");
                if (inSpawnZone(b.getLocation())) return LocaleText.raw("message_8ca33d2d9b07");
                Material expected = materialOf(a);
                if (claimed(b)) return LocaleText.raw("message_bf667b7b5814");
                if (expected != null && CatalogGardenPolicy.isGarden(expected.name())) {
                    if (!placeable(expected, a)) return LocaleText.raw("message_407346a63e5d");
                    String gardenProblem = gardenPlacementProblem(b, a);
                    if (gardenProblem != null) return gardenProblem;
                }
                if (expected != null && b.getType() == expected && (!a.has("data") || BlockStateSpec.matches(a.get("data").getAsString(), b.getBlockData().getAsString()))) return LocaleText.raw("message_e8e91dc6b587");
                if (!b.getType().isAir()) return LocaleText.raw("message_cc174adefbf8");
                if (claimed(b)) return LocaleText.raw("message_bf667b7b5814");
                Material m = materialOf(a);
                if (!placeable(m, a))
                    return LocaleText.raw("message_e7a517a0e474");
                org.bukkit.block.data.BlockData data = a.has("data") ? Bukkit.createBlockData(a.get("data").getAsString()) : m.createBlockData();
                if (data.getMaterial() != m) return LocaleText.raw("message_af7317d52d18");
                if (!rateOk()) return LocaleText.raw("message_96fa4c1f0e70");
                // SPEED edits confirmed coordinates directly; entity position and reach never gate the plan.
                if (npcInTheWay(b)) return escapeEdit(b);
                String placementProblem = placementProblem(b, data);
                if (placementProblem != null) return placementProblem;
                boolean catalogPlacement = isCatalogPlacement(a);
                if (!quickBuildMode && !inReach(b)) {
                    if (!moveNear(b, a)) { walker.stop(npc); return LocaleText.raw("message_76dd36bcda03"); }
                    return recoverStalledBuildApproach(a, LocaleText.raw("message_46cf07fc9608"));
                }
                if(!quickBuildMode) walker.stop(npc);
                boolean free = catalogPlacement && activeBuild.catalogFreeMaterials;
                Material requiredItem = m == Material.WATER ? Material.WATER_BUCKET : m;
                if (!free && !takeFromOwner(requiredItem)) return LocaleText.raw("message_77a7921cb018", requiredItem.name());
                try { b.setBlockData(data, m != Material.WATER && !(data instanceof org.bukkit.block.data.type.Door) && !(data instanceof org.bukkit.block.data.type.Bed)); }
                catch (RuntimeException failure) { if (!free) giveToOwner(requiredItem); throw failure; }
                if (!free && m == Material.WATER && usesOwnerInventory()) giveToOwner(Material.BUCKET);
                if (activeBuild != null) {
                    activeBuild.placed.add(b.getX() + "," + b.getY() + "," + b.getZ());
                    checkpointBuildIfDue();
                }
                recordSuccessfulAction();
                if(!quickBuildMode) npc.swingMainHand();
                return LocaleText.raw("message_142226f06376", m);
            }
            case "replace": {
                // troca um bloco que a propria obra construiu (ex.: parede por janela); o antigo volta ao criador
                Block b = blockAt(a);
                if (b == null) return LocaleText.raw("message_9159dd3c89fd");
                if (!inAreaOf(a, b.getLocation())) return LocaleText.raw("message_3ef56d4e1345");
                if (inSpawnZone(b.getLocation())) return LocaleText.raw("message_8ca33d2d9b07");
                if (activeBuild == null || !activeBuild.hasBlock(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()))
                    return LocaleText.raw("message_36d090b49780");
                if (claimed(b)) return LocaleText.raw("message_bf667b7b5814");
                if (!matchesReplacementSource(b, a)) return LocaleText.raw("message_7f4911317d4d");
                if (b.getState() instanceof org.bukkit.block.TileState) return LocaleText.raw("message_fee9731725a5");
                Material expected = materialOf(a);
                if (expected == null || CatalogGardenPolicy.isGarden(expected.name()) || !placeable(expected, a)) return LocaleText.raw("message_e7a517a0e474");
                String mutationProblem = gardenMutationProblem(a, expected.name());
                if (mutationProblem != null) return mutationProblem;
                org.bukkit.block.data.BlockData data = a.has("data") ? Bukkit.createBlockData(a.get("data").getAsString()) : expected.createBlockData();
                if (data.getMaterial() != expected) return LocaleText.raw("message_af7317d52d18");
                if (b.getType() == expected && (!a.has("data") || b.getBlockData().getAsString().equals(a.get("data").getAsString()))) return LocaleText.raw("message_e8e91dc6b587");
                if (!rateOk()) return LocaleText.raw("message_96fa4c1f0e70");
                if (npcInTheWay(b)) return escapeEdit(b);
                String placementProblem = placementProblem(b, data);
                if (placementProblem != null) return placementProblem;
                if (!quickBuildMode && !inReach(b)) {
                    if (!moveNear(b, a)) { walker.stop(npc); return LocaleText.raw("message_76dd36bcda03"); }
                    return recoverStalledBuildApproach(a, LocaleText.raw("message_46cf07fc9608"));
                }
                if(!quickBuildMode) walker.stop(npc);
                if (!takeFromOwner(expected)) return LocaleText.raw("message_77a7921cb018", expected.name());
                Material old = b.getType();
                try { b.setBlockData(data, !(data instanceof org.bukkit.block.data.type.Door) && !(data instanceof org.bukkit.block.data.type.Bed)); }
                catch (RuntimeException failure) { giveToOwner(expected); throw failure; }
                activeBuild.placed.add(b.getX() + "," + b.getY() + "," + b.getZ());
                checkpointBuildIfDue();
                if (!old.isAir() && old != expected) giveToOwner(old);
                recordSuccessfulAction();
                if(!quickBuildMode) npc.swingMainHand();
                return LocaleText.raw("message_a12804d85636", expected);
            }
            case "break": {
                Block b = blockAt(a);
                if (b == null) return LocaleText.raw("message_9159dd3c89fd");
                String mutationProblem = gardenMutationProblem(a, null);
                if (mutationProblem != null) return mutationProblem;
                if (!inAreaOf(a, b.getLocation())) return LocaleText.raw("message_3ef56d4e1345");
                if (inSpawnZone(b.getLocation())) return LocaleText.raw("message_8ca33d2d9b07");
                if (!BREAKABLE.contains(b.getType())) return LocaleText.raw("message_2c0fd467d67d");
                if (claimed(b)) return LocaleText.raw("message_bf667b7b5814");
                JsonObject selectedArea = a.has("area") && a.get("area").isJsonObject() ? a.getAsJsonObject("area") : null;
                if (playerModified(b, selectedArea)) return LocaleText.raw("message_c7ee9520a8fe");
                if (!quickBuildMode && !inReach(b)) {
                    if (!moveNear(b, a)) { walker.stop(npc); return LocaleText.raw("message_ea7debd20c69", commandPrefix()); }
                    return LocaleText.raw("message_a08627d66e40");
                }
                if (!rateOk()) return LocaleText.raw("message_96fa4c1f0e70");
                Material old = b.getType();
                b.setType(Material.AIR, true);
                if (activeBuild != null) activeBuild.preparationTargets.remove(blockKey(a));
                giveToOwner(old);
                recordSuccessfulAction();
                if(!quickBuildMode) npc.swingMainHand();
                return LocaleText.raw("message_639edbb8d5c2");
            }
            case "undo": {
                Block b=blockAt(a);
                if(b==null || !inAreaOf(a,b.getLocation())) return LocaleText.raw("message_3ef56d4e1345");
                if(inSpawnZone(b.getLocation()) || claimed(b)) return LocaleText.raw("message_cb0a7d402c9b");
                Material expected=materialOf(a);
                if(expected==null || b.getType()!=expected || a.has("data") && !BlockStateSpec.matches(a.get("data").getAsString(),b.getBlockData().getAsString()))
                    return LocaleText.raw("message_1f938c3a2cf9");
                if(undoActor==null || !undoActor.isOnline()) return LocaleText.raw("message_26d90ce32019");
                if(!rateOk()) return LocaleText.raw("message_96fa4c1f0e70");
                BlockBreakEvent event=new BlockBreakEvent(b,undoActor);
                event.setDropItems(false); event.setExpToDrop(0);
                plugin.dispatchBuilderBreak(event);
                event.setDropItems(false); event.setExpToDrop(0);
                if(event.isCancelled()) return LocaleText.raw("message_474d195d8139");
                if(b.getType()!=expected || a.has("data") && !BlockStateSpec.matches(a.get("data").getAsString(),b.getBlockData().getAsString()))
                    return LocaleText.raw("message_7fde0a440715");
                b.setType(Material.AIR,false);
                if(activeBuild!=null && (activeBuild.catalogId==null || !activeBuild.catalogFreeMaterials) && getConfig().getBoolean("use-owner-inventory",true)
                        && !getConfig().getBoolean("creative-mode",false)) giveToOwner(expected);
                if(activeBuild!=null) activeBuild.placed.remove(b.getX()+","+b.getY()+","+b.getZ());
                recordSuccessfulAction(); if(!quickBuildMode) npc.swingMainHand();
                return LocaleText.raw("message_fa0d002cd2a3");
            }
            default:
                return LocaleText.raw("message_92d5401bb423", type);
        }
    }

    /** Chega a ate quatro blocos do centro do alvo, tentando voo depois que a rota a pe bloqueia. */
    private boolean movementBlocked(String result) {
        return result.equals(LocaleText.raw("message_c4b132c8e758"))
                || result.equals(LocaleText.raw("message_fb7d082df85c"))
                || result.equals(LocaleText.raw("message_76dd36bcda03"))
                || result.equals(LocaleText.raw("message_ea7debd20c69", commandPrefix()));
    }

    private boolean isWaitingResult(String result) {
        return result != null && (result.regionMatches(true, 0, "aguardando:", 0, "aguardando:".length())
                || result.regionMatches(true, 0, "waiting:", 0, "waiting:".length()));
    }

    /** A route can remain nominally WALKING while the NPC presses against its own structure. */
    private String recoverStalledBuildApproach(JsonObject action, String result) {
        if (!result.equals(LocaleText.raw("message_46cf07fc9608")) || !walker.hasGoal()
                || walker.state() == NpcWalker.State.BLOCKED) {
            movementWaitOrigin = null;
            movementWaitSince = 0;
            movementWaitAction = "";
            return result;
        }
        String key = action.toString();
        Location current = npc.getLocation();
        long now = System.currentTimeMillis();
        if (!key.equals(movementWaitAction) || movementWaitOrigin == null) {
            movementWaitAction = key;
            movementWaitOrigin = current.clone();
            movementWaitSince = now;
            return result;
        }
        if (movementWaitOrigin.distanceSquared(current) >= .75 * .75) {
            movementWaitOrigin = current.clone();
            movementWaitSince = now;
            return result;
        }
        if (now - movementWaitSince < 8000) return result;
        movementWaitOrigin = null;
        movementWaitSince = 0;
        movementWaitAction = "";
        return requestBuilderSelfClear();
    }

    private boolean moveNear(Block b, JsonObject action) {
        boolean routeBlocked = walker.state() == NpcWalker.State.BLOCKED;
        if (!walker.airborne() && !routeBlocked && walkNear(b, action) && walker.state() != NpcWalker.State.BLOCKED) return true;
        if (flyNear(b, action)) return walker.state() != NpcWalker.State.BLOCKED;
        return walkNear(b, action) && walker.state() != NpcWalker.State.BLOCKED;
    }

    private double buildReach() { return Math.min(actionReach(), 4.0); }

    /** Voo: sobe ou desce ate um ponto livre dentro do alcance do bloco, sem entrar na obra. */
    private boolean flyNear(Block b, JsonObject action) {
        Location centro = b.getLocation();
        World w = centro.getWorld();
        double limite = buildReach() - 1.8;
        Location kept = walker.goal();
        // ja voando para um ponto valido: mantem o destino para nao refazer o caminho a cada passo
        if (walker.airborne() && kept != null && kept.distance(centro) <= limite && walker.state() != NpcWalker.State.BLOCKED) return true;
        java.util.function.Predicate<Location> free = l -> insideWorkMovementBounds(l)
                && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4) && freeOfBuild(l)
                && !inSpawnZone(l) && !claimed(l.getBlock());
        Location me = npc.getLocation();
        List<Location> candidates = new ArrayList<>();
        int lim = (int) Math.floor(limite);
        for (int dx = -lim; dx <= lim; dx++) for (int dy = -lim; dy <= lim; dy++) for (int dz = -lim; dz <= lim; dz++) {
            if (dx * dx + dy * dy + dz * dz > limite * limite) continue;
            int x = centro.getBlockX() + dx, y = centro.getBlockY() + dy, z = centro.getBlockZ() + dz;
            if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) continue;
            if (!NpcWalker.clear(w, x, y, z) || !NpcWalker.clear(w, x, y + 1, z)) continue;
            Location spot = new Location(w, x + .5, y, z + .5);
            if (!free.test(spot)) continue;
            candidates.add(spot);
        }
        candidates.sort(java.util.Comparator.comparingDouble(s -> s.distanceSquared(me)));
        for (Location spot : candidates.subList(0, Math.min(400, candidates.size()))) {
            int x = spot.getBlockX(), y = spot.getBlockY(), z = spot.getBlockZ();
            boolean ground = NpcWalker.canStand(w, new WalkPathfinder.Cell(x, y, z), l -> true);
            if (!ground && !fallSafe(w, x, y, z)) continue; // ponto no ar so vale se a queda final cair no chao livre
            walker.flyToWork(npc, spot, .65, walkingSpeed(), free);
            return true;
        }
        return false;
    }

    /** Caminho pelo chao: anda ate perto do bloco (2 blocos para tras e para o lado), se o espaco estiver livre. */
    private boolean walkNear(Block b, JsonObject action) {
        // procura uma posicao livre (ar nos dois blocos) dentro do alcance, em aneis ao redor do alvo
        Location centro = b.getLocation();
        double limite = buildReach() - 1.8;
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[] {0, 1, -1, 2}) {
                        Location spot = centro.clone().add(dx, dy, dz);
                        if (!spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)
                                || spot.distance(centro) > limite || !insideWorkMovementBounds(spot) || inSpawnZone(spot) || claimed(spot.getBlock()) || !freeOfBuild(spot)) continue;
                        // Travel may use unselected ground; block edits remain inside the captured area.
                        java.util.function.Predicate<Location> allowed = location -> insideWorkMovementBounds(location)
                                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                                && !inSpawnZone(location) && !claimed(location.getBlock()) && freeOfBuild(location);
                        if (!NpcWalker.canStand(spot.getWorld(), new WalkPathfinder.Cell(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ()), allowed)) continue;
                        walker.requestWork(npc, spot.add(.5, 0, .5), .65, walkingSpeed(), allowed);
                        return walker.state() != NpcWalker.State.BLOCKED;
                    }
                }
            }
        }
        return false;
    }

    // ---------- materiais do criador ----------

    /** Consome 1 item do inventario do criador. Sem criador online ou sem material, nao constroi. */
    /** Modo criativo do construtor: materiais ilimitados, mesmo se o criador estiver em sobrevivencia. */
    private boolean usesOwnerInventory() {
        return !getConfig().getBoolean("creative-mode", false) && getConfig().getBoolean("use-owner-inventory", true);
    }

    private boolean takeFromOwner(Material m) {
        if (!usesOwnerInventory()) return true;
        Player owner = ownerPlayer();
        if (owner == null) return takeFromChest(m); // dono offline: usa os baus dentro da zona
        ItemStack item = new ItemStack(m, 1);
        if (!owner.getInventory().containsAtLeast(item, 1)) return false;
        owner.getInventory().removeItem(item);
        return true;
    }

    /** Remove 1 bloco de algum bau da zona (dono offline). */
    private boolean takeFromChest(Material m) {
        ItemStack item = new ItemStack(m, 1);
        for (Location loc : chestLocations()) {
            if (loc.getBlock().getState() instanceof org.bukkit.block.Container c && c.getInventory().containsAtLeast(item, 1)) {
                c.getInventory().removeItem(item);
                return true;
            }
        }
        return false;
    }

    /** Guarda 1 bloco quebrado num bau da zona com espaco (dono offline); sem espaco, solta perto do construtor. */
    private void putInChest(Material m) {
        ItemStack item = new ItemStack(m, 1);
        for (Location loc : chestLocations()) {
            if (loc.getBlock().getState() instanceof org.bukkit.block.Container c && c.getInventory().addItem(item).isEmpty()) return;
        }
        if (npc != null && !npc.isDead()) npc.getWorld().dropItemNaturally(npc.getLocation(), item);
    }

    private List<Location> chestCache = List.of();
    private long chestCacheAt = 0L;

    /** Baus e barris da zona marcada, em chunks carregados. Recalculado a cada 10 s (varredura cara). */
    private List<Location> chestLocations() {
        if (System.currentTimeMillis() - chestCacheAt < 10_000L) return chestCache;
        List<Location> out = new ArrayList<>();
        World w = zoneWorld();
        if (settings.getBoolean("zone.enabled", false) && w != null) {
            int x1 = settings.getInt("zone.x1"), x2 = settings.getInt("zone.x2");
            int y1 = settings.getInt("zone.y1"), y2 = settings.getInt("zone.y2");
            int z1 = settings.getInt("zone.z1"), z2 = settings.getInt("zone.z2");
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = y1; y <= y2; y++) {
                    Material t = w.getBlockAt(x, y, z).getType();
                    if (t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL || t.name().endsWith("SHULKER_BOX"))
                        out.add(new Location(w, x, y, z));
                }
            }
        }
        chestCache = out;
        chestCacheAt = System.currentTimeMillis();
        return out;
    }

    /** Devolve o bloco quebrado ao inventario do criador (o excedente cai no chao). */
    private void giveToOwner(Material m) {
        if (!usesOwnerInventory()) return;
        Player owner = ownerPlayer();
        if (owner == null) { putInChest(m); return; } // dono offline: guarda no bau da zona
        for (ItemStack rest : owner.getInventory().addItem(new ItemStack(m, 1)).values())
            owner.getWorld().dropItemNaturally(owner.getLocation(), rest);
    }

    private Location coords(JsonObject a) {
        if (!a.has("x") || !a.has("y") || !a.has("z")) return null;
        World world = a.has("area") ? getServer().getWorld(a.getAsJsonObject("area").get("world").getAsString()) : npc.getWorld();
        if (world == null || !quickBuildMode && !world.equals(npc.getWorld())) return null;
        int y = a.get("y").getAsInt();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return null;
        return new Location(world, a.get("x").getAsInt(), y, a.get("z").getAsInt());
    }

    private Block blockAt(JsonObject a) {
        Location l = coords(a);
        if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
                || !l.getWorld().getWorldBorder().isInside(l)) return null;
        return l.getBlock();
    }

    private boolean matchesReplacementSource(Block block, JsonObject action) {
        return action.has("expectedMaterial")
                && block.getType().name().equals(action.get("expectedMaterial").getAsString())
                && (!action.has("expectedData") || block.getBlockData().getAsString().equals(action.get("expectedData").getAsString()));
    }

    private String placementProblem(Block block, org.bukkit.block.data.BlockData data) {
        if (data instanceof org.bukkit.block.data.Waterlogged wet && wet.isWaterlogged())
            return LocaleText.raw("message_6b0ff960216a");
        if (CatalogGardenPolicy.FLOWERS.contains(data.getMaterial().name()) && !data.isSupported(block))
            return LocaleText.raw("message_f731dafcaffb");
        if (!CatalogGardenPolicy.isGarden(data.getMaterial().name()) && !data.isSupported(block))
            return LocaleText.raw("message_6bbaa9501a92");
        if (data.getMaterial().hasGravity() && !block.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid())
            return LocaleText.raw("message_fb78b86f6fcb");
        var location = block.getLocation();
        var bounds = new org.bukkit.util.BoundingBox(location.getX(), location.getY(), location.getZ(),
                location.getX() + 1, location.getY() + 1, location.getZ() + 1);
        var occupants=block.getWorld().getNearbyEntities(bounds, entity -> entity instanceof org.bukkit.entity.LivingEntity);
        if(occupants.size()==1 && occupants.iterator().next()==npc) return requestBuilderSelfClear();
        if (!occupants.isEmpty())
            return LocaleText.raw("message_b009f8938d54");
        return null;
    }

    private boolean placeable(Material material, JsonObject action) {
        return placeable(material, action, activeBuild);
    }

    private boolean placeable(Material material, JsonObject action, Build candidate) {
        if (material == null) return false;
        if (CatalogGardenPolicy.isGarden(material.name())) {
            String data=action.has("data") ? action.get("data").getAsString() : null;
            if (material == Material.WATER) return validatedWaterAction(action, data);
            return isCatalogPlacement(action, candidate) && (material == Material.LIGHT
                    ? CatalogGardenPolicy.light(material.name(), data) : true);
        }
        return placeableMaterial(material, action.has("data"));
    }

    private boolean validatedWaterAction(JsonObject action, String data) {
        if (!action.has("_aibuilder_contained_water") || !action.get("_aibuilder_contained_water").getAsBoolean()
                || !action.has("type") || !(action.get("type").getAsString().equals("place")
                || action.get("type").getAsString().equals("place_project"))
                || validatedGardenWorld.isBlank()
                || (action.has("area") && !validatedGardenWorld.equals(action.getAsJsonObject("area").get("world").getAsString()))
                || !CatalogGardenPolicy.source("WATER", data)) return false;
        var point = new CatalogGardenPolicy.Point(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
        return validatedGardenBasins.containsKey(point);
    }

    private static Map<CatalogGardenPolicy.Point, CatalogGardenPolicy.Cell> gardenCells(Build build) {
        if (build.gardenCells != null) return build.gardenCells;
        Map<CatalogGardenPolicy.Point, CatalogGardenPolicy.Cell> cells = new HashMap<>();
        for (var b:build.blocks) {
            String key=b.x()+","+b.y()+","+b.z();
            cells.put(new CatalogGardenPolicy.Point(b.x(),b.y(),b.z()),
                    new CatalogGardenPolicy.Cell(build.materialsByPosition.get(key), build.blockData.get(key)));
        }
        build.gardenCells = Map.copyOf(cells);
        return build.gardenCells;
    }

    private String gardenMutationProblem(JsonObject action, String material) {
        if (activeBuild == null) return null;
        String data = action.has("data") ? action.get("data").getAsString() : null;
        var point = new CatalogGardenPolicy.Point(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
        var cells = gardenCells(activeBuild);
        String problem = CatalogGardenPolicy.mutationProblem(cells, point,
                material == null ? null : new CatalogGardenPolicy.Cell(material, data));
        if (problem == null || material != null || !activeBuild.preparationTargets.contains(blockKey(action))) return problem;
        // Clearing a future support is temporary only when the server scheduled its
        // replacement, and no real flower/water currently depends on this block.
        World world = getServer().getWorld(activeBuild.world);
        if (world == null) return problem;
        List<CatalogGardenPolicy.Point> dependents = new ArrayList<>(point.sides());
        dependents.add(new CatalogGardenPolicy.Point(point.x(), point.y() + 1, point.z()));
        for (var dependent : dependents) {
            if (!world.isChunkLoaded(dependent.x() >> 4, dependent.z() >> 4)) return problem;
            String actual = world.getBlockAt(dependent.x(), dependent.y(), dependent.z()).getType().name();
            if (actual.equals("WATER") || CatalogGardenPolicy.FLOWERS.contains(actual)) return problem;
        }
        return CatalogGardenPolicy.mutationProblem(cells, point, cells.get(point));
    }

    private String gardenPlacementProblem(Block target, JsonObject action) {
        String plannedWorld = activeBuild == null ? validatedGardenWorld : activeBuild.world;
        if (!target.getWorld().getName().equals(plannedWorld)) return LocaleText.raw("message_6dace7cb6c3a");
        if ("LIGHT".equals(action.get("material").getAsString())) return null;
        var cells = activeBuild == null ? validatedGardenCells : gardenCells(activeBuild);
        var point = new CatalogGardenPolicy.Point(target.getX(),target.getY(),target.getZ());
        Set<CatalogGardenPolicy.Point> supports;
        if ("WATER".equals(action.get("material").getAsString())) {
            Map<CatalogGardenPolicy.Point, Set<CatalogGardenPolicy.Point>> basins = activeBuild == null ? validatedGardenBasins : activeBuild.gardenBasins;
            if (basins == null || basins.isEmpty()) return LocaleText.raw("message_630a4394c7cc");
            supports = basins.get(point);
            if (supports == null || supports.size()>CatalogGardenPolicy.MAX_BASIN_SUPPORTS) return LocaleText.raw("message_b892683ff44c");
        } else supports = Set.of(new CatalogGardenPolicy.Point(point.x(),point.y()-1,point.z()));
        for (var p:supports) {
            var planned=cells.get(p);
            if(planned==null || (!CatalogGardenPolicy.sealed(planned) && !CatalogGardenPolicy.SOIL.contains(planned.material())))
                return LocaleText.raw("message_84fc3a2a3696");
            World world=target.getWorld();
            if(!world.isChunkLoaded(p.x()>>4,p.z()>>4)) return LocaleText.raw("message_04ecf4399d7a");
            Block actual=world.getBlockAt(p.x(),p.y(),p.z());
            if(!inAreaOf(action,actual.getLocation()) || inSpawnZone(actual.getLocation()) || claimed(actual))
                return LocaleText.raw("message_1b2735e13c0f");
            if(!actual.getType().name().equals(planned.material())
                    || !CatalogGardenPolicy.sealed(new CatalogGardenPolicy.Cell(actual.getType().name(),actual.getBlockData().getAsString())))
                return LocaleText.raw("message_3640f6b507da");
            if(planned.data()!=null && !BlockStateSpec.matches(planned.data(),actual.getBlockData().getAsString()))
                return LocaleText.raw("message_428c5811567d");
        }
        return null;
    }

    /** Mesma regra de colocacao usada pelo construtor e pela validacao dos pre-moldados do usuario. */
    static boolean placeableMaterial(Material material, boolean hasData) {
        return material != null && !CatalogGardenPolicy.isGarden(material.name()) && !FORBIDDEN_PLACE.contains(material)
                && material.isBlock() && !material.isAir();
    }

    /** Checagem de um material/BlockData de pre-moldado do usuario (null = aceito). */
    static String userMaterialProblem(String material, String data) {
        Material m;
        try { m = Material.valueOf(material.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return "material desconhecido: " + material; }
        if (!(CatalogGardenPolicy.FLOWERS.contains(m.name()) || CatalogGardenPolicy.source(m.name(), data) || CatalogGardenPolicy.light(m.name(), data)) && !placeableMaterial(m, data != null)) return "material nao permitido: " + material + " (use um bloco disponivel no servidor Paper 26.3+ e respeite os materiais protegidos)";
        if (data != null) {
            try {
                if (Bukkit.createBlockData(data).getMaterial() != m) return "BlockData nao corresponde ao material: " + data;
            } catch (IllegalArgumentException e) { return "BlockData invalido: " + data; }
        }
        return null;
    }

    /** Carrega os pre-moldados de dataFolder/prebuilts e publica no catalogo. Erros ficam para o console e o comando. */
    static UserPrebuilts.Load loadPrebuilts(java.nio.file.Path dataFolder, int maxBlocks) {
        return loadPrebuilts(dataFolder, maxBlocks, 100);
    }

    static UserPrebuilts.Load loadPrebuilts(java.nio.file.Path dataFolder, int maxBlocks, int maxHeight) {
        return loadPrebuilts(dataFolder, ConstructionLimits.defaults(maxBlocks).withHeight(maxHeight));
    }

    static UserPrebuilts.Load loadPrebuilts(java.nio.file.Path dataFolder, ConstructionLimits limits) {
        return loadPrebuilts(dataFolder, limits, UserPrebuilts.HARD_MAX_USER_PREBUILTS);
    }

    static UserPrebuilts.Load loadPrebuilts(java.nio.file.Path dataFolder, ConstructionLimits limits, int maxUserEntries) {
        Set<String> reserved = new java.util.HashSet<>();
        for (PrebuiltCatalog.Entry e : PrebuiltCatalog.builtIn()) reserved.add(e.id());
        UserPrebuilts.Load load = UserPrebuilts.load(dataFolder.resolve(UserPrebuilts.FOLDER), reserved, limits, maxUserEntries,
                BuilderSession::userMaterialProblem);
        PrebuiltCatalog.setUser(load.entries());
        try {
            PrebuiltPreview.write(dataFolder.resolve(UserPrebuilts.FOLDER).resolve("preview"),
                    PrebuiltPreview.render(PrebuiltCatalog.builtIn(), load.entries(), load, limits, System.currentTimeMillis()));
        } catch (IOException e) {
            Bukkit.getLogger().warning("[AIBuilder] nao consegui gravar a previa dos pre-moldados: " + e.getMessage());
        }
        return load;
    }


    private boolean isCatalogPlacement(JsonObject action) {
        return isCatalogPlacement(action, activeBuild);
    }

    private boolean isCatalogPlacement(JsonObject action, Build candidate) {
        if (candidate == null || candidate.catalogId == null || PrebuiltCatalog.find(candidate.catalogId) == null
                || !action.has("type") || !action.get("type").getAsString().equals("place_project")) return false;
        String key=blockKey(action);
        return (candidate.catalogPlacements == null || candidate.catalogPlacements.contains(key))
                && java.util.Objects.equals(candidate.materialsByPosition.get(key),action.get("material").getAsString())
                && java.util.Objects.equals(candidate.blockData.get(key), action.has("data") ? action.get("data").getAsString() : null);
    }

    private Material materialOf(JsonObject a) {
        try {
            return a.has("material") ? Material.valueOf(a.get("material").getAsString().toUpperCase()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean inReach(Block b) {
        return b.getLocation().add(.5, .5, .5).distance(npc.getLocation()) <= buildReach();
    }

    // ---------- fora do caminho da obra ----------
    /** Verdadeiro se a celula (x,y,z) faz parte da obra ativa (ainda que nao construida). */
    private boolean plannedAt(int x, int y, int z) {
        return activeBuild != null && activeBuild.occupies(x, y, z);
    }

    /** Translate the real NPC collider, including fractional positions and custom dimensions. */
    private org.bukkit.util.BoundingBox bodyAt(Location location) {
        Location current = npc.getLocation();
        return npc.getBoundingBox().clone().shift(location.getX() - current.getX(),
                location.getY() - current.getY(), location.getZ() - current.getZ());
    }

    private org.bukkit.util.BoundingBox editBox(int x, int y, int z) {
        return new org.bukkit.util.BoundingBox(x, y, z, x + 1, y + 1, z + 1);
    }

    /** Future cells stay walkable; the current edit must be clear of the whole body. */
    private boolean freeOfBuild(Location location) {
        JsonObject action = queue.peek();
        if (action == null || !action.has("type") || !Set.of("place", "place_project", "replace").contains(action.get("type").getAsString())
                || !action.has("x") || !action.has("y") || !action.has("z")) return true;
        return !bodyAt(location).overlaps(editBox(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt()));
    }

    private boolean npcInTheWay(Block target) {
        return npc.getBoundingBox().overlaps(editBox(target.getX(), target.getY(), target.getZ()));
    }

    /**
     * El constructor bloqueado por su propia obra sale con el escape fino (stepOffBuild, sin teleport);
     * si no hay salida local, cae en el auto-cleareo de rango mayor. Devuelve el motivo "aguardando"
     * para que la accion se reintente, nunca se descarte.
     */
    private String escapeEdit(Block target) {
        if (stepOffBuild(target)) return LocaleText.raw("message_2d5a55cc5fd6");
        return requestBuilderSelfClear();
    }

    /** Uma queda vertical a partir de (x,y,z) termina num bloco solido real, sem liquidos. */
    private boolean fallSafe(World w, int x, int y, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        for (int yy = y - 1; yy > w.getMinHeight(); yy--) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block below = w.getBlockAt(x, yy, z);
            if (below.isLiquid()) return false;
            if (!below.isPassable()) return true;
        }
        return false;
    }

    private String sidestepTarget = "";

    /** Move away from the current edit, never treating an unrelated walk as an escape. */
    private boolean stepOffBuild(Block target) {
        Location me = npc.getLocation();
        World world = me.getWorld();
        var obstacle = editBox(target.getX(), target.getY(), target.getZ());
        String identity = world.getUID() + ":" + target.getX() + "," + target.getY() + "," + target.getZ();
        java.util.function.Predicate<Location> allowed = location -> insideWorkMovementBounds(location)
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && !bodyAt(location).overlaps(obstacle)
                && !inSpawnZone(location) && !claimed(location.getBlock());
        Location previous = walker.goal();
        // .45 clearance covers the controller's .35 arrival tolerance plus a margin.
        // Otherwise it can report ARRIVED while its body still intersects the edit.
        if (identity.equals(sidestepTarget) && walker.state() == NpcWalker.State.WALKING
                && previous != null && allowed.test(previous) && !bodyAt(previous).expand(.45).overlaps(obstacle)
                && NpcWalker.clear(world, previous.getBlockX(), previous.getBlockY(), previous.getBlockZ())
                && NpcWalker.clear(world, previous.getBlockX(), previous.getBlockY() + 1, previous.getBlockZ())) return true;
        walker.stop(npc);
        sidestepTarget = identity;
        List<Location> candidates = new ArrayList<>();
        int cx = me.getBlockX(), cy = me.getBlockY(), cz = me.getBlockZ();
        for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (int dz = -8; dz <= 8; dz++) {
            int x = cx + dx, y = cy + dy, z = cz + dz;
            Location spot = new Location(world, x + .5, y, z + .5);
            if (!allowed.test(spot) || bodyAt(spot).expand(.45).overlaps(obstacle)) continue;
            if (!NpcWalker.clear(world, x, y, z) || !NpcWalker.clear(world, x, y + 1, z)) continue;
            boolean ground = NpcWalker.canStand(world, new WalkPathfinder.Cell(x, y, z), l -> true);
            if (ground || fallSafe(world, x, y, z)) candidates.add(spot);
        }
        candidates.sort(java.util.Comparator.comparingDouble(spot -> spot.distanceSquared(me)));
        // Try another position (and flight) when the closest safe endpoint has no path.
        for (Location spot : candidates.subList(0, Math.min(8, candidates.size()))) {
            boolean ground = NpcWalker.canStand(world, new WalkPathfinder.Cell(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ()), l -> true);
            if (ground && !walker.airborne()) {
                walker.requestWork(npc, spot, .35, walkingSpeed(), allowed);
                walker.tick(npc);
                if (walker.state() != NpcWalker.State.BLOCKED) return true;
                walker.stop(npc);
            }
            walker.flyToWork(npc, spot, .35, walkingSpeed(), allowed);
            walker.tick(npc);
            if (walker.state() != NpcWalker.State.BLOCKED) return true;
            walker.stop(npc);
        }
        // Nearby endpoints can all be unreachable when the builder is boxed in by its own work.
        // Search outward through loaded, permitted air (up first) for a safe exit up to 32 blocks away.
        FlightPathfinder.Air air = cell -> {
            int x=cell.x(), y=cell.y(), z=cell.z();
            if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()
                    || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Location cellLocation=new Location(world,x+.5,y,z+.5);
            return NpcWalker.clear(world,x,y,z) && NpcWalker.clear(world,x,y+1,z) && allowed.test(cellLocation);
        };
        var escape = FlightPathfinder.findNearest(new WalkPathfinder.Cell(me.getBlockX(),me.getBlockY(),me.getBlockZ()),
                air, cell -> {
                    Location spot=new Location(world,cell.x()+.5,cell.y(),cell.z()+.5);
                    if (bodyAt(spot).expand(.45).overlaps(obstacle)) return false;
                    boolean ground=NpcWalker.canStand(world,cell,allowed);
                    return ground || fallSafe(world,cell.x(),cell.y(),cell.z());
                },6000,32);
        if (!escape.isEmpty()) {
            WalkPathfinder.Cell cell=escape.getLast();
            Location exit=new Location(world,cell.x()+.5,cell.y(),cell.z()+.5);
            walker.flyToWork(npc,exit,.35,walkingSpeed(),allowed);
            walker.tick(npc);
            if (walker.state()!=NpcWalker.State.BLOCKED) return true;
            walker.stop(npc);
        }
        return false;
    }

    private boolean inSpawnZone(Location l) {
        int radius = getConfig().getInt("spawn-exclusion-radius", 30);
        Location spawn = l.getWorld().getSpawnLocation();
        return spawn.distance(l) < radius;
    }

    private boolean claimed(Block b) {
        return ClaimProtection.claimed(getServer().getPluginManager(), b.getLocation());
    }

    private boolean playerModified(Block b) {
        return playerModified(b, null);
    }

    /** Without CoreProtect, an explicit user-selected area is the only opt-in fallback. */
    private boolean playerModified(Block b, JsonObject selectedArea) {
        if (coreProtect == null) coreProtect = loadCoreProtectApi();
        if (coreProtect == null) {
            boolean allowSelectedArea = getConfig().getBoolean("allow-break-without-coreprotect-in-selection", true)
                    && selectedArea != null && selectedArea.isJsonObject()
                    && inAreaJson(selectedArea, b.getLocation())
                    && (NATURAL_TERRAIN.contains(b.getType().name()) || isVegetation(b.getType()))
                    && !(b.getState() instanceof org.bukkit.block.TileState);
            return !allowSelectedArea;
        }
        int seconds = 60 * 60 * 24 * 3650; // 10 anos de historico
        try {
            Object rows = coreProtect.getClass().getMethod("blockLookup", Block.class, int.class).invoke(coreProtect, b, seconds);
            return !(rows instanceof List<?> l) || !l.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("error", LocaleText.format(plugin, "message_88ec346c8782", e));
            return true; // falha fechada: trata como protegido
        }
    }

    private boolean rateOk() {
        // OP speed mode uses the same bounded per-tick work and time budget as terrain leveling.
        if (quickBuildMode) return true;
        int configured = getConfig().getInt("max-blocks-per-minute", 600);
        return actionBudget.allows(System.currentTimeMillis(), configured);
    }

    private void recordSuccessfulAction() {
        if (!quickBuildMode) actionBudget.record(System.currentTimeMillis());
    }

    private String coords(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private void log(String kind, String text) {
        plugin.logAction("[" + name + "][" + kind + "] " + text);
    }
}
