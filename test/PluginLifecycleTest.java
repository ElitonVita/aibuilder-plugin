import com.elitonvita.aibuilder.AIBuilderPlugin;
import com.elitonvita.aibuilder.ActionBudget;
import com.elitonvita.aibuilder.BuilderUi;
import com.elitonvita.aibuilder.BlueprintCompiler;
import com.elitonvita.aibuilder.RequestGuard;
import com.elitonvita.aibuilder.NpcWalker;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/** Serverless white-box checks of the real plugin's lifecycle methods.
 * Constructor-free allocation is confined to this fixture: JavaPlugin normally requires Paper's classloader.
 * Proxies intentionally model only the methods touched here; this is not an in-game acceptance test.
 */
public class PluginLifecycleTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object stateTarget(Object target, String name, boolean method) throws Exception {
        if (!(target instanceof AIBuilderPlugin)) return target;
        if (method ? Arrays.stream(AIBuilderPlugin.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name))
                : Arrays.stream(AIBuilderPlugin.class.getDeclaredFields()).anyMatch(f -> f.getName().equals(name))) return target;
        return ((Map<?, ?>) field(AIBuilderPlugin.class, "builders").get(target)).values().iterator().next();
    }
    private static void set(Object target, String name, Object value) throws Exception {
        target = stateTarget(target, name, false); field(target.getClass(), name).set(target, value);
    }
    private static Object get(Object target, String name) throws Exception {
        target = stateTarget(target, name, false); return field(target.getClass(), name).get(target);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        target = stateTarget(target, name, true);
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    public static final class HistoryLookup {
        private final Object rows;
        private final boolean fail;
        public HistoryLookup(Object rows, boolean fail) { this.rows = rows; this.fail = fail; }
        public Object blockLookup(org.bukkit.block.Block block, int seconds) {
            if (fail) throw new IllegalStateException("simulated history failure");
            return rows;
        }
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("toString")) return "LifecycleTest(" + type.getSimpleName() + ")";
            if (values.containsKey(method.getName())) return values.get(method.getName());
            if (method.getReturnType() == void.class) return null;
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return false;
            if (method.getName().equals("getBoundingBox") && method.getReturnType() == org.bukkit.util.BoundingBox.class)
                return new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1);
            throw new UnsupportedOperationException("Unmodelled test API: " + method);
        });
    }
    private static JsonObject area(int x) {
        JsonObject area = new JsonObject(); area.addProperty("world", "world");
        area.addProperty("x1", x); area.addProperty("x2", x + 5);
        area.addProperty("y1", 64); area.addProperty("y2", 70);
        area.addProperty("z1", 0); area.addProperty("z2", 5); return area;
    }
    private static final class Fixture implements AutoCloseable {
        final AIBuilderPlugin plugin;
        final Path directory = Files.createTempDirectory("aibuilder-lifecycle-");
        final CommandSender sender = proxy(CommandSender.class, Map.of("getName", "test", "hasPermission", true, "isOp", true));
        final YamlConfiguration config = new YamlConfiguration();
        final Map<String,Object> worldValues = new HashMap<>();
        final Map<String,Object> serverValues = new HashMap<>();
        final Object session;
        final YamlConfiguration settings;
        final UUID builderId = UUID.randomUUID();
        Fixture() throws Exception {
            // Paper 26.3 delegates Material properties to server registries. Model only
            // the fixture materials; production always uses the real registry.
            for (org.bukkit.Material material : List.of(org.bukkit.Material.STONE, org.bukkit.Material.DIRT,
                    org.bukkit.Material.SAND, org.bukkit.Material.AIR, org.bukkit.Material.DIAMOND_SWORD,
                    org.bukkit.Material.GLASS_PANE, org.bukkit.Material.OAK_DOOR, org.bukkit.Material.OAK_LEAVES)) {
                var blockType = material == org.bukkit.Material.DIAMOND_SWORD ? null : proxy(org.bukkit.block.BlockType.class,
                        Map.of("isSolid", !Set.of(org.bukkit.Material.AIR,org.bukkit.Material.GLASS_PANE,org.bukkit.Material.OAK_DOOR,org.bukkit.Material.OAK_LEAVES).contains(material), "hasGravity", material == org.bukkit.Material.SAND, "isAir", material == org.bukkit.Material.AIR));
                field(org.bukkit.Material.class, "blockType").set(material, (java.util.function.Supplier<org.bukkit.block.BlockType>) () -> blockType);
                var itemType = proxy(org.bukkit.inventory.ItemType.class, Map.of());
                field(org.bukkit.Material.class, "itemType").set(material, (java.util.function.Supplier<org.bukkit.inventory.ItemType>) () -> itemType);
            }
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            plugin = (AIBuilderPlugin) unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), AIBuilderPlugin.class);
            worldValues.putAll(Map.of("getName", "world", "getMinHeight", -64, "getMaxHeight", 320, "isChunkLoaded", true, "getChunkAt", proxy(org.bukkit.Chunk.class, Map.of("getEntities", new org.bukkit.entity.Entity[0]))));
            World world = proxy(World.class, worldValues);
            serverValues.put("getWorld", world); serverValues.put("getWorlds", List.of()); serverValues.put("getOnlinePlayers", List.of()); serverValues.put("getPlayer", null);
            serverValues.put("getPluginManager", proxy(org.bukkit.plugin.PluginManager.class, new HashMap<String,Object>() {{ put("getPlugin", null); }}));
            serverValues.put("getConsoleSender", proxy(org.bukkit.command.ConsoleCommandSender.class, Map.of("getName", "CONSOLE", "hasPermission", true, "isOp", true)));
            Server server = proxy(Server.class, serverValues);
            field(JavaPlugin.class, "server").set(plugin, server);
            field(JavaPlugin.class, "newConfig").set(plugin, config);
            field(JavaPlugin.class, "dataFolder").set(plugin, directory.toFile());
            field(JavaPlugin.class, "configFile").set(plugin, directory.resolve("config.yml").toFile());
            field(JavaPlugin.class, "logger").set(plugin, Logger.getLogger("PluginLifecycleTest"));
            set(plugin, "builders", new LinkedHashMap<>()); set(plugin, "selected", new HashMap<>());
            set(plugin, "registryHealthy", true);
            Class<?> sessionType = Class.forName("com.elitonvita.aibuilder.BuilderSession");
            Constructor<?> sessionCtor = sessionType.getDeclaredConstructor(AIBuilderPlugin.class, UUID.class, String.class, UUID.class);
            sessionCtor.setAccessible(true);
            session = sessionCtor.newInstance(plugin, builderId, "Alfred", UUID.randomUUID());
            ((Map<UUID, Object>) get(plugin, "builders")).put(builderId, session);
            settings = (YamlConfiguration) get(session, "settings");
            config.set("language", "PT");
            set(plugin, "gson", new Gson()); set(plugin, "queue", new ArrayDeque<>());
            set(plugin, "actionBudget", new ActionBudget()); set(plugin, "history", new HashMap<>());
            set(plugin, "walker", new NpcWalker()); set(plugin, "movementNotice", "");
            set(plugin, "requests", new RequestGuard()); set(plugin, "ui", new BuilderUi());
            set(plugin, "rejectionCounts", new TreeMap<>());
            set(plugin, "dailyQuota", new com.elitonvita.aibuilder.DailyRequestQuota(directory.resolve("ai-daily-quota.properties")));
            set(plugin, "npc", proxy(Mannequin.class, Map.of("isDead", false, "getWorld", world, "getVelocity", new org.bukkit.util.Vector(), "getLocation", new Location(world, 1, 64, 1), "getBoundingBox", new org.bukkit.util.BoundingBox(.7,64,.7,1.3,65.8,1.3), "getUniqueId", UUID.randomUUID())));
            settings.set("zone.enabled", true); config.set("announce-actions", false); config.set("ui.native-dialogs", false);
            currentArea(area(0));
        }
        Path builderDirectory() { return directory.resolve("builders").resolve(builderId.toString()); }
        void currentArea(JsonObject area) {
            for (String key : area.keySet()) {
                if (key.equals("world")) settings.set("zone." + key, area.get(key).getAsString());
                else settings.set("zone." + key, area.get(key).getAsInt());
            }
        }
        Object pending(JsonObject area) throws Exception {
            Class<?> buildType = Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");
            Constructor<?> constructor = buildType.getDeclaredConstructor(String.class, String.class, List.class, JsonObject.class);
            constructor.setAccessible(true);
            Object build = constructor.newInstance("fixture", "world", List.of(new BlueprintCompiler.Block(area.get("x1").getAsInt(), 64, 0, "STONE", 0)), area);
            JsonObject action = new JsonObject(); action.addProperty("type", "place_project"); action.addProperty("material", "STONE");
            action.addProperty("x", area.get("x1").getAsInt()); action.addProperty("y", 64); action.addProperty("z", 0);
            set(plugin, "pendingBuild", build); set(plugin, "pendingArea", area.deepCopy());
            set(plugin, "pendingActions", new ArrayList<>(List.of(action)));
            set(plugin, "pendingExpires", System.currentTimeMillis() + 60_000);
            return build;
        }
        void command(String... args) { plugin.onCommand(sender, null, "construtor", args); }
        Deque<?> queue() throws Exception { return (Deque<?>) get(plugin, "queue"); }
        org.bukkit.block.Block blockAt(int x, int y, int z) {
            World world = plugin.getServer().getWorld("world");
            return proxy(org.bukkit.block.Block.class, new HashMap<String,Object>() {{ put("getLocation", new org.bukkit.Location(world, x, y, z)); put("getType", org.bukkit.Material.STONE); put("getState", null); put("isPassable", false); }});
        }
        public void close() throws Exception {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    @SuppressWarnings("unchecked")
    public static void multiBuilders() throws Exception {
        int before = checks;
        try (Fixture f = new Fixture()) {
            Object alfred = f.session;
            Class<?> type = alfred.getClass();
            Constructor<?> ctor = type.getDeclaredConstructor(AIBuilderPlugin.class, UUID.class, String.class, UUID.class);
            ctor.setAccessible(true);
            UUID owner = (UUID) get(alfred, "ownerId");
            UUID bruceId = UUID.randomUUID();
            Object bruce = ctor.newInstance(f.plugin, bruceId, "Bruce", owner);
            Map<UUID, Object> registry = (Map<UUID, Object>) get(f.plugin, "builders");
            registry.put(bruceId, bruce);
            World bruceWorld = f.plugin.getServer().getWorld("world");
            set(bruce, "npc", proxy(Mannequin.class, Map.of("isDead", false, "getWorld", bruceWorld,
                    "getVelocity", new org.bukkit.util.Vector(), "getLocation", new Location(bruceWorld, 100, 64, 1), "getUniqueId", UUID.randomUUID())));
            YamlConfiguration bruceSettings = (YamlConfiguration) get(bruce, "settings");
            bruceSettings.set("zone.enabled", true);
            for (String key : area(100).keySet()) {
                var value = area(100).get(key);
                bruceSettings.set("zone." + key, key.equals("world") ? value.getAsString() : value.getAsInt());
            }
            check(((JsonObject) invoke(alfred, "currentAreaJson", new Class<?>[0])).equals(area(0)), "Alfred's zone changed with Bruce");
            check(((JsonObject) invoke(bruce, "currentAreaJson", new Class<?>[0])).equals(area(100)), "Bruce did not retain its own zone");
            for (String independent : List.of("queue", "requests", "history", "ui", "walker", "actionBudget", "rejectionCounts"))
                check(get(alfred, independent) != get(bruce, independent), independent + " was shared between builders");
            check((int) invoke(f.plugin, "builderLimit", new Class<?>[0]) == 3, "default server limit is not 3");
            f.config.set("builders.max", 0);
            check((int) invoke(f.plugin, "builderLimit", new Class<?>[0]) == 1, "limit was not clamped to 1");
            f.config.set("builders.max", 99);
            check((int) invoke(f.plugin, "builderLimit", new Class<?>[0]) == 5, "absolute limit exceeded 5");
            f.config.set("builders.max", 1);
            org.bukkit.entity.Player player = proxy(org.bukkit.entity.Player.class, Map.of("getUniqueId", owner, "hasPermission", true, "isOp", false, "getName", "owner", "getLocation", new Location(bruceWorld, 1, 64, 1)));
            f.plugin.onCommand(player, null, "construtor", new String[]{"spawn"});
            check(registry.size() == 2, "lowering limit removed builders or allowed spawn");
            f.plugin.onCommand(player, null, "construtor", new String[]{"select", "bruce"});
            check(invoke(f.plugin, "selectedFor", new Class<?>[]{org.bukkit.entity.Player.class}, player) == bruce, "selection did not choose Bruce");
            check(f.plugin.onTabComplete(player, null, "construtor", new String[]{"b"}).equals(List.of("bruce")), "name completion is missing");
            check(f.plugin.onTabComplete(player, null, "construtor", new String[]{"bruce", "st"}).containsAll(List.of("stay", "stop", "status")), "named command completion is missing");
            long bruceToken = ((RequestGuard)get(bruce, "requests")).begin();
            JsonObject action = new JsonObject(); action.addProperty("type", "move"); action.addProperty("x", 1); action.addProperty("y", 64); action.addProperty("z", 0);
            ((Deque<JsonObject>)get(bruce, "queue")).add(action);
            f.pending(area(0));
            Object proposed = get(alfred, "pendingBuild");
            String alfredButton = (String)invoke(alfred, "confirmationToken", new Class<?>[0]);
            check(!alfredButton.equals(invoke(bruce, "confirmationToken", new Class<?>[0])), "confirmation token is shared by builders");
            Object replacementSession = ctor.newInstance(f.plugin, f.builderId, "Alfred", owner);
            check(!alfredButton.equals(invoke(replacementSession, "confirmationToken", new Class<?>[0])), "old buttons can confirm a plan after restart");
            f.plugin.onCommand(player, null, "construtor", new String[]{"alfred", "yes"});
            check(get(alfred, "activeBuild") == proposed, "explicit confirmation went to the selected Bruce");
            check(((Deque<?>)get(bruce, "queue")).size() == 1 && ((RequestGuard)get(bruce, "requests")).accepts(bruceToken), "Alfred confirmation changed Bruce");
            check(!(boolean)invoke(bruce, "canRequest", new Class<?>[]{org.bukkit.entity.Player.class}, player), "busy builder accepted a new request");
            String busy = (String) invoke(bruce, "busyDescription", new Class<?>[0]);
            check(busy.contains("planejando") && busy.contains("1 ações restantes"), "busy response omitted stage/action count");
            ((RequestGuard)get(bruce, "requests")).complete(bruceToken);
            check(((String)invoke(bruce, "busyDescription", new Class<?>[0])).contains("executando"), "queued action was not described");
            f.plugin.onCommand(player, null, "construtor", new String[]{"alferd", "stop"});
            check(((Deque<?>)get(bruce, "queue")).size() == 1, "misspelled name stopped selected builder");
            org.bukkit.entity.Player stranger = proxy(org.bukkit.entity.Player.class, Map.of("getUniqueId", UUID.randomUUID(), "hasPermission", true, "isOp", false));
            f.plugin.onCommand(stranger, null, "construtor", new String[]{"bruce", "stop"});
            check(((Deque<?>)get(bruce, "queue")).size() == 1, "another player controlled Bruce");
            f.plugin.onCommand(player, null, "construtor", new String[]{"alfred", "stop"});
            check(((Deque<?>)get(bruce, "queue")).size() == 1 && get(alfred, "activeBuild") == null, "stopping Alfred affected Bruce");
            // Save an identically named build in each scope: neither _ultima nor geometry may collide.
            f.pending(area(0));
            Class<?> buildType = proposed.getClass();
            invoke(alfred, "saveBuild", new Class<?>[]{buildType, String.class}, get(alfred, "pendingBuild"), "parada");
            Constructor<?> buildCtor = buildType.getDeclaredConstructor(String.class, String.class, List.class, JsonObject.class);
            buildCtor.setAccessible(true);
            Object bruceBuild = buildCtor.newInstance("fixture", "world", List.of(new BlueprintCompiler.Block(100,64,0,"STONE",0)), area(100));
            invoke(bruce, "saveBuild", new Class<?>[]{buildType, String.class}, bruceBuild, "parada");
            Path bruceDir = (Path) invoke(bruce, "dataDirectory", new Class<?>[0]);
            check(!Files.readString(f.builderDirectory().resolve("builds/fixture.json")).equals(Files.readString(bruceDir.resolve("builds/fixture.json"))), "identically named works collided");
            set(alfred, "areaUsed", true); bruceSettings.set("follow-owner", false);
            invoke(alfred, "saveBuilderRegistry", new Class<?>[0]); invoke(bruce, "saveBuilderRegistry", new Class<?>[0]);
            registry.clear();
            invoke(f.plugin, "loadBuilders", new Class<?>[0]);
            Object restoredAlfred = invoke(f.plugin, "named", new Class<?>[]{String.class}, "alfred");
            Object restoredBruce = invoke(f.plugin, "named", new Class<?>[]{String.class}, "bruce");
            check(registry.size() == 2 && restoredAlfred != null && restoredBruce != null, "restart registry lost a builder");
            check(get(restoredAlfred, "ownerId").equals(owner) && get(restoredBruce, "ownerId").equals(owner), "restart lost owners");
            check((boolean)get(restoredAlfred, "areaUsed") && !((YamlConfiguration)get(restoredBruce,"settings")).getBoolean("follow-owner"), "restart lost consumed zone or independent follow mode");
            check(((JsonObject)invoke(restoredBruce,"currentAreaJson",new Class<?>[0])).equals(area(100)), "restart changed Bruce's zone");
            // A instancia viva do Alfred e a do registro (restoredAlfred): com fila ocupada, apagar deve ser recusado.
            ((Deque) get(restoredAlfred, "queue")).add(new JsonObject());
            check((boolean) invoke(restoredAlfred, "isActive", new Class<?>[0]), "busy builder not seen as active");
            f.plugin.onCommand(player, null, "construtor", new String[]{"alfred", "delete"});
            check(registry.size() == 2 && Files.exists(f.builderDirectory().resolve("builder.json")), "active builder was deleted");
            ((Deque) get(restoredAlfred, "queue")).clear();
            check((int) invoke(restoredBruce, "removeWands", new Class<?>[0]) == 0, "no-player wand removal reported items");
            f.plugin.onCommand(player, null, "construtor", new String[]{"alfred", "delete"});
            check(registry.size() == 1 && Files.exists(bruceDir.resolve("builder.json")), "deleting Alfred deleted Bruce");
            check(!Files.exists(f.builderDirectory().resolve("builder.json")), "deleted builder can reappear from registry");
            check(Files.exists(f.builderDirectory().resolve("builds/fixture.json")), "deleting builder erased saved work");
        }
        try (Fixture f = new Fixture()) {
            Map<UUID, Object> registry = (Map<UUID, Object>)get(f.plugin, "builders");
            registry.clear();
            JsonObject legacy = new JsonObject(); legacy.addProperty("owner", UUID.randomUUID().toString());
            legacy.addProperty("npc", UUID.randomUUID().toString()); legacy.addProperty("world", "world");
            legacy.addProperty("x", 1); legacy.addProperty("y", 64); legacy.addProperty("z", 2);
            Files.writeString(f.directory.resolve("builder.json"), legacy.toString());
            Files.createDirectories(f.directory.resolve("builds"));
            Files.writeString(f.directory.resolve("builds/_ultima.json"), "legacy-work-preserved");
            f.config.set("zone.enabled", true); f.config.set("zone.world", "world"); f.config.set("zone.x1", 42);
            invoke(f.plugin, "loadBuilders", new Class<?>[0]);
            Object migrated = invoke(f.plugin, "named", new Class<?>[]{String.class}, "Alfred");
            check(registry.size() == 1 && migrated != null, "legacy builder was not migrated to Alfred");
            Path migratedDir = (Path)invoke(migrated, "dataDirectory", new Class<?>[0]);
            check(Files.readString(migratedDir.resolve("builds/_ultima.json")).equals("legacy-work-preserved"), "migration lost original work");
            check(((YamlConfiguration)get(migrated,"settings")).getInt("zone.x1") == 42, "migration lost legacy zone");
            check(Files.exists(f.directory.resolve("builder.legacy.json")) && !Files.exists(f.directory.resolve("builder.json")), "migration did not preserve original registry backup");
            registry.clear(); invoke(f.plugin, "loadBuilders", new Class<?>[0]);
            check(registry.size() == 1, "restart after migration duplicated builder");
            f.command("alfred", "delete");
            registry.clear(); invoke(f.plugin, "loadBuilders", new Class<?>[0]);
            check(registry.isEmpty(), "legacy backup resurrected deleted builder");
            // An unreadable registry must block spawn rather than pretend the server is empty.
            Files.writeString(migratedDir.resolve("builder.json"), "broken");
            invoke(f.plugin, "loadBuilders", new Class<?>[0]);
            check(!(boolean)get(f.plugin,"registryHealthy"), "corrupt registry did not block creation");
        }
        System.out.println("PASS: " + (checks - before) + " multi-builder isolation and persistence assertions");
    }

    public static void terrainIntegration() throws Exception {
        int before=checks;
        try(Fixture f=new Fixture()) {
            YamlConfiguration resourceDefaults=new YamlConfiguration();resourceDefaults.set("terrain.defaults-version",1);
            f.config.setDefaults(resourceDefaults);f.config.set("max-clear-volume",4096);
            invoke(f.plugin,"migrateTerrainDefaults",new Class<?>[0]);
            check(f.config.getInt("max-clear-volume")==4096,"existing max-clear-volume was changed during migration");
            check(f.config.contains("terrain.defaults-version",true),"terrain migration version not persisted explicitly");
            f.config.set("max-clear-volume",8000);invoke(f.plugin,"migrateTerrainDefaults",new Class<?>[0]);
            check(f.config.getInt("max-clear-volume")==8000,"explicit terrain limit was overwritten");
            f.config.set("max-clear-volume",262144);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==262144,"default terrain limit changed");
            f.config.set("max-clear-volume",16_000_000);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==16_000_000,"technical ceiling rejected");
            f.config.set("max-clear-volume",2_000_000);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==2_000_000,"explicit terrain limit was capped below the technical ceiling");
            f.config.set("max-clear-volume",20_000_000L);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==16_000_000,"over-ceiling terrain limit was not capped");
            f.config.set("max-clear-volume",-5);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==262144,"invalid terrain limit did not fall back to default");
            f.config.set("max-clear-volume","not-a-number");
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==262144,"non-numeric terrain limit did not fall back to default");
            f.config.set("max-clear-volume",1.5);
            check((int)invoke(f.plugin,"terrainVolumeLimit",new Class<?>[0])==262144,"fractional terrain limit did not fall back to default");
            f.config.set("max-clear-volume",262144);
            JsonObject captured=area(0);f.currentArea(area(100));
            World world=f.plugin.getServer().getWorld("world");
            f.worldValues.put("isChunkLoaded",false);
            org.bukkit.entity.Player player=proxy(org.bukkit.entity.Player.class,Map.of("getLocation",new Location(world,1,64,1),"getName","owner"));
            String context=(String)invoke(f.plugin,"positionLine",new Class<?>[]{org.bukkit.entity.Player.class,JsonObject.class},player,captured);
            check(context.contains("AREA DE CONSTRUCAO: world x=0..5")&&!context.contains("x=100..105"),"LLM still receives current selection instead of captured work area");
            check(context.contains("largura 6 x profundidade 6 x altura 7"),"captured dimensions missing from LLM context");
            var bounds=(com.elitonvita.aibuilder.TerrainGrid.Bounds)invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},captured,true);
            check(bounds.x1()==0&&bounds.x2()==5&&bounds.y1()==48&&bounds.y2()==70,"survey not bounded to request footprint/depth");
            JsonObject low=captured.deepCopy();low.addProperty("y1",-60);
            bounds=(com.elitonvita.aibuilder.TerrainGrid.Bounds)invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},low,true);
            check(bounds.y1()==-64&&bounds.volume()==4860L,"survey depth was not clipped to actual world minimum");
            JsonObject castle=area(0);castle.addProperty("x2",125);castle.addProperty("y2",213);castle.addProperty("z2",92);
            f.config.set("max-clear-volume",8_000_000);
            bounds=(com.elitonvita.aibuilder.TerrainGrid.Bounds)invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},castle,true);
            check(bounds.volume()==1_945_188L,"Neuschwanstein envelope with 16-block deep-level search is wrong");
            bounds=(com.elitonvita.aibuilder.TerrainGrid.Bounds)invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},castle,false);
            check(bounds.volume()==1_757_700L,"normal leveling included below-selection positions");
            f.config.set("max-clear-volume",1_900_000);
            try { invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},castle,true); throw new AssertionError("insufficient Neuschwanstein survey limit accepted"); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("1945188")&&expected.getMessage().contains("max-clear-volume"),"large-region rejection lacks dimensions, volume, and config key"); }
            JsonObject full=area(0);full.addProperty("x2",199);full.addProperty("y2",263);full.addProperty("z2",199);
            f.config.set("max-clear-volume",10_000_000);
            bounds=(com.elitonvita.aibuilder.TerrainGrid.Bounds)invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},full,true);
            check(bounds.volume()==8_640_000L,"200x200x200 plus 16 surveyed blocks was rejected or miscounted");
            f.config.set("max-clear-volume",8_000_000);
            try { invoke(f.plugin,"surveyBounds",new Class<?>[]{JsonObject.class,boolean.class},full,true); throw new AssertionError("insufficient 200-cube survey limit accepted"); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("8640000")&&expected.getMessage().contains("max-clear-volume"),"200-cube rejection lacks required volume or config key"); }
            f.config.set("max-clear-volume",10_000_000);
            f.command("terrain","level");
            Object preparation=get(f.plugin,"terrainWork");
            check(preparation!=null,"level command did not create per-builder preparation");
            JsonObject selected=(JsonObject)get(f.plugin,"terrainArea");
            check(selected.get("x1").getAsInt()==100,"preparation did not capture current selection");
            check(selected.get("y1").getAsInt()==64,"normal leveling expanded below the selected area");
            JsonObject afterLevel=(JsonObject)invoke(f.plugin,"areaAfterLeveling",new Class<?>[]{JsonObject.class,int.class},area(100),65);
            check(afterLevel.get("y1").getAsInt()==65&&afterLevel.get("y2").getAsInt()==70,
                    "leveling expanded the selected build upper bound");
            check(!((com.elitonvita.aibuilder.TerrainWork)preparation).confirm(System.currentTimeMillis()),"level confirmed before survey/protection preview");
            f.command("terrain","clean","yes");
            check(get(f.plugin,"terrainWork")==preparation,"wrong-mode confirmation changed active preparation");
            check(!(boolean)invoke(f.plugin,"canRequest",new Class<?>[]{org.bukkit.entity.Player.class},player),"LLM request accepted during preparation");
            f.command("stop");
            check(get(f.plugin,"terrainWork")==null,"stop did not cancel terrain preparation");
            f.pending(captured);f.command("terrain","level");
            check(get(f.plugin,"terrainWork")==null&&get(f.plugin,"pendingActions")!=null,"terrain preparation overwrote a construction plan");
            check(f.plugin.onTabComplete(f.sender,null,"construtor",new String[]{"alfred","terrain","l"}).contains("level"),"level command not suggested");
            check(f.plugin.onTabComplete(f.sender,null,"construtor",new String[]{"alfred","terrain","level",""}).containsAll(List.of("yes","select")),"terrain confirmation/selection not suggested");
        }
        try(Fixture f=new Fixture()) {
            f.command("terrain","level");
            String token=(String)get(f.plugin,"terrainToken");
            String yes=(String)invoke(f.plugin,"terrainConfirmCommand",new Class<?>[0]);
            String no=(String)invoke(f.plugin,"terrainCancelCommand",new Class<?>[0]);
            check(yes.equals("/builder alfred terrain level yes "+token),"Yes button routes to wrong builder/preparation");
            check(no.equals("/builder alfred terrain cancel "+token),"No button routes to wrong builder/preparation");
            var choices=BuilderUi.terrainChoices(yes,no);
            check(((net.kyori.adventure.text.TextComponent)choices).content().equals("[Sim]"),"Yes label missing");
            check(choices.clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand(yes)),"Yes requires typing instead of executing");
            var noChoice=choices.children().get(1);
            check(((net.kyori.adventure.text.TextComponent)noChoice).content().equals("[Não]")&&noChoice.clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand(no)),"No button missing or confirms instead of cancelling");
            var access=new com.elitonvita.aibuilder.TerrainWork.Access() {
                public com.elitonvita.aibuilder.TerrainGrid.Cell read(com.elitonvita.aibuilder.TerrainGrid.Point p) {
                    return new com.elitonvita.aibuilder.TerrainGrid.Cell(p.y()==60?"DIRT":"AIR",p.y()==60?"dirt":"air",p.y()==60?com.elitonvita.aibuilder.TerrainGrid.Kind.NATURAL:com.elitonvita.aibuilder.TerrainGrid.Kind.AIR);
                }
                public void check(com.elitonvita.aibuilder.TerrainGrid.Change c) { }
                public void apply(com.elitonvita.aibuilder.TerrainGrid.Change c) { throw new AssertionError("confirmation wrote world directly"); }
            };
            java.util.function.Supplier<com.elitonvita.aibuilder.TerrainWork> preview=()-> {
                var work=new com.elitonvita.aibuilder.TerrainWork(new com.elitonvita.aibuilder.TerrainGrid(new com.elitonvita.aibuilder.TerrainGrid.Bounds(0,60,0,0,62,0)),com.elitonvita.aibuilder.TerrainGrid.Mode.LEVEL,10);
                for(int i=0;i<5000&&work.stage()!=com.elitonvita.aibuilder.TerrainWork.Stage.READY&&!work.terminal();i++) {
                    work.advance(access,10,10,System.currentTimeMillis());
                    if(work.stage()==com.elitonvita.aibuilder.TerrainWork.Stage.PLANNING) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                }
                return work;
            };
            var ready=preview.get();set(f.plugin,"terrainWork",ready);
            f.command("terrain","level","yes","stale");
            check(ready.stage()==com.elitonvita.aibuilder.TerrainWork.Stage.READY,"stale Yes confirmed new preparation");
            f.command("terrain","cancel","stale");
            check(get(f.plugin,"terrainWork")==ready,"stale No cancelled new preparation");
            f.command("terrain","level","yes",token);
            check(ready.stage()==com.elitonvita.aibuilder.TerrainWork.Stage.RECHECK,"current Yes did not confirm preview");
            f.command("terrain","cancel",token);
            check(get(f.plugin,"terrainWork")==null,"current Cancel did not stop the confirmed operation");
            f.command("terrain","cancel");
            f.command("terrain","level");
            String replacement=(String)get(f.plugin,"terrainToken");
            check(!replacement.equals(token),"replacement reused old confirmation token");
            ready=preview.get();set(f.plugin,"terrainWork",ready);
            f.command("terrain","cancel",token);
            check(get(f.plugin,"terrainWork")==ready,"old button cancelled replacement operation");
            f.command("terrain","cancel",replacement);
            check(get(f.plugin,"terrainWork")==null&&get(f.plugin,"terrainToken")==null,"current No did not discard preview/token");
        }
        System.out.println("PASS: "+(checks-before)+" terrain/context plugin integration assertions");
    }

    private static void minecraftReview() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String invalid : List.of("fraction", "unknown", "foreign-replace", "outside", "item", "duplicate")) {
                f.pending(area(0));
                JsonObject a = ((List<JsonObject>) get(f.plugin, "pendingActions")).get(0);
                switch (invalid) {
                    case "duplicate" -> ((List<JsonObject>) get(f.plugin, "pendingActions")).add(a.deepCopy());
                    case "fraction" -> a.addProperty("x", 0.5);
                    case "unknown" -> a.addProperty("type", "command");
                    case "foreign-replace" -> a.addProperty("type", "replace");
                    case "outside" -> a.addProperty("x", 100);
                    case "item" -> a.addProperty("material", "DIAMOND_SWORD");
                }
                f.command("yes");
                check(f.queue().isEmpty() && get(f.plugin, "pendingActions") == null,
                        "confirmation accepted invalid LLM plan: " + invalid);
            }
            var stone = proxy(org.bukkit.block.Block.class, Map.of("getType", org.bukkit.Material.STONE));
            JsonObject a = new JsonObject();
            Class<?>[] types = {org.bukkit.block.Block.class, JsonObject.class};
            check(!(boolean)invoke(f.plugin, "matchesReplacementSource", types, stone, a), "replacement without precondition accepted");
            a.addProperty("expectedMaterial", "DIRT");
            check(!(boolean)invoke(f.plugin, "matchesReplacementSource", types, stone, a), "externally modified block accepted");
            a.addProperty("expectedMaterial", "STONE");
            check((boolean)invoke(f.plugin, "matchesReplacementSource", types, stone, a), "unchanged source rejected");
            var data = proxy(org.bukkit.block.data.BlockData.class, Map.of("getAsString", "minecraft:stone"));
            var oriented = proxy(org.bukkit.block.Block.class, Map.of("getType", org.bukkit.Material.STONE, "getBlockData", data));
            a.addProperty("expectedData", "minecraft:dirt");
            check(!(boolean)invoke(f.plugin, "matchesReplacementSource", types, oriented, a), "source state mismatch accepted");
            a.addProperty("expectedData", "minecraft:stone");
            check((boolean)invoke(f.plugin, "matchesReplacementSource", types, oriented, a), "matching source state rejected");
            var wet = proxy(org.bukkit.block.data.Waterlogged.class, Map.of("isWaterlogged", true));
            Class<?>[] placement = {org.bukkit.block.Block.class, org.bukkit.block.data.BlockData.class};
            check(((String)invoke(f.plugin, "placementProblem", placement, stone, wet)).contains("agua"), "waterlogged block accepted");
            var air = proxy(org.bukkit.block.Block.class, Map.of("getType", org.bukkit.Material.AIR));
            var unsupported = proxy(org.bukkit.block.Block.class, Map.of("getRelative", air));
            var sand = proxy(org.bukkit.block.data.BlockData.class, Map.of("getMaterial", org.bukkit.Material.SAND, "isSupported", true));
            check(((String)invoke(f.plugin, "placementProblem", placement, unsupported, sand)).contains("gravidade"), "floating sand accepted");
            JsonObject unloaded = new JsonObject(); unloaded.addProperty("x", 0); unloaded.addProperty("y", 64); unloaded.addProperty("z", 0);
            World world = f.plugin.getServer().getWorld("world");
            f.worldValues.put("getWorldBorder", proxy(org.bukkit.WorldBorder.class, Map.of("isInside", true)));
            f.worldValues.put("getSpawnLocation", new Location(world, 1000, 64, 1000));
            var target = proxy(org.bukkit.block.Block.class, new HashMap<String,Object>() {{ put("getLocation", new Location(world, 0, 64, 0)); put("getWorld", world); put("isPassable", true); put("isLiquid", false); put("getType", org.bukkit.Material.AIR); }});
            f.worldValues.put("getBlockAt", target);
            set(f.plugin, "activeBuild", f.pending(area(0)));
            JsonObject replace = unloaded.deepCopy(); replace.addProperty("type", "replace"); replace.add("area", area(0));
            Object absentPlugins = f.serverValues.get("getPluginManager");
            f.serverValues.put("getPluginManager", Proxy.newProxyInstance(org.bukkit.plugin.PluginManager.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (o,m,args) -> { throw new IllegalStateException("claim integration unavailable"); }));
            check(((String)invoke(f.plugin, "execute", new Class<?>[]{JsonObject.class}, replace)).contains("claim"),
                    "replacement bypassed unavailable claim protection");
            f.serverValues.put("getPluginManager", absentPlugins);
            f.worldValues.put("getNearbyEntities", List.of(get(f.plugin, "npc")));
            var plain = proxy(org.bukkit.block.data.BlockData.class, Map.of("getMaterial", org.bukkit.Material.STONE, "isSupported", true));
            check(((String)invoke(f.plugin, "placementProblem", placement, target, plain)).startsWith("aguardando:"),
                    "placement did not wait for an entity to move");
            f.worldValues.put("getNearbyEntities", List.of());
            check(invoke(f.plugin, "placementProblem", placement, target, plain) == null, "placement did not resume after entity moved");
            f.worldValues.put("isChunkLoaded", false);
            check(invoke(f.plugin, "blockAt", new Class<?>[]{JsonObject.class}, unloaded) == null, "execution loads absent chunk");
        }
    }

    private static void selectionFlow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.set("language", "PT"); // These message assertions intentionally exercise Portuguese.
            UUID owner = (UUID)get(f.plugin, "ownerId");
            List<String> messages = new ArrayList<>();
            Map<String, Object> playerValues = new HashMap<>(Map.of("getUniqueId", owner, "isOnline", true,
                    "hasPermission", true, "isOp", true, "getWorld", f.plugin.getServer().getWorld("world"),
                    "getLocation", new Location(f.plugin.getServer().getWorld("world"), 0, 64, 0)));
            org.bukkit.entity.Player player = (org.bukkit.entity.Player)Proxy.newProxyInstance(
                    org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class}, (object, method, args) -> {
                        if (method.getName().equals("sendMessage")) { messages.add(String.valueOf(args[0])); return null; }
                        if (playerValues.containsKey(method.getName())) return playerValues.get(method.getName());
                        if (method.getReturnType() == void.class) return null;
                        throw new UnsupportedOperationException(method.toString());
                    });
            Class<?>[] requestTypes = {org.bukkit.entity.Player.class, String.class};
            f.settings.set("zone.enabled", false);
            invoke(f.plugin, "receiveRequest", requestTypes, player, "uma casa azul");
            check("uma casa azul".equals(get(f.plugin,"selectionRequest")), "request lost while asking for area");
            check(f.queue().isEmpty() && !((RequestGuard)get(f.plugin,"requests")).isThinking(), "selection wait started work");
            invoke(f.plugin, "receiveRequest", requestTypes, player, "uma torre");
            check("uma torre".equals(get(f.plugin,"selectionRequest")), "new request did not replace waiting request");
            f.command("cancel");
            check(get(f.plugin,"selectionRequest") == null, "cancel did not discard waiting request");
            invoke(f.plugin, "receiveRequest", requestTypes, player, "uma ponte");
            f.command("stop");
            check(get(f.plugin,"selectionRequest") == null, "stop did not discard waiting request");
            f.settings.set("zone.enabled", true); set(f.plugin,"areaUsed",true);
            check(!(boolean)invoke(f.plugin,"awaitSelection",requestTypes,player,"reusar"), "used area was ignored");
            set(f.plugin,"zonePos1",new Location(f.plugin.getServer().getWorld("world"),0,64,0));
            check((boolean)invoke(f.plugin,"awaitSelection",requestTypes,player,"nova selecao"), "partial selection used previous area");
            f.config.set("parental.allowed-hours", "0-0"); // Observe entry into planning without HTTP/server scheduler.
            World world = f.plugin.getServer().getWorld("world");
            invoke(f.plugin,"saveZone",new Class<?>[]{Location.class,Location.class,org.bukkit.entity.Player.class},
                    new Location(world,0,64,0),new Location(world,3,64,3),player);
            check(get(f.plugin,"selectionRequest") == null && get(f.plugin,"zonePos1") == null, "completed selection did not consume request");
            check(messages.stream().anyMatch(m -> m.contains("continuar seu pedido: nova selecao")), "selection did not announce automatic continuation");
            check(messages.stream().anyMatch(m -> m.contains("estou descansando")), "automatic continuation bypassed or never reached parental gate");
            int messageCount=messages.size();
            invoke(f.plugin,"resumeAfterSelection",new Class<?>[]{org.bukkit.entity.Player.class},player);
            check(messages.size()==messageCount,"selection resumed the same request twice");
            f.settings.set("zone.enabled",false);
            invoke(f.plugin,"awaitSelection",requestTypes,player,"expirado");set(f.plugin,"selectionExpires",0L);
            invoke(f.plugin,"resumeAfterSelection",new Class<?>[]{org.bukkit.entity.Player.class},player);
            check(get(f.plugin,"selectionRequest")==null && messages.getLast().contains("expirou"),"expired request resumed");
            invoke(f.plugin,"awaitSelection",requestTypes,player,"privado");
            playerValues.put("getUniqueId",UUID.randomUUID());
            invoke(f.plugin,"resumeAfterSelection",new Class<?>[]{org.bukkit.entity.Player.class},player);
            check("privado".equals(get(f.plugin,"selectionRequest")),"another player consumed waiting request");
            playerValues.put("getUniqueId",owner);
            // A completed preparation with an invalid output zone must not use the old zone.
            var done = new com.elitonvita.aibuilder.TerrainWork(new com.elitonvita.aibuilder.TerrainGrid(
                    new com.elitonvita.aibuilder.TerrainGrid.Bounds(0,64,0,0,64,0)),com.elitonvita.aibuilder.TerrainGrid.Mode.CLEAR,10);
            field(done.getClass(),"stage").set(done,com.elitonvita.aibuilder.TerrainWork.Stage.DONE);
            set(f.plugin,"terrainWork",done); set(f.plugin,"terrainActor",player); set(f.plugin,"terrainNotice","DONE");
            JsonObject tooLarge=area(0);tooLarge.addProperty("x2",100000);
            set(f.plugin,"terrainArea",tooLarge);
            invoke(f.plugin,"tickTerrain",new Class<?>[0]);
            check("privado".equals(get(f.plugin,"selectionRequest")),"invalid terrain output resumed request against old zone");
            check(get(f.plugin,"terrainWork")==null,"completed terrain kept session busy");
            set(f.plugin,"terrainWork",done);set(f.plugin,"terrainActor",player);set(f.plugin,"terrainNotice","DONE");
            set(f.plugin,"terrainArea",area(0));
            set(f.plugin,"zonePos1",new Location(world,0,64,0));
            invoke(f.plugin,"tickTerrain",new Class<?>[0]);
            check(get(f.plugin,"selectionRequest")==null && get(f.plugin,"zonePos1")==null,"valid prepared zone did not resume request");
            f.settings.set("zone.enabled",false);
            invoke(f.plugin,"awaitSelection",requestTypes,player,"desconectar");
            Object builderBeforeQuit=((Map<?,?>)field(AIBuilderPlugin.class,"builders").get(f.plugin)).values().iterator().next();
            invoke(f.plugin,"onQuit",new Class<?>[]{org.bukkit.event.player.PlayerQuitEvent.class},
                    org.bukkit.event.player.PlayerQuitEvent.class
                            .getConstructor(org.bukkit.entity.Player.class, net.kyori.adventure.text.Component.class)
                            .newInstance(player, net.kyori.adventure.text.Component.empty()));
            check(get(builderBeforeQuit,"selectionRequest")==null,"disconnect retained waiting request");
            check(((Map<?,?>)field(AIBuilderPlugin.class,"builders").get(f.plugin)).isEmpty(),"idle builder not removed when owner quit");
        }
    }

    private static void orientationControls() throws Exception {
        try(Fixture f=new Fixture()) {
            f.command("alfred","orientation","north");
            check("north".equals(invoke(f.session,"entranceDirection",new Class<?>[0])),"named orientation route failed");
            JsonObject saved=JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builder.json"))).getAsJsonObject();
            check(saved.get("entranceDirection").getAsString().equals("north"),"orientation not persisted");
            check(f.plugin.onTabComplete(f.sender,null,"construtor",new String[]{"alfred","orientation","e"}).contains("east"),"orientation completion missing");
            check(f.plugin.onTabComplete(f.sender,null,"construtor",new String[]{"alfred","prebuilt","cubo_moderno","w"}).contains("west"),"one-off facing completion missing");
            f.pending(area(0));f.command("alfred","orientation","south");
            check("north".equals(invoke(f.session,"entranceDirection",new Class<?>[0])),"pending plan orientation changed");
            invoke(f.session,"clearPending",new Class<?>[0]);
            f.command("alfred","orientation","cima");
            check("north".equals(invoke(f.session,"entranceDirection",new Class<?>[0])),"invalid facing accepted");
            var stranger=proxy(org.bukkit.entity.Player.class,Map.of("getUniqueId",UUID.randomUUID(),"hasPermission",true,"isOp",false));
            f.plugin.onCommand(stranger,null,"construtor",new String[]{"alfred","orientation","west"});
            check("north".equals(invoke(f.session,"entranceDirection",new Class<?>[0])),"stranger changed orientation");
            ((Map<?,?>)get(f.plugin,"builders")).clear();
            Object restored=invoke(f.plugin,"registerRecord",new Class<?>[]{JsonObject.class},saved);
            check("north".equals(invoke(restored,"entranceDirection",new Class<?>[0])),"orientation lost on restart");
            // Re-register the original instance for remaining command/save checks.
            ((Map<UUID,Object>)get(f.plugin,"builders")).clear();
            ((Map<UUID,Object>)get(f.plugin,"builders")).put(f.builderId,f.session);
            f.command("alfred","orientation","original");
            check(invoke(f.session,"entranceDirection",new Class<?>[0])==null,"original preference not cleared");
            saved=JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builder.json"))).getAsJsonObject();
            check(!saved.has("entranceDirection"),"cleared preference still persisted");
            JsonObject large=area(0);large.addProperty("x2",47);large.addProperty("z2",47);large.addProperty("y2",263);
            invoke(f.session,"validateArea",new Class<?>[]{JsonObject.class},large);
            check(true,"48x48x200 build envelope rejected");
            Path prebuilts=f.directory.resolve("prebuilts");Files.createDirectories(prebuilts);
            Files.writeString(prebuilts.resolve("tall.json"),OrientationLimitsTest.model(199,1,1));
            f.config.set("construction.max-height",200);
            check(((com.elitonvita.aibuilder.UserPrebuilts.Load)invoke(f.plugin,"reloadPrebuilts",new Class<?>[0])).entries().size()==1,"runtime importer ignored height 200");
            f.config.set("construction.max-height",100);
            check(((com.elitonvita.aibuilder.UserPrebuilts.Load)invoke(f.plugin,"reloadPrebuilts",new Class<?>[0])).entries().isEmpty(),"runtime importer ignored lowered height");
            com.elitonvita.aibuilder.PrebuiltCatalog.setUser(List.of());
            Path bad=f.directory.resolve("not-a-directory-orientation");Files.writeString(bad,"x");
            field(JavaPlugin.class,"dataFolder").set(f.plugin,bad.toFile());
            f.command("alfred","orientation","east");
            check(invoke(f.session,"entranceDirection",new Class<?>[0])==null,"save failure changed orientation");
        }
    }

    private static void configurableEnvelope() throws Exception {
        try(Fixture f=new Fixture()) {
            JsonObject area=area(0);area.addProperty("x2",199);area.addProperty("z2",199);area.addProperty("y2",263);
            invoke(f.session,"validateArea",new Class<?>[]{JsonObject.class},area);
            check(true,"200x200x200 runtime area rejected");
            area.addProperty("x2",200);
            try{invoke(f.session,"validateArea",new Class<?>[]{JsonObject.class},area);throw new AssertionError("width config ignored");}catch(IllegalArgumentException expected){checks++;}
            f.config.set("construction.max-width",220);f.config.set("construction.max-volume",10_000_000L);
            invoke(f.session,"validateArea",new Class<?>[]{JsonObject.class},area);
            check(true,"custom runtime width rejected");
            f.config.set("construction.max-volume",100);
            try{invoke(f.session,"validateArea",new Class<?>[]{JsonObject.class},area);throw new AssertionError("volume config ignored");}catch(IllegalArgumentException expected){checks++;}
            f.config.set("construction.max-volume",8_000_000L);
            Object build=f.pending(area(0));
            check((boolean)invoke(f.session,"saveBuild",new Class<?>[]{build.getClass(),String.class},build,"parada"),"large-file fixture could not save");
            Path saved=f.builderDirectory().resolve("builds/fixture.json");
            Files.writeString(saved,Files.readString(saved)+" ".repeat(8_100_000));
            f.worldValues.put("getBlockAt", proxy(org.bukkit.block.Block.class, new HashMap<String,Object>() {{ put("getType", org.bukkit.Material.AIR); put("isPassable", true); put("isLiquid", false); }}));
            List<String> loadMessages = new ArrayList<>();
            CommandSender diagnostic = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (o,m,a) -> {
                if(m.getName().equals("sendMessage")) loadMessages.add(Arrays.toString(a));
                return m.invoke(f.sender,a);
            });
            check(invoke(f.session,"loadSavedBuild",new Class<?>[]{CommandSender.class,String.class},diagnostic,"fixture")!=null,"large saved build could not resume: " + loadMessages);
            f.config.set("construction.max-file-bytes",8_000_000L);
            check(invoke(f.session,"loadSavedBuild",new Class<?>[]{CommandSender.class,String.class},f.sender,"fixture")==null,"file budget ignored at runtime");
        }
    }

    public static void main(String[] args) throws Exception {
        configurableEnvelope();
        orientationControls();
        catalogStates();
        combatDamage();
        travelControls();
        mannequinConfiguration();
        selectionFlow();
        minecraftReview();
        try (Fixture f = new Fixture()) {
            JsonObject original = area(0);
            Object planned = f.pending(original);
            check(get(f.plugin, "activeBuild") == null && f.queue().isEmpty(), "proposal activated before confirmation");
            f.command("cancel");
            check(get(f.plugin, "activeBuild") == null && get(f.plugin, "pendingBuild") == null && f.queue().isEmpty(), "cancel left an active build");
            planned = f.pending(original);
            f.currentArea(area(100));
            f.command("yes");
            check(get(f.plugin, "activeBuild") == planned, "confirmation did not activate proposed build");
            f.command("stay");
            check(!f.settings.getBoolean("follow-owner") && get(f.plugin, "activeBuild") == planned && f.queue().size() == 1,
                    "Stay paused/discarded confirmed construction");
            check(!JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builder.json"))).getAsJsonObject().get("follow").getAsBoolean(), "Stay mode was not persisted");
            f.command("follow");
            check(f.settings.getBoolean("follow-owner") && get(f.plugin, "activeBuild") == planned && f.queue().size() == 1,
                    "Follow changed confirmed construction");
            check(f.queue().size() == 1 && get(f.plugin, "pendingActions") == null, "confirmation did not transfer/clear pending actions");
            JsonObject queued = (JsonObject) f.queue().peek();
            check(queued.getAsJsonObject("area").equals(original), "selection change moved confirmed plan");
            check(!(boolean)get(f.plugin, "areaUsed"), "old plan consumed fresh selection");
            RequestGuard guard = (RequestGuard)get(f.plugin, "requests");
            long token = guard.begin(); f.command("stop");
            check(!guard.accepts(token) && !guard.isThinking(), "stop left stale response valid");
            check(f.queue().isEmpty() && get(f.plugin, "activeBuild") == null, "stop left queue/build active");
            JsonObject saved = JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builds/fixture.json"))).getAsJsonObject();
            check(saved.get("status").getAsString().equals("parada") && saved.getAsJsonObject("area").equals(original), "pause did not persist original area/status");
            check(saved.getAsJsonArray("blocks").size() == 1 && saved.getAsJsonArray("blocks").get(0).getAsJsonObject().get("m").getAsString().equals("STONE"), "pause lost build geometry");
            f.currentArea(original); f.pending(original); f.command("yes");
            check((boolean)get(f.plugin, "areaUsed"), "confirmation did not consume matching area");
            f.command("stop"); f.pending(original); set(f.plugin, "pendingExpires", 0L); f.command("yes");
            check(get(f.plugin, "activeBuild") == null && get(f.plugin, "pendingBuild") == null && f.queue().isEmpty(), "expired confirmation activated build");
            f.pending(original);
            long id = (long)get(f.plugin, "pendingId"); f.command("yes", Long.toString(id - 1));
            check(f.queue().isEmpty() && get(f.plugin, "pendingActions") != null, "stale button confirmed replacement plan");
            f.command("cancel", (String)invoke(f.plugin, "confirmationToken", new Class<?>[0]));
            check(get(f.plugin, "pendingActions") == null, "current cancel button did not clear plan");
            Class<?>[] volumeTypes = {long.class, long.class, long.class, long.class};
            check((long)invoke(f.plugin, "boundedVolume", volumeTypes, 4L, 8L, 16L, 512L) == 512L, "exact volume limit rejected");
            check((long)invoke(f.plugin, "boundedVolume", volumeTypes, 4L, 8L, 17L, 512L) == -1L, "over-limit volume accepted");
            check((long)invoke(f.plugin, "boundedVolume", volumeTypes, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 200_000L) == -1L, "overflowing volume accepted");
            check((long)invoke(f.plugin, "boundedVolume", volumeTypes, -1L, 3L, 3L, 200_000L) == -1L, "negative dimension accepted");
            check((long)invoke(f.plugin, "boundedVolume", volumeTypes, 3L, 0L, 3L, 200_000L) == -1L, "zero dimension accepted");
            Class<?>[] lookupTypes = {org.bukkit.block.Block.class};
            Class<?>[] selectedLookupTypes = {org.bukkit.block.Block.class, JsonObject.class};
            set(f.plugin, "coreProtect", null);
            org.bukkit.block.Block selectedBlock = f.blockAt(2, 64, 2);
            org.bukkit.block.Block outsideBlock = f.blockAt(100, 64, 2);
            JsonObject selectedArea = area(0);
            check(!(boolean)invoke(f.plugin, "playerModified", selectedLookupTypes, selectedBlock, selectedArea),
                    "standalone selection did not allow natural terrain by default");
            f.config.set("allow-break-without-coreprotect-in-selection", false);
            check((boolean)invoke(f.plugin, "playerModified", selectedLookupTypes, selectedBlock, selectedArea),
                    "explicit conservative setting was ignored");
            f.config.set("allow-break-without-coreprotect-in-selection", true);
            check(!(boolean)invoke(f.plugin, "playerModified", selectedLookupTypes, selectedBlock, selectedArea),
                    "configured selected area did not allow fallback");
            check((boolean)invoke(f.plugin, "playerModified", selectedLookupTypes, outsideBlock, selectedArea),
                    "CoreProtect fallback escaped the selected area");
            check((boolean)invoke(f.plugin, "playerModified", selectedLookupTypes, selectedBlock, null),
                    "CoreProtect fallback accepted an unselected action");
            set(f.plugin, "coreProtect", new HistoryLookup(null, false));
            check((boolean)invoke(f.plugin, "playerModified", lookupTypes, (Object)null), "null history failed open");
            set(f.plugin, "coreProtect", new HistoryLookup("unexpected", false));
            check((boolean)invoke(f.plugin, "playerModified", lookupTypes, (Object)null), "unexpected history failed open");
            set(f.plugin, "coreProtect", new HistoryLookup(List.of(), false));
            check(!(boolean)invoke(f.plugin, "playerModified", lookupTypes, (Object)null), "empty verified history treated as modified");
            set(f.plugin, "coreProtect", new HistoryLookup(List.of("record"), false));
            check((boolean)invoke(f.plugin, "playerModified", lookupTypes, (Object)null), "recorded history ignored");
            set(f.plugin, "coreProtect", new HistoryLookup(null, true));
            check((boolean)invoke(f.plugin, "playerModified", lookupTypes, (Object)null), "history failure failed open");
            f.pending(original);
            Path regularFile = f.directory.resolve("not-a-directory"); Files.writeString(regularFile, "fixture");
            field(JavaPlugin.class, "dataFolder").set(f.plugin, regularFile.toFile());
            f.command("yes");
            check(f.queue().isEmpty() && get(f.plugin, "activeBuild") == null, "save failure started construction");
            check(get(f.plugin, "pendingActions") != null && get(f.plugin, "pendingBuild") != null, "save failure discarded retryable plan");
            field(JavaPlugin.class, "dataFolder").set(f.plugin, f.directory.toFile());
            // registro do construtor: salvar, ler de volta e apagar (sobrevive ao reinicio)
            UUID ownerUuid = UUID.randomUUID(), npcUuid = UUID.randomUUID();
            World world = f.plugin.getServer().getWorld("world");
            Location spot = new Location(world, 10.5, 65.0, -3.25);
            set(f.plugin, "ownerId", ownerUuid);
            set(f.plugin, "npc", proxy(Mannequin.class, Map.of("isDead", false, "getWorld", world,
                    "getLocation", spot, "getUniqueId", npcUuid)));
            invoke(f.plugin, "saveBuilderRegistry", new Class<?>[0]);
            Path registry = f.builderDirectory().resolve("builder.json");
            check(Files.exists(registry), "builder registry was not written");
            JsonObject savedRegistry = JsonParser.parseString(Files.readString(registry)).getAsJsonObject();
            check(savedRegistry.get("owner").getAsString().equals(ownerUuid.toString()), "registry lost owner");
            check(savedRegistry.get("npc").getAsString().equals(npcUuid.toString()), "registry lost npc uuid");
            check(savedRegistry.get("world").getAsString().equals("world") && savedRegistry.get("x").getAsDouble() == 10.5
                    && savedRegistry.get("z").getAsDouble() == -3.25, "registry lost position");
            set(f.plugin, "npc", null); set(f.plugin, "ownerId", null);
            invoke(f.plugin, "saveBuilderRegistry", new Class<?>[0]);
            check(Files.exists(registry), "registry without an active builder must not be overwritten");
            invoke(f.plugin, "deleteBuilderRegistry", new Class<?>[0]);
            check(!Files.exists(registry), "delete did not remove the builder registry");
        }
        // obra aberta: incremento troca bloco da propria obra e acrescenta blocos, sem mexer na obra original
        Class<?> buildType = Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");
        Constructor<?> buildCtor = buildType.getDeclaredConstructor(String.class, String.class, List.class, JsonObject.class);
        buildCtor.setAccessible(true);
        Object base = buildCtor.newInstance("casa", "world", List.of(
                new BlueprintCompiler.Block(0, 64, 0, "OAK_PLANKS", 0), new BlueprintCompiler.Block(1, 64, 0, "OAK_PLANKS", 0)), area(0));
        Method hasBlock = buildType.getDeclaredMethod("hasBlock", int.class, int.class, int.class); hasBlock.setAccessible(true);
        Method withIncrement = buildType.getDeclaredMethod("withIncrement", List.class, String.class); withIncrement.setAccessible(true);
        JsonObject window = new JsonObject(); window.addProperty("type", "replace"); window.addProperty("material", "GLASS_PANE");
        window.addProperty("x", 1); window.addProperty("y", 64); window.addProperty("z", 0);
        JsonObject torch = new JsonObject(); torch.addProperty("type", "place"); torch.addProperty("material", "TORCH");
        torch.addProperty("x", 2); torch.addProperty("y", 65); torch.addProperty("z", 0);
        Object next = withIncrement.invoke(base, List.of(window, torch), "janela e tocha");
        Field blocksField = buildType.getDeclaredField("blocks"); blocksField.setAccessible(true);
        Field stepsField = buildType.getDeclaredField("steps"); stepsField.setAccessible(true);
        check(((List<?>) blocksField.get(next)).size() == 3, "increment did not add the torch while replacing the window");
        check((Boolean) hasBlock.invoke(next, 2, 65, 0), "increment torch missing from the open build");
        check(((List<?>) blocksField.get(base)).size() == 2 && !(Boolean) hasBlock.invoke(base, 2, 65, 0), "increment mutated the original build");
        check(((List<?>) stepsField.get(next)).size() == 1, "increment step not recorded");
        System.out.println("PASS: " + checks + " plugin lifecycle integration assertions");
    }

    private static void mannequinConfiguration() throws Exception {
        try (Fixture f = new Fixture()) {
            field(JavaPlugin.class, "pluginMeta").set(f.plugin,
                    new org.bukkit.plugin.PluginDescriptionFile("AIBuilder", "test", AIBuilderPlugin.class.getName()));
            // Model the item registry; configureNpc itself is the production method.
            class TestItem extends org.bukkit.inventory.ItemStack { TestItem() { super(); } }
            var axe = org.bukkit.Material.NETHERITE_AXE;
            Object previousItemType = field(org.bukkit.Material.class, "itemType").get(axe);
            var itemType = proxy(org.bukkit.inventory.ItemType.class, Map.of("createItemStack", new TestItem()));
            field(org.bukkit.Material.class, "itemType").set(axe,
                    (java.util.function.Supplier<org.bukkit.inventory.ItemType>) () -> itemType);
            Map<String, Object> calls = new HashMap<>();
            var equipment = (org.bukkit.inventory.EntityEquipment) Proxy.newProxyInstance(
                    getClassLoader(), new Class<?>[]{org.bukkit.inventory.EntityEquipment.class}, (object, method, args) -> {
                        if (method.getName().contains("DropChance"))
                            throw new IllegalArgumentException("Cannot set drop chance for non-Mob entity");
                        if (method.getName().equals("setItemInMainHand")) { calls.put("tool", args[0]); return null; }
                        throw new UnsupportedOperationException(method.toString());
                    });
            var pdc = (org.bukkit.persistence.PersistentDataContainer) Proxy.newProxyInstance(
                    getClassLoader(), new Class<?>[]{org.bukkit.persistence.PersistentDataContainer.class}, (object, method, args) -> {
                        if (method.getName().equals("set")) { calls.put("tag", args[2]); return null; }
                        throw new UnsupportedOperationException(method.toString());
                    });
            var mannequin = (Mannequin) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Mannequin.class}, (object, method, args) -> {
                if (method.getName().equals("getEquipment")) return equipment;
                if (method.getName().equals("getPersistentDataContainer")) return pdc;
                if (method.getReturnType() == void.class) { calls.put(method.getName(), args[0]); return null; }
                throw new UnsupportedOperationException(method.toString());
            });
            try {
                invoke(f.session, "configureNpc", new Class<?>[]{Mannequin.class}, mannequin);
                check(calls.get("tool") != null, "mannequin lost its tool");
                check(f.builderId.toString().equals(calls.get("tag")), "mannequin lost builder identity");
                check(Boolean.TRUE.equals(calls.get("setInvulnerable")) && Boolean.TRUE.equals(calls.get("setPersistent")),
                        "mannequin lost protection/persistence");
            } finally { field(org.bukkit.Material.class, "itemType").set(axe, previousItemType); }
        }
    }

    private static ClassLoader getClassLoader() { return PluginLifecycleTest.class.getClassLoader(); }

    @SuppressWarnings("unchecked")
    private static void catalogStates() throws Exception {
        try(Fixture f=new Fixture()) {
            var block=new BlueprintCompiler.Block(1,64,1,"GLASS_PANE",0,"minecraft:glass_pane[north=true,south=true,waterlogged=false]");
            List<JsonObject> actions=(List<JsonObject>)invoke(f.session,"blocksToActions",new Class<?>[]{List.class},List.of(block));
            JsonObject action=actions.getFirst();
            check(action.get("data").getAsString().equals(block.data()),"state lost between compiler and actions");
            var lower=new BlueprintCompiler.Block(3,64,1,"OAK_DOOR",1,"minecraft:oak_door[half=lower,facing=south]");
            var upper=new BlueprintCompiler.Block(3,65,1,"OAK_DOOR",0,"minecraft:oak_door[half=upper,facing=south]");
            List<JsonObject> ordered=(List<JsonObject>)invoke(f.session,"blocksToActions",new Class<?>[]{List.class},List.of(upper,block,lower));
            check(ordered.get(0).get("material").getAsString().equals("GLASS_PANE")
                    &&ordered.get(1).get("y").getAsInt()==64&&ordered.get(2).get("y").getAsInt()==65,"door halves not installed last and consecutively");
            check((boolean)invoke(f.session,"placeable",new Class<?>[]{org.bukkit.Material.class,JsonObject.class},org.bukkit.Material.GLASS_PANE,action),"explicit pane rejected");
            action.addProperty("creative",true);
            check(!(boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},action),"creative flag granted catalog privileges");
            Class<?> type=Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");
            var ctor=type.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);ctor.setAccessible(true);
            Object build=ctor.newInstance("casa_campo","world",List.of(block),area(0));set(f.session,"activeBuild",build);
            check(!(boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},action),"LLM blueprint name granted catalog privileges");
            field(type,"catalogId").set(build,"casa_campo");
            check((boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},action),"server-assigned catalog action denied");
            action.addProperty("data","minecraft:glass_pane[east=true]");
            check(!(boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},action),"altered state got catalog privileges");
            action.addProperty("data",block.data());action.addProperty("x",2);
            check(!(boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},action),"unplanned position got catalog privileges");
            invoke(f.session,"saveBuild",new Class<?>[]{type,String.class},build,"parada");
            JsonObject saved=JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builds/casa_campo.json"))).getAsJsonObject();
            check(saved.get("catalogId").getAsString().equals("casa_campo")&&saved.getAsJsonArray("blocks").get(0).getAsJsonObject().get("data").getAsString().equals(block.data()),"save lost catalog/state");
            JsonObject increment=new JsonObject();increment.addProperty("type","place");increment.addProperty("material","STONE");increment.addProperty("x",2);increment.addProperty("y",64);increment.addProperty("z",2);
            Object next=invoke(build,"withIncrement",new Class<?>[]{List.class,String.class},List.of(increment),"extra");
            check("casa_campo".equals(get(next,"catalogId")),"increment lost catalog lineage");
            set(f.session,"activeBuild",next);
            increment.addProperty("type","place_project");
            check(!(boolean)invoke(f.session,"isCatalogPlacement",new Class<?>[]{JsonObject.class},increment),"resumed AI increment received free catalog authority");
            check(f.plugin.onTabComplete(f.sender,null,"construtor",new String[]{"pre"}).contains("prebuilt"),"catalog route completion missing");
        }
    }

    @SuppressWarnings("unchecked")
    private static void combatDamage() throws Exception {
        try (Fixture f = new Fixture()) {
            World world = f.plugin.getServer().getWorld("world");
            UUID ownerId = (UUID)get(f.session,"ownerId");
            var owner = proxy(org.bukkit.entity.Player.class,Map.of("getUniqueId",ownerId,"getLocation",new Location(world,0,64,0),
                    "getWorld",world,"getNearbyEntities",List.of()));
            f.serverValues.put("getPlayer",owner);
            List<org.bukkit.entity.Entity> nearby = new ArrayList<>();
            Map<String,Object> npcValues = new HashMap<>(Map.of("isDead",false,"getLocation",new Location(world,0,64,0),
                    "getWorld",world,"getNearbyEntities",nearby,"hasLineOfSight",true,"getVelocity",new org.bukkit.util.Vector()));
            Mannequin npc = (Mannequin)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{Mannequin.class},(object,method,args)-> {
                if (method.getName().equals("attack")) throw new AssertionError("native mannequin attack used instead of explicit damage");
                if (npcValues.containsKey(method.getName())) return npcValues.get(method.getName());
                if (method.getReturnType()==void.class) return null;
                throw new UnsupportedOperationException(method.toString());
            });
            set(f.session,"npc",npc);
            List<Double> damage = new ArrayList<>(); List<Object> sources = new ArrayList<>();
            var enemy=(org.bukkit.entity.Enemy)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{org.bukkit.entity.Enemy.class},(object,method,args)-> {
                return switch(method.getName()) {
                    case "isDead" -> false;
                    case "getLocation" -> new Location(world,2,64,0);
                    case "damage" -> { damage.add((double)args[0]); sources.add(args[1]); yield null; }
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            });
            nearby.add(enemy);
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.equals(List.of(1_000_000.0)),"default strike did not apply maximum explicit damage");
            check(sources.getFirst()==npc,"damage lost builder attribution");
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.size()==1,"strike ignored cooldown");
            for(int tick=0;tick<19;tick++) invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.size()==2,"strike did not resume after 20 ticks");
            f.config.set("combat.damage",42); set(f.session,"strikeCooldown",0);
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.getLast()==42.0,"configured damage ignored");
            for(double invalid : new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,Double.MAX_VALUE}) {
                f.config.set("combat.damage",invalid);
                check((double)invoke(f.session,"strikeDamage",new Class<?>[0])==1_000_000.0,"invalid/overflow damage not bounded");
            }
            int hits=damage.size(); set(f.session,"strikeCooldown",0);
            f.config.set("combat.enabled",false);
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.size()==hits,"disabled combat dealt damage");
            f.config.set("combat.enabled",true); npcValues.put("hasLineOfSight",false);
            ((Deque<JsonObject>)get(f.session,"queue")).add(new JsonObject());
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.size()==hits,"strike crossed a wall");
            npcValues.put("hasLineOfSight",true); nearby.clear();
            nearby.add(owner);
            nearby.add(proxy(org.bukkit.entity.Animals.class,Map.of("isDead",false,"getLocation",new Location(world,1,64,0))));
            invoke(f.session,"combatStep",new Class<?>[0]);
            check(damage.size()==hits && invoke(f.session,"hostileNear",new Class<?>[0])==null,"combat selected player/animal");
            nearby.clear(); nearby.add(enemy); f.settings.set("recall-hold",true);
            invoke(f.session,"followOwner",new Class<?>[0]);
            check(damage.size()==hits,"recalled builder dealt damage");
        }
    }

    @SuppressWarnings("unchecked")
    private static void travelControls() throws Exception {
        try (Fixture f = new Fixture()) {
            UUID ownerId = (UUID)get(f.session, "ownerId");
            World world = f.plugin.getServer().getWorld("world");
            Location[] position = {new Location(world, 1, 64, 1)};
            boolean[] accepted = {true};
            int[] teleports = {0};
            List<org.bukkit.entity.Entity> monsters = new ArrayList<>();
            Mannequin npc = (Mannequin) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Mannequin.class}, (object, method, args) -> {
                return switch (method.getName()) {
                    case "isDead" -> false;
                    case "getUniqueId" -> f.builderId;
                    case "getLocation" -> position[0].clone();
                    case "getWorld" -> world;
                    case "getNearbyEntities" -> monsters;
                    case "getVelocity" -> new org.bukkit.util.Vector();
                    case "teleport" -> { teleports[0]++; if (accepted[0]) position[0] = ((Location)args[0]).clone(); yield accepted[0]; }
                    default -> { if (method.getReturnType() == void.class) yield null; throw new UnsupportedOperationException(method.toString()); }
                };
            });
            set(f.session, "npc", npc);
            var owner = proxy(org.bukkit.entity.Player.class, Map.of("getUniqueId", ownerId, "hasPermission", true, "isOp", false,
                    "getLocation", new Location(world, 0, 64, 0), "getWorld", world, "getNearbyEntities", List.of()));
            f.serverValues.put("getPlayer", owner);
            f.plugin.onCommand(owner, null, "construtor", new String[]{"alfred", "distance", "8"});
            check((int)invoke(f.session,"travelRadius",new Class<?>[0]) == 8, "named radius command not routed");
            var farEnemy = proxy(org.bukkit.entity.Enemy.class,Map.of("isDead",false,"getLocation",new Location(world,9,64,0)));
            monsters.add(farEnemy);
            check(invoke(f.session,"hostileNear",new Class<?>[0])==null,"combat acquired enemy outside owner limit");
            var nearEnemy = proxy(org.bukkit.entity.Enemy.class,Map.of("isDead",false,"getLocation",new Location(world,5,64,0)));
            monsters.add(nearEnemy);
            check(invoke(f.session,"hostileNear",new Class<?>[0])==nearEnemy,"combat lost valid nearby enemy");
            for (String invalid : List.of("3", "65", "NaN", "8.5", "2147483648")) {
                f.plugin.onCommand(owner,null,"construtor",new String[]{"distance",invalid});
                check((int)invoke(f.session,"travelRadius",new Class<?>[0])==8,"invalid radius changed state");
            }
            var stranger = proxy(org.bukkit.entity.Player.class, Map.of("getUniqueId",UUID.randomUUID(),"hasPermission",true,"isOp",false));
            f.plugin.onCommand(stranger,null,"construtor",new String[]{"alfred","distance","64"});
            f.plugin.onCommand(stranger,null,"construtor",new String[]{"alfred","call"});
            check((int)invoke(f.session,"travelRadius",new Class<?>[0])==8 && teleports[0]==0,"stranger controlled travel");
            Object work = f.pending(area(0)); set(f.session,"activeBuild",work);
            Deque<JsonObject> queue = (Deque<JsonObject>)get(f.session,"queue");
            queue.add(new JsonObject());
            accepted[0] = false;
            invoke(f.session,"finishRecall",new Class<?>[]{org.bukkit.entity.Player.class,Location.class}, owner, new Location(world,2,64,2));
            check(!f.settings.getBoolean("recall-hold") && queue.size()==1,"cancelled teleport changed work/hold");
            accepted[0] = true;
            invoke(f.session,"finishRecall",new Class<?>[]{org.bukkit.entity.Player.class,Location.class}, owner, new Location(world,2,64,2));
            check(f.settings.getBoolean("recall-hold") && get(f.session,"activeBuild")==work && queue.size()==1
                    && get(f.session,"pendingActions")!=null,"recall discarded work or plan");
            invoke(f.session,"processQueue",new Class<?>[0]);
            invoke(f.session,"followOwner",new Class<?>[0]);
            check(queue.size()==1 && teleports[0]==2,"held builder moved or processed work");
            JsonObject saved = JsonParser.parseString(Files.readString(f.builderDirectory().resolve("builder.json"))).getAsJsonObject();
            check(saved.get("recallHold").getAsBoolean() && saved.get("travelRadius").getAsInt()==8
                    && saved.get("x").getAsInt()==2,"recall/radius/position not persisted");
            ((Map<?,?>)get(f.plugin,"builders")).clear();
            invoke(f.plugin,"loadBuilders",new Class<?>[0]);
            Object restored = invoke(f.plugin,"named",new Class<?>[]{String.class},"alfred");
            check((boolean)invoke(restored,"recalled",new Class<?>[0]) && (int)invoke(restored,"travelRadius",new Class<?>[0])==8,
                    "restart lost recall hold or radius");
            // Resume the original live instance and ensure only its hold is released.
            ((Map<UUID,Object>)get(f.plugin,"builders")).put(f.builderId,f.session);
            f.plugin.onCommand(owner,null,"construtor",new String[]{"alfred","follow"});
            check(!f.settings.getBoolean("recall-hold") && queue.size()==1,"follow discarded saved queue");
            position[0] = new Location(world,20,64,0);
            invoke(f.session,"processQueue",new Class<?>[0]);
            check(queue.isEmpty(),"owner travel radius still blocks processing confirmed work");
            // A dequeued move goal is still part of the confirmed build. The owner leash must not stop it.
            f.config.set("combat.enabled",false);
            f.worldValues.put("getWorldBorder",proxy(org.bukkit.WorldBorder.class,Map.of("isInside",true)));
            f.worldValues.put("getPlayers",List.of());
            var air=proxy(org.bukkit.block.Block.class,Map.of("isPassable",true,"isLiquid",false,"getType",org.bukkit.Material.AIR));
            f.worldValues.put("getBlockAt",air);
            var activeWalker=(NpcWalker)get(f.session,"walker");
            activeWalker.requestWork(npc,position[0],.65,.2,l->true);
            invoke(f.session,"followOwner",new Class<?>[0]);
            check(activeWalker.state()==NpcWalker.State.ARRIVED,
                    "confirmed move route was stopped by the distant owner's leash");
            queue.add(new JsonObject()); // Separate captured-world guard from the completed radius check.
            position[0] = new Location(world,2,64,2);
            JsonObject otherWorld = area(0); otherWorld.addProperty("world","other-world");
            queue.peek().add("area",otherWorld);
            invoke(f.session,"processQueue",new Class<?>[0]);
            check(queue.size()==1,"cross-world recall consumed captured action");
            check(f.plugin.onTabComplete(owner,null,"construtor",new String[]{"call","a"}).equals(List.of("all")),"recall-all completion missing");
            check(f.plugin.onTabComplete(owner,null,"construtor",new String[]{"alfred","distance","1"}).contains("16"),"radius completion missing");
            // Everyone else's builder remains untouched by the all-owned route.
            var registry = (Map<UUID,Object>)get(f.plugin,"builders");
            var ctor = f.session.getClass().getDeclaredConstructor(AIBuilderPlugin.class,UUID.class,String.class,UUID.class); ctor.setAccessible(true);
            UUID otherId=UUID.randomUUID(); Object other=ctor.newInstance(f.plugin,otherId,"Bruce",UUID.randomUUID());
            registry.put(otherId,other);
            set(f.session,"npc",null);
            f.plugin.onCommand(owner,null,"construtor",new String[]{"call","all"});
            check(!(boolean)invoke(other,"recalled",new Class<?>[0]) && registry.size()==2,"recall-all affected foreign builder");
        }
    }
}
