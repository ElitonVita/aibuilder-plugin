import com.elitonvita.aibuilder.*;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import java.lang.reflect.*;
import java.util.*;

/** Serverless white-box checks of real authority/order code; no in-game physics claim. */
public class CatalogGardenRuntimeTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static Object invoke(Object o,String n,Class<?>[]types,Object...args)throws Exception{var m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause()instanceof Exception c)throw c;throw e;}}
    static JsonObject area(){JsonObject a=new JsonObject();a.addProperty("world","world");for(String axis:List.of("x","z")){a.addProperty(axis+"1",0);a.addProperty(axis+"2",5);}a.addProperty("y1",64);a.addProperty("y2",70);return a;}
    static Object allocate(Class<?> type)throws Exception{
        Class<?> u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T>type,java.lang.reflect.InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static void runtimeBasin(Object session,Class<?> buildType,Constructor<?>bc)throws Exception{
        Object plugin=get(session,"plugin");
        org.bukkit.Server previousServer=((org.bukkit.plugin.java.JavaPlugin)plugin).getServer();
        var serverField=org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");serverField.setAccessible(true);
        boolean[] brokenClaims={false};
        var manager=proxy(org.bukkit.plugin.PluginManager.class,(o,m,a)->{
            if(m.getName().equals("getPlugin")) {
                if(brokenClaims[0])throw new IllegalStateException("unavailable claim integration");
                return null;
            }
            throw new UnsupportedOperationException(m.toString());
        });
        serverField.set(plugin,proxy(org.bukkit.Server.class,(o,m,a)->m.getName().equals("getPluginManager")?manager:throwUnsupported(m)));
        try{
            Map<CatalogGardenPolicy.Point,Material> actual=new HashMap<>();
            boolean[] loaded={true};org.bukkit.World[] world=new org.bukkit.World[1];
            world[0]=proxy(org.bukkit.World.class,(o,m,a)->switch(m.getName()){
                case "getName"->"world";case "isChunkLoaded"->loaded[0];case "getSpawnLocation"->new org.bukkit.Location(world[0],10000,64,10000);
                case "getBlockAt"->block(world[0],actual,(int)a[0],(int)a[1],(int)a[2]);
                case "equals"->o==a[0];case "hashCode"->System.identityHashCode(o);default->throw new UnsupportedOperationException(m.toString());
            });
            List<BlueprintCompiler.Block> blocks=new ArrayList<>();
            for(int x=0;x<4;x++)for(int z=0;z<3;z++){
                blocks.add(new BlueprintCompiler.Block(x,64,z,"STONE",0));actual.put(new CatalogGardenPolicy.Point(x,64,z),Material.STONE);
                boolean wall=x==0||x==3||z==0||z==2;
                blocks.add(new BlueprintCompiler.Block(x,65,z,wall?"STONE":"WATER",0,wall?null:"minecraft:water[level=0]"));
                if(wall)actual.put(new CatalogGardenPolicy.Point(x,65,z),Material.STONE);
            }
            Object build=bc.newInstance("casa_campo","world",blocks,area());set(build,"catalogId","casa_campo");set(session,"activeBuild",build);
            var cells=CatalogGardenPolicy.cells(blocks);set(build,"gardenBasins",CatalogGardenPolicy.basinIndex(cells));
            JsonObject action=new JsonObject();action.addProperty("type","place_project");action.addProperty("material","WATER");action.add("area",area());
            action.addProperty("x",1);action.addProperty("y",65);action.addProperty("z",1);
            action.addProperty("data","minecraft:water[level=0]");
            var target=block(world[0],actual,1,65,1);
            check(invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)==null,"complete real fixture basin denied");
            set(build,"catalogId",null);
            set(session,"validatedGardenWorld","world");set(session,"validatedGardenCells",cells);
            set(session,"validatedGardenBasins",CatalogGardenPolicy.basinIndex(cells));
            action.addProperty("_aibuilder_contained_water",true);
            check((boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},Material.WATER,action),"server-validated contained water action rejected without catalog identity");
            action.remove("_aibuilder_contained_water");
            check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},Material.WATER,action),"water placement accepted without server-generated proof");
            action.addProperty("_aibuilder_contained_water",true);
            var remoteWall=new CatalogGardenPolicy.Point(3,65,1);actual.remove(remoteWall);
            check(((String)invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)).contains("incompleto"),"remote open wall accepted before first water");actual.put(remoteWall,Material.STONE);
            var floor=new CatalogGardenPolicy.Point(2,64,1);actual.remove(floor);
            check(((String)invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)).contains("incompleto"),"remote missing floor accepted");actual.put(floor,Material.STONE);
            loaded[0]=false;check(((String)invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)).contains("descarregado"),"unloaded basin accepted");loaded[0]=true;
            brokenClaims[0]=true;
            check(((String)invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)).contains("protegido"),"claim API failure accepted");
            brokenClaims[0]=false;
            set(build,"gardenBasins",null);check(((String)invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)).contains("preflight"),"unpreflighted basin accepted");
            // Benchmark the production live-support guard against proxies, not real Paper tick/TPS.
            blocks.clear();actual.clear();
            for(int x=0;x<62;x++)for(int z=0;z<62;z++){
                blocks.add(new BlueprintCompiler.Block(x,64,z,"STONE",0));actual.put(new CatalogGardenPolicy.Point(x,64,z),Material.STONE);
                boolean wall=x==0||x==61||z==0||z==61;
                blocks.add(new BlueprintCompiler.Block(x,65,z,wall?"STONE":"WATER",0,wall?null:"minecraft:water[level=0]"));
                if(wall)actual.put(new CatalogGardenPolicy.Point(x,65,z),Material.STONE);
            }
            var largeArea=area();largeArea.addProperty("x2",61);largeArea.addProperty("z2",61);action.add("area",largeArea);
            build=bc.newInstance("casa_campo","world",blocks,largeArea);set(build,"catalogId","casa_campo");set(session,"activeBuild",build);
            set(build,"gardenBasins",CatalogGardenPolicy.basinIndex(CatalogGardenPolicy.cells(blocks)));
            long start=System.nanoTime();
            for(int i=0;i<20;i++)check(invoke(session,"gardenPlacementProblem",new Class<?>[]{org.bukkit.block.Block.class,JsonObject.class},target,action)==null,"large bounded basin guard failed");
            System.out.printf(Locale.ROOT,"Proxy guard benchmark: 3840 supports, 20 scans, %.3f ms/scan (not a Paper tick benchmark)%n",(System.nanoTime()-start)/20e6);
        }finally{serverField.set(plugin,previousServer);}
    }
    static org.bukkit.block.Block block(org.bukkit.World world,Map<CatalogGardenPolicy.Point,Material>actual,int x,int y,int z){
        return proxy(org.bukkit.block.Block.class,(o,m,a)->switch(m.getName()){
            case "getX"->x;case "getY"->y;case "getZ"->z;case "getWorld"->world;case "getLocation"->new org.bukkit.Location(world,x,y,z);
            case "getType"->actual.getOrDefault(new CatalogGardenPolicy.Point(x,y,z),Material.AIR);
            case "getBlockData"->proxy(org.bukkit.block.data.BlockData.class,(d,dm,da)->{
                if(dm.getName().equals("getAsString"))return "minecraft:"+actual.getOrDefault(new CatalogGardenPolicy.Point(x,y,z),Material.AIR).name().toLowerCase(Locale.ROOT);
                throw new UnsupportedOperationException(dm.toString());});
            default->throw new UnsupportedOperationException(m.toString());
        });
    }
    @SuppressWarnings("unchecked")
    public static void main(String[]args)throws Exception{
        Class<?> fixtureClass=Class.forName("PluginLifecycleTest$Fixture");var ctor=fixtureClass.getDeclaredConstructor();ctor.setAccessible(true);
        try(AutoCloseable fixture=(AutoCloseable)ctor.newInstance()){
            Object session=get(fixture,"session");
            var water=new BlueprintCompiler.Block(1,65,1,"WATER",0,"minecraft:water[level=0]");
            var light=new BlueprintCompiler.Block(2,65,1,"LIGHT",0,"minecraft:light[level=15,waterlogged=false]");
            var rose=new BlueprintCompiler.Block(3,65,1,"POPPY",0);
            var stone=new BlueprintCompiler.Block(1,64,1,"STONE",99);
            var door=new BlueprintCompiler.Block(4,65,1,"OAK_DOOR",99,"minecraft:oak_door[half=lower,facing=south]");
            List<BlueprintCompiler.Block> blocks=List.of(water,rose,light,door,stone);
            List<JsonObject> actions=(List<JsonObject>)invoke(session,"blocksToActions",new Class<?>[]{List.class},blocks);
            check(actions.getFirst().get("material").getAsString().equals("STONE"),"architecture not first");
            check(actions.getLast().get("material").getAsString().equals("WATER"),"source water not last");
            check(actions.get(1).get("material").getAsString().equals("OAK_DOOR"),"door before plants");
            Class<?> buildType=Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");var bc=buildType.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);bc.setAccessible(true);
            Object build=bc.newInstance("casa_campo","world",blocks,area());set(session,"activeBuild",build);
            set(build,"catalogId","casa_campo");
            Object continued=invoke(build,"withIncrement",new Class<?>[]{List.class,String.class},List.of(),"decoracao");
            check("casa_campo".equals(get(continued,"catalogId")),"increment lost server catalog lineage");
            set(build,"catalogId",null);
            for(JsonObject a:actions){
                Material material=Material.valueOf(a.get("material").getAsString());
                // WATER has dedicated safe-basin coverage below; this small fixture intentionally
                // has no complete basin and must not be used to mint water authority.
                if(!CatalogGardenPolicy.isGarden(material.name())||material==Material.WATER)continue;
                a.addProperty("creative",true);
                check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"AI creative flag grants garden authority");
                set(build,"catalogId","casa_campo");
                check((boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"server-owned garden action refused");
                a.addProperty("x",5);check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"altered garden coordinate accepted");
                a.addProperty("x",material==Material.WATER?1:material==Material.LIGHT?2:3);
                String data=a.has("data")?a.get("data").getAsString():null;a.addProperty("data","minecraft:stone");
                check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"altered garden state accepted");
                if(data!=null)a.addProperty("data",data);else a.remove("data");
                a.addProperty("type","replace");check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"replace grants garden authority");a.addProperty("type","place_project");
                set(build,"catalogId","missing_catalog");check(!(boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},material,a),"unknown catalog grants authority");
                set(build,"catalogId",null);
            }
            // Full-map validation happens before individual BlockData parsing: no Bukkit bootstrap needed.
            set(build,"catalogId","casa_campo");set(session,"pendingBuild",build);
            try{invoke(session,"validateActions",new Class<?>[]{List.class,JsonObject.class},actions,area());throw new AssertionError("preflight accepted unsafe complete map");}
            catch(IllegalArgumentException expected){check(expected.getMessage().contains("jardim"),"preflight failed for wrong reason: "+expected);}
            runtimeBasin(session,buildType,bc);
            List<JsonObject> fountain=new ArrayList<>();
            JsonObject source=placement(1,65,1,"WATER","minecraft:water[level=0]");
            fountain.add(source);fountain.add(placement(2,65,1,"WATER","minecraft:water[level=0]"));
            for(int x=0;x<4;x++)for(int z=0;z<3;z++){
                fountain.add(placement(x,64,z,"SMOOTH_QUARTZ",null));
                if(x==0||x==3||z==0||z==2)fountain.add(placement(x,65,z,"SMOOTH_QUARTZ",null));
            }
            invoke(session,"validateGardenActions",new Class<?>[]{List.class,JsonObject.class,buildType},fountain,area(),null);
            check(source.has("_aibuilder_contained_water")&&source.get("_aibuilder_contained_water").getAsBoolean(),"AI fountain water lacks server-generated proof");
            check((boolean)invoke(session,"placeable",new Class<?>[]{Material.class,JsonObject.class},Material.WATER,source),"validated AI fountain source rejected");
            List<JsonObject> unsafeWater=new ArrayList<>();JsonObject spoof=placement(1,65,1,"WATER","minecraft:water[level=0]");spoof.addProperty("_aibuilder_contained_water",true);unsafeWater.add(spoof);
            try{invoke(session,"validateGardenActions",new Class<?>[]{List.class,JsonObject.class,buildType},unsafeWater,area(),null);throw new AssertionError("uncontained AI water plan accepted");}
            catch(IllegalArgumentException expected){check(!spoof.has("_aibuilder_contained_water"),"model-supplied water proof was trusted");}
            List<JsonObject> order=new ArrayList<>(List.of(source,placement(0,64,0,"SMOOTH_QUARTZ",null)));
            var reorder=session.getClass().getDeclaredMethod("waterActionsLast",List.class);reorder.setAccessible(true);reorder.invoke(null,order);
            check(order.getLast()==source,"water source was not deferred until after its basin blocks");
            var pm=session.getClass().getDeclaredMethod("placeableMaterial",Material.class,boolean.class);pm.setAccessible(true);
            for(Material m:List.of(Material.WATER,Material.POPPY,Material.LIGHT))check(!(boolean)pm.invoke(null,m,true),"generic placement accepts catalog-only "+m);
            var materialProblem=session.getClass().getDeclaredMethod("userMaterialProblem",String.class,String.class);materialProblem.setAccessible(true);
            var bukkitServer=org.bukkit.Bukkit.class.getDeclaredField("server");bukkitServer.setAccessible(true);
            bukkitServer.set(null,proxy(org.bukkit.Server.class,(o,m,a)->{
                if(!m.getName().equals("createBlockData"))throw new UnsupportedOperationException(m.toString());
                String data=(String)a[0];int colon=data.indexOf(':');int state=data.indexOf('[');
                String name=data.substring(colon+1,state<0?data.length():state).toUpperCase(Locale.ROOT);
                Material parsed=Material.valueOf(name);
                return proxy(org.bukkit.block.data.BlockData.class,(d,dm,da)->dm.getName().equals("getMaterial")?parsed:throwUnsupported(dm));
            }));
            check(materialProblem.invoke(null,"LIGHT","minecraft:light[level=15,waterlogged=false]")==null,"valid Paper LIGHT state rejected from user prebuilt");
            check(materialProblem.invoke(null,"TNT",null)!=null,"protected material accepted from user prebuilt");
        }
        System.out.println("PASS: "+checks+" catalog garden runtime authority/order assertions");
    }
    static JsonObject placement(int x,int y,int z,String material,String data){JsonObject a=new JsonObject();a.addProperty("type","place");a.addProperty("x",x);a.addProperty("y",y);a.addProperty("z",z);a.addProperty("material",material);if(data!=null)a.addProperty("data",data);return a;}
    static Object throwUnsupported(Method method){throw new UnsupportedOperationException(method.toString());}
}
