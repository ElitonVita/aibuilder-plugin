import com.elitonvita.aibuilder.*;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** Optional integration and captured catalog-cost regressions, without third-party plugin jars. */
public final class StandalonePolicyTest {
    static int checks;
    static void check(boolean ok, String text) { checks++; if (!ok) throw new AssertionError(text); }
    public static final class FakeClaim {
        public String denial;
        public String allowAccess(Player player) { return denial; }
    }
    public static final class FakeStore {
        public FakeClaim claim;
        public boolean broken;
        public FakeClaim getClaimAt(Location location, boolean ignoreHeight, FakeClaim cached) {
            if (broken) throw new IllegalStateException("broken claims");
            return claim;
        }
    }
    public static final class FakeProtection extends JavaPlugin { public FakeStore dataStore; }
    static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> bridge = Class.forName("com.elitonvita.aibuilder.ClaimProtection");
        var m = bridge.getDeclaredMethod(method, types); m.setAccessible(true); return m.invoke(null, args);
    }
    static boolean claimed(PluginManager manager) throws Exception {
        return (boolean) call("claimed", new Class<?>[]{PluginManager.class, Location.class}, manager, null);
    }
    static boolean access(PluginManager manager) throws Exception {
        return (boolean) call("canAccess", new Class<?>[]{PluginManager.class, Location.class, Player.class}, manager, null, null);
    }
    public static void main(String[] args) throws Exception {
        Plugin[] installed = {null};
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                (o,m,a) -> { if(m.getName().equals("getPlugin")) return installed[0]; throw new UnsupportedOperationException(m.toString()); });
        check(!claimed(manager) && access(manager), "standalone requires claim plugin");
        var gp = (FakeProtection) CatalogGardenRuntimeTest.allocate(FakeProtection.class);
        gp.dataStore = new FakeStore();
        var enabled = JavaPlugin.class.getDeclaredField("isEnabled"); enabled.setAccessible(true); enabled.set(gp, true);
        installed[0] = gp;
        check(!claimed(manager) && access(manager), "unclaimed location denied");
        gp.dataStore.claim = new FakeClaim();
        check(claimed(manager), "installed claim ignored");
        check(access(manager), "trusted access denied");
        gp.dataStore.claim.denial = "not allowed";
        check(!access(manager), "claim access denial ignored");
        gp.dataStore.broken = true;
        check(claimed(manager) && !access(manager), "broken installed claims fail open");
        enabled.set(gp, false);
        check(claimed(manager) && !access(manager), "disabled integration fails open");

        var fixtureType = Class.forName("PluginLifecycleTest$Fixture");
        var ctor = fixtureType.getDeclaredConstructor(); ctor.setAccessible(true);
        try (AutoCloseable fixture = (AutoCloseable) ctor.newInstance()) {
            Object session = CatalogGardenRuntimeTest.get(fixture, "session");
            var area = CatalogGardenRuntimeTest.area();
            var buildType = Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");
            var buildCtor = buildType.getDeclaredConstructor(String.class, String.class, List.class, JsonObject.class); buildCtor.setAccessible(true);
            Object build = buildCtor.newInstance("casa_campo", "world", List.of(new BlueprintCompiler.Block(1,64,1,"STONE",0)), area);
            CatalogGardenRuntimeTest.set(build, "catalogId", "casa_campo");
            CatalogGardenRuntimeTest.set(build,"catalogPlacements",new HashSet<>(Set.of("1,64,1")));
            CatalogGardenRuntimeTest.set(build, "catalogFreeMaterials", false);
            Object next = CatalogGardenRuntimeTest.invoke(build, "withIncrement", new Class<?>[]{List.class, String.class}, List.of(), "extra");
            check(!(boolean)CatalogGardenRuntimeTest.get(next,"catalogFreeMaterials"), "increment lost captured cost");
            CatalogGardenRuntimeTest.set(session, "pendingBuild", build);
            JsonObject action = CatalogGardenRuntimeTest.placement(1,64,1,"STONE",null); action.addProperty("type","place_project");
            String charged = (String)CatalogGardenRuntimeTest.invoke(session,"materialsLine",new Class<?>[]{List.class},List.of(action));
            check(charged.contains("STONE") && !charged.contains("materiais livres"), "charged catalog advertised free");
            CatalogGardenRuntimeTest.set(build,"catalogFreeMaterials",true);
            String free = (String)CatalogGardenRuntimeTest.invoke(session,"materialsLine",new Class<?>[]{List.class},List.of(action));
            check(free.contains("materiais livres"), "free catalog summary changed");
            Object plugin = CatalogGardenRuntimeTest.get(session,"plugin");
            var config = ((JavaPlugin)plugin).getConfig(); config.set("creative-mode",true);
            check(!(boolean)CatalogGardenRuntimeTest.invoke(session,"usesOwnerInventory",new Class<?>[0]), "creative mode began consuming items");
            config.set("creative-mode",false); config.set("use-owner-inventory",true);
            check((boolean)CatalogGardenRuntimeTest.invoke(session,"usesOwnerInventory",new Class<?>[0]), "survival inventory policy ignored");
            CatalogGardenRuntimeTest.set(build,"catalogFreeMaterials",false);
            check((boolean)CatalogGardenRuntimeTest.invoke(session,"saveBuild",new Class<?>[]{buildType,String.class},build,"parada"), "catalog cost did not save");
            var dir = (java.nio.file.Path)CatalogGardenRuntimeTest.get(fixture,"directory");
            var id = CatalogGardenRuntimeTest.get(fixture,"builderId");
            var path = dir.resolve("builders").resolve(id.toString()).resolve("builds/casa_campo.json");
            var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            check(!saved.get("catalogFreeMaterials").getAsBoolean(), "persisted cost policy wrong");
            var worldValues = (Map<String,Object>)CatalogGardenRuntimeTest.get(fixture,"worldValues");
            var air = CatalogGardenRuntimeTest.proxy(org.bukkit.block.Block.class, (o,m,a)-> {
                if(m.getName().equals("getType")) return org.bukkit.Material.AIR;
                throw new UnsupportedOperationException(m.toString());
            });
            worldValues.put("getBlockAt",air);
            config.set("prebuilt.free-materials",true);
            List<String> errors = new ArrayList<>();
            var sender = (org.bukkit.command.CommandSender)Proxy.newProxyInstance(org.bukkit.command.CommandSender.class.getClassLoader(),new Class<?>[]{org.bukkit.command.CommandSender.class},(o,m,a)-> {
                if(m.getName().equals("sendMessage")) { errors.add(Arrays.toString(a)); return null; }
                return m.invoke(CatalogGardenRuntimeTest.get(fixture,"sender"),a);
            });
            Object resumed = CatalogGardenRuntimeTest.invoke(session,"loadSavedBuild",new Class<?>[]{org.bukkit.command.CommandSender.class,String.class},sender,"casa_campo");
            check(resumed!=null && !(boolean)CatalogGardenRuntimeTest.get(resumed,"catalogFreeMaterials"), "resume changed captured catalog cost: " + errors);
            check(Set.of("1,64,1").equals(CatalogGardenRuntimeTest.get(resumed,"catalogPlacements")), "resume lost catalog authority positions");
            saved.remove("catalogFreeMaterials"); java.nio.file.Files.writeString(path,saved.toString());
            resumed = CatalogGardenRuntimeTest.invoke(session,"loadSavedBuild",new Class<?>[]{org.bukkit.command.CommandSender.class,String.class},sender,"casa_campo");
            check(resumed!=null && (boolean)CatalogGardenRuntimeTest.get(resumed,"catalogFreeMaterials"), "legacy free build became charged: " + errors);
            var natural = (org.bukkit.block.Block)CatalogGardenRuntimeTest.invoke(fixture,"blockAt",new Class<?>[]{int.class,int.class,int.class},2,64,2);
            check(!(boolean)CatalogGardenRuntimeTest.invoke(session,"playerModified",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},natural,area), "natural selection denied without history");
            var artificial = CatalogGardenRuntimeTest.proxy(org.bukkit.block.Block.class,(o,m,a)->switch(m.getName()) {
                case "getLocation" -> natural.getLocation(); case "getType" -> org.bukkit.Material.OAK_PLANKS;
                default -> throw new UnsupportedOperationException(m.toString());
            });
            check((boolean)CatalogGardenRuntimeTest.invoke(session,"playerModified",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},artificial,area), "standalone history fallback grants artificial removal");
        }
        System.out.println("StandalonePolicyTest: " + checks + " checks passed");
    }
}
