import com.elitonvita.aibuilder.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.PluginManager;
import java.lang.reflect.*;
import java.util.*;

/** Real command routing and native break-event guards, in a serverless fixture. */
public final class DeepTerrainPolicyTest {
    static int checks;
    static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static Object get(Object o,String key)throws Exception { return CatalogGardenRuntimeTest.get(o,key); }
    static void set(Object o,String key,Object value)throws Exception { CatalogGardenRuntimeTest.set(o,key,value); }
    static Object call(Object o,String method,Class<?>[] types,Object...args)throws Exception { return CatalogGardenRuntimeTest.invoke(o,method,types,args); }
    public static final class WandStack extends org.bukkit.inventory.ItemStack {
        org.bukkit.inventory.meta.ItemMeta meta;
        boolean selected = true;
        @Override public boolean hasItemMeta() { return selected; }
        @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { return meta; }
    }
    public static void main(String[]args)throws Exception {
        var type=Class.forName("PluginLifecycleTest$Fixture"); var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
        try(AutoCloseable fixture=(AutoCloseable)ctor.newInstance()) {
            var plugin=(AIBuilderPlugin)get(fixture,"plugin"); Object session=get(fixture,"session");
            var pluginMeta=org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("pluginMeta");pluginMeta.setAccessible(true);
            pluginMeta.set(plugin,CatalogGardenRuntimeTest.proxy(io.papermc.paper.plugin.configuration.PluginMeta.class,(o,m,a)->m.getName().equals("namespace")?"aibuilder":m.getName().equals("getName")?"AIBuilder":null));
            WandStack wand=(WandStack)CatalogGardenRuntimeTest.allocate(WandStack.class);wand.selected=true;
            var pdc=CatalogGardenRuntimeTest.proxy(org.bukkit.persistence.PersistentDataContainer.class,(o,m,a)->m.getName().equals("has"));
            wand.meta=CatalogGardenRuntimeTest.proxy(org.bukkit.inventory.meta.ItemMeta.class,(o,m,a)->m.getName().equals("getPersistentDataContainer")?pdc:null);
            var inventory=CatalogGardenRuntimeTest.proxy(org.bukkit.inventory.PlayerInventory.class,(o,m,a)->m.getName().equals("getItemInMainHand")?wand:null);
            var sessionCtor=session.getClass().getDeclaredConstructor(AIBuilderPlugin.class,UUID.class,String.class,UUID.class);sessionCtor.setAccessible(true);
            UUID secondId=UUID.randomUUID();Object second=sessionCtor.newInstance(plugin,secondId,"Bruce",get(session,"ownerId"));
            ((Map<UUID,Object>)get(plugin,"builders")).put(secondId,second);
            boolean[] op={false},cancelBreak={false},revokeDuringEvent={false},throwDuringEvent={false}; int[] events={0},writes={0};
            List<String> messages=new ArrayList<>();
            Player actor=CatalogGardenRuntimeTest.proxy(Player.class,(o,m,a)->switch(m.getName()) {
                case "getInventory" -> inventory;
                case "getBoundingBox" -> new org.bukkit.util.BoundingBox(0,60,0,1,62,1);
                case "getUniqueId" -> get(session,"ownerId"); case "getName" -> "owner";
                case "isOp" -> op[0]; case "isOnline","hasPermission" -> true;
                case "sendMessage" -> { messages.add(Arrays.toString(a)); yield null; }
                case "showBossBar", "hideBossBar", "sendActionBar" -> null;
                default -> throw new UnsupportedOperationException(m.toString());
            });
            plugin.getConfig().set("terrain.deep-clean.enabled",true);
            plugin.onCommand(actor,null,"builder",new String[]{"alfred","terrain","deep","clean"});
            check(get(session,"terrainWork")==null,"non-OP started Deep");
            op[0]=true;plugin.getConfig().set("terrain.deep-clean.enabled",false);
            plugin.onCommand(actor,null,"builder",new String[]{"alfred","terrain","deep","level"});
            check(get(session,"terrainWork")==null,"disabled Deep started");
            plugin.getConfig().set("terrain.deep-clean.enabled",true);
            plugin.onCommand(actor,null,"builder",new String[]{"alfred","terrain","deep","clean"});
            check(((TerrainWork)get(session,"terrainWork")).mode()==TerrainGrid.Mode.DEEP_CLEAR,"English Deep clean routed to normal mode");
            check(messages.stream().anyMatch(s->s.contains("[DEEP_CLEAR]")),"start message omitted Deep mode");
            call(session,"cancelTerrain",new Class<?>[]{String.class},"test");
            plugin.onCommand(actor,null,"builder",new String[]{"alfred","terrain","deep","level"});
            check(((TerrainWork)get(session,"terrainWork")).mode()==TerrainGrid.Mode.DEEP_LEVEL,"English Deep level routed to normal mode");
            call(session,"cancelTerrain",new Class<?>[]{String.class},"test");
            plugin.onCommand(actor,null,"builder",new String[]{"alfred","terrain","level"});
            check(((TerrainWork)get(session,"terrainWork")).mode()==TerrainGrid.Mode.LEVEL,"OP normal mode silently escalated to Deep");
            call(session,"cancelTerrain",new Class<?>[]{String.class},"test");

            var world=plugin.getServer().getWorld("world");
            var values=(Map<String,Object>)get(fixture,"worldValues");
            values.put("getWorldBorder",CatalogGardenRuntimeTest.proxy(WorldBorder.class,(o,m,a)->m.getName().equals("isInside")?true:null));
            values.put("getSpawnLocation",new Location(world,0,64,0)); // Deep must not apply normal internal spawn rules.
            values.put("getPlayers",List.of());
            Material[] material={Material.STONE};
            Block block=CatalogGardenRuntimeTest.proxy(Block.class,(o,m,a)->switch(m.getName()) {
                case "getLocation" -> new Location(world,1,64,1); case "getWorld" -> world;
                case "getType" -> material[0]; case "isLiquid" -> material[0]==Material.WATER;
                case "getBlockData" -> CatalogGardenRuntimeTest.proxy(org.bukkit.block.data.BlockData.class,(d,dm,da)->dm.getName().equals("getAsString")?"minecraft:"+material[0].name().toLowerCase(Locale.ROOT):null);
                case "setType" -> { writes[0]++; material[0]=(Material)a[0]; yield null; }
                default -> throw new UnsupportedOperationException(m.toString());
            });
            values.put("getBlockAt",block);
            PluginManager manager=CatalogGardenRuntimeTest.proxy(PluginManager.class,(o,m,a)-> {
                if(m.getName().equals("callEvent")) {
                    check(a[0] instanceof BlockBreakEvent,"Deep removal did not consult native break event"); events[0]++;
                    var event=(BlockBreakEvent)a[0];
                    plugin.onWandBreak(event); // Real listener chain, including the second builder.
                    var nested=new BlockBreakEvent(event.getBlock(),actor);plugin.onWandBreak(nested);
                    check(nested.isCancelled()==wand.selected,"event exemption leaked to a different manual event");
                    if(cancelBreak[0]) event.setCancelled(true);
                    if(throwDuringEvent[0]) throw new IllegalStateException("listener test failure");
                    if(revokeDuringEvent[0]) op[0]=false;
                    return null;
                }
                throw new AssertionError("Deep consulted a history/claim heuristic instead of native events: "+m);
            });
            var staticServer=Bukkit.class.getDeclaredField("server");staticServer.setAccessible(true);Object old=staticServer.get(null);
            staticServer.set(null,CatalogGardenRuntimeTest.proxy(Server.class,(o,m,a)->m.getName().equals("getPluginManager")?manager:null));
            try {
                set(session,"terrainActor",actor);set(session,"terrainArea",CatalogGardenRuntimeTest.area());
                set(session,"terrainWork",new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,60,0,3,68,3)),TerrainGrid.Mode.DEEP_CLEAR,100));
                TerrainWork.Access access=(TerrainWork.Access)call(session,"terrainAccess",new Class<?>[]{World.class,TerrainGrid.Mode.class},world,TerrainGrid.Mode.DEEP_CLEAR);
                values.put("getPlayers",List.of(actor));
                access.validate();check(true,"survey unexpectedly rejected a player inside the selection");
                set(get(session,"terrainWork"),"stage",TerrainWork.Stage.APPLY);
                try { access.validate();throw new AssertionError("work did not pause for a player in the selection"); }
                catch(TerrainWork.PauseException expected){checks++;}
                values.put("getPlayers",List.of());access.validate();
                set(get(session,"terrainWork"),"stage",TerrainWork.Stage.SURVEY);
                var airField=Material.class.getDeclaredField("blockType");airField.setAccessible(true);
                for(Material target:List.of(Material.CHEST,Material.STONE_BRICKS,Material.BEDROCK,Material.BARRIER,Material.WATER)) {
                    Object previous=airField.get(target);
                    var blockType=CatalogGardenRuntimeTest.proxy(org.bukkit.block.BlockType.class,(o,m,a)->switch(m.getName()) { case "isAir" -> false;case "isSolid" -> target!=Material.WATER;default -> throw new UnsupportedOperationException(m.toString()); });
                    airField.set(target,(java.util.function.Supplier<org.bukkit.block.BlockType>)()->blockType);
                    try {
                        material[0]=target; var change=new TerrainGrid.Change(new TerrainGrid.Point(1,64,1),access.read(new TerrainGrid.Point(1,64,1)),"AIR");
                        access.check(change);check(access.tryApply(change)&&material[0]==Material.AIR,"Deep preserved unprotected "+target);
                    } finally { airField.set(target,previous); }
                }
                material[0]=Material.STONE;
                var manual=new BlockBreakEvent(block,actor);plugin.onWandBreak(manual);
                check(manual.isCancelled(),"selection tool broke a block manually");
                wand.selected=false;manual=new BlockBreakEvent(block,actor);plugin.onWandBreak(manual);
                check(!manual.isCancelled(),"ordinary tool was cancelled by AIBuilder");
                var emptyHandChange=new TerrainGrid.Change(new TerrainGrid.Point(1,64,1),access.read(new TerrainGrid.Point(1,64,1)),"AIR");
                check(access.tryApply(emptyHandChange),"Deep failed without the selection tool");
                wand.selected=true;material[0]=Material.STONE;
                var failingEvent=new BlockBreakEvent(block,actor);throwDuringEvent[0]=true;
                try { call(plugin,"dispatchBuilderBreak",new Class[]{BlockBreakEvent.class},failingEvent);throw new AssertionError("test listener did not throw"); }
                catch(IllegalStateException expected) { checks++; }
                throwDuringEvent[0]=false;
                check(!(boolean)call(plugin,"isBuilderBreakEvent",new Class[]{BlockBreakEvent.class},failingEvent),"event scope leaked after exception");
                plugin.onWandBreak(failingEvent);check(failingEvent.isCancelled(),"failed dispatch exempted a later manual event");
                var change=new TerrainGrid.Change(new TerrainGrid.Point(1,64,1),access.read(new TerrainGrid.Point(1,64,1)),"AIR");
                var normalAccess=(TerrainWork.Access)call(session,"terrainAccess",new Class<?>[]{World.class,TerrainGrid.Mode.class},world,TerrainGrid.Mode.CLEAR);
                try { normalAccess.check(change);throw new AssertionError("normal mode bypassed internal spawn protection"); } catch(IllegalArgumentException expected) { checks++; }
                cancelBreak[0]=true;int before=writes[0];
                check(!access.tryApply(change)&&writes[0]==before&&material[0]==Material.STONE,"Deep ignored external cancellation");
                cancelBreak[0]=false;revokeDuringEvent[0]=true;
                check(!access.tryApply(change)&&writes[0]==before,"Deep wrote after OP was revoked by a listener");
                try { access.validate();throw new AssertionError("lost OP did not interrupt Deep"); } catch(IllegalArgumentException expected) { checks++; }
                op[0]=true;plugin.getConfig().set("terrain.deep-clean.enabled",false);
                try { access.validate();throw new AssertionError("Deep continued after being disabled"); } catch(IllegalArgumentException expected) { checks++; }
                check(events[0]>=7,"Deep edits bypassed protection events");
                plugin.getConfig().set("terrain.deep-clean.enabled",true);revokeDuringEvent[0]=false;
                int previousY=((org.bukkit.configuration.file.YamlConfiguration)get(session,"settings")).getInt("zone.y1");
                for(int applied:List.of(0,1)) {
                    var completed=new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,60,0,3,68,3)),TerrainGrid.Mode.DEEP_LEVEL,100);
                    set(completed,"plan",new TerrainGrid.Plan(TerrainGrid.Mode.DEEP_LEVEL,60,0,1,List.of(change),Map.of()));
                    set(completed,"stage",TerrainWork.Stage.DONE);set(completed,"skipped",1);set(completed,"applied",applied);
                    set(session,"terrainWork",completed);set(session,"terrainActor",actor);set(session,"terrainArea",CatalogGardenRuntimeTest.area());messages.clear();
                    call(session,"tickTerrain",new Class[0]);
                    check(messages.stream().anyMatch(line->line.contains(applied==0?"bloqueada":"parcialmente")),"blocked/partial operation reported as prepared");
                    check(((org.bukkit.configuration.file.YamlConfiguration)get(session,"settings")).getInt("zone.y1")==previousY,"partial work moved the build floor");
                    check(get(session,"terrainWork")==null,"terminal operation was not cleared");
                }

            } finally { staticServer.set(null,old); }
        }
        System.out.println("DeepTerrainPolicyTest: "+checks+" checks passed");
    }
}
