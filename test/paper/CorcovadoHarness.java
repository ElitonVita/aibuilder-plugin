package com.elitonvita.aibuilder.check;

import com.elitonvita.aibuilder.*;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Isolated Paper acceptance harness. NEVER install on a player server.
 * Requires an empty test directory and shutdowns its server when finished.
 * Full placement uses controlled NPC positions; real movement is tested separately.
 */
public final class CorcovadoHarness extends JavaPlugin implements org.bukkit.event.Listener {
    JavaPlugin builder;Object session,build;World world;Mannequin npc;
    List<BlueprintCompiler.Block> blocks;List<JsonObject> actions;int cursor,placed,existing,broken;
    int constructionTeleports;boolean airCase,airAsidePassed,groundAsidePassed;List<BlueprintCompiler.Block> asideBlocks;
    long began;List<String> failures=new ArrayList<>();NpcWalker walker;int phase,ticks;Location start;
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){throw new IllegalStateException(n,e.getCause());}}
    void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    @Override public void onEnable(){
        // Absolute, purpose-specific opt-in prevents accidental use in the player's server.
        if(!Path.of("ALLOW_ISOLATED_CORCOVADO_TEST").toFile().isFile()) {getLogger().severe("Missing isolated-test marker; harness disabled");getServer().getPluginManager().disablePlugin(this);return;}
        getServer().getPluginManager().registerEvents(this,this);
        new BukkitRunnable(){public void run(){try{initialize();cancel();runTests();}catch(Exception e){cancel();finish(e);}}}.runTaskLater(this,30);
    }
    void initialize()throws Exception{
        began=System.currentTimeMillis();builder=(JavaPlugin)getServer().getPluginManager().getPlugin("AIBuilder");require(builder!=null,"AIBuilder not enabled");
        for(String key:List.of("creative-mode"))builder.getConfig().set(key,true);
        builder.getConfig().set("use-owner-inventory",false);builder.getConfig().set("spawn-exclusion-radius",0);
        builder.getConfig().set("max-blocks-per-minute",100000);builder.getConfig().set("announce-actions",false);builder.getConfig().set("missions",List.of());
        world=getServer().getWorlds().getFirst();world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);
        JsonObject bp=JsonParser.parseString(Files.readString(builder.getDataFolder().toPath().resolve("prebuilts/mm1_cristo_redentor.json"))).getAsJsonObject();
        var limits=new ConstructionLimits(200,200,200,8_000_000L,120000,16384,4096,128_000_000L,67_108_864L,120000);
        var compiled=BlueprintCompiler.compile(bp,0,64,0,limits);require(compiled.isValid(),"compile "+compiled.errors);blocks=compiled.blocks;
        require(blocks.size()==49335,"project drift "+blocks.size());require(CatalogGardenPolicy.problem(CatalogGardenPolicy.cells(blocks))==null,"garden geometry invalid");
        for(int cx=-1;cx<=8;cx++)for(int cz=-1;cz<=7;cz++)world.getChunkAt(cx,cz).setForceLoaded(true);
        // Stone floor models the prepared ground seen in the user's logs.
        for(int x=-5;x<=132;x++)for(int z=-12;z<=110;z++)world.getBlockAt(x,63,z).setType(Material.STONE,false);
        for(var b:blocks)if(b.y()==64)world.getBlockAt(b.x(),64,b.z()).setType(Material.STONE,false);
        for(var entity:world.getEntities())if(entity instanceof LivingEntity)entity.remove();
        npc=world.spawn(new Location(world,130.5,65,-10.5),Mannequin.class);npc.setInvulnerable(true);npc.setGravity(false);
        Class<?> st=Class.forName("com.elitonvita.aibuilder.BuilderSession",true,builder.getClass().getClassLoader());
        var sc=st.getDeclaredConstructor(AIBuilderPlugin.class,UUID.class,String.class,UUID.class);sc.setAccessible(true);session=sc.newInstance(builder,UUID.randomUUID(),"CorcovadoTest",UUID.randomUUID());set(session,"npc",npc);
        JsonObject area=new JsonObject();area.addProperty("world",world.getName());area.addProperty("x1",0);area.addProperty("x2",126);area.addProperty("y1",64);area.addProperty("y2",200);area.addProperty("z1",0);area.addProperty("z2",103);
        Class<?> bt=Class.forName("com.elitonvita.aibuilder.BuilderSession$Build",true,st.getClassLoader());var bc=bt.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);bc.setAccessible(true);
        build=bc.newInstance("mm1_cristo_redentor",world.getName(),blocks,area);set(build,"catalogId","mm1_cristo_redentor");set(build,"catalogFreeMaterials",true);set(build,"catalogPlacements",new HashSet<>(((Map<?,?>)get(build,"materialsByPosition")).keySet()));set(session,"activeBuild",build);
        actions=(List<JsonObject>)call(session,"blocksToActions",new Class[]{List.class},blocks);
        var preparation=(List<JsonObject>)call(session,"levelingBreaks",new Class[]{List.class,JsonObject.class},blocks,area);
        require(preparation!=null,"prepared floor rejected");for(var a:preparation)((Set<String>)get(build,"preparationTargets")).add(a.get("x")+","+a.get("y")+","+a.get("z"));actions.addAll(0,preparation);
        for(var a:actions)a.add("area",area.deepCopy());
        getLogger().info("CORCO START blocks="+blocks.size()+" preparation="+preparation.size());
    }
    void runTests(){new BukkitRunnable(){public void run(){try{
        if(phase==0){long deadline=System.nanoTime()+8_000_000L;int count=0;
            while(cursor<actions.size()&&count++<128&&System.nanoTime()<deadline){JsonObject a=actions.get(cursor);int x=a.get("x").getAsInt(),y=a.get("y").getAsInt(),z=a.get("z").getAsInt();
                npc.teleport(new Location(world,x+.5,y+1,z+.5));
                String result=(String)call(session,"execute",new Class[]{JsonObject.class},a);
                if(result.equals("ok: ja construido"))existing++;else if(result.startsWith("ok:")){if(a.get("type").getAsString().equals("break"))broken++;else placed++;}else{failures.add(cursor+" "+a+" -> "+result);if(failures.size()>=12)throw new IllegalStateException("placement failures "+failures);}
                cursor++;
            }
            if(cursor==actions.size()){
                require(failures.isEmpty(),"placement refusals "+failures);
                List<?> missing=(List<?>)call(session,"missingBlocks",new Class[]{build.getClass()},build);require(missing.isEmpty(),"missing after placement "+missing.size());
                getLogger().info("CORCO PLACEMENT PASS blocks="+blocks.size()+" placed="+placed+" existing="+existing+" removed="+broken+" missing=0");
                var q=(Deque<JsonObject>)get(session,"queue");q.addAll((List<JsonObject>)call(session,"blocksToActions",new Class[]{List.class},blocks));for(var a:q)a.add("area",((JsonObject)get(build,"area")).deepCopy());phase=1;ticks=0;
            }
        }else if(phase==1){
            int before=((Deque<?>)get(session,"queue")).size();call(session,"processQueue",new Class[0]);ticks++;
            int after=((Deque<?>)get(session,"queue")).size();require(before-after<=512,"unbounded existing batch");
            if(after==0){require((int)get(session,"existingBlocks")==49335,"existing count wrong");require((int)get(session,"placedBlocks")==0,"existing pass claims placement");require((int)get(session,"planRejected")==0,"existing pass rejected blocks");
                getLogger().info("CORCO EXISTING PASS 49335 existing, 0 placed, passes="+ticks);
                npc.teleport(new Location(world,.5,64,-10.5));npc.setGravity(true);walker=new NpcWalker();walker.setBoundary(l->world.getWorldBorder().isInside(l));walker.requestWork(npc,new Location(world,95.5,64,-10.5),.7,.28,l->true);phase=2;ticks=0;start=npc.getLocation();
            }
        }else if(phase==2){walker.tick(npc);ticks++;
            if(walker.state()==NpcWalker.State.ARRIVED){getLogger().info("CORCO WALK PASS distance="+npc.getLocation().distance(start)+" ticks="+ticks);walker.flyToWork(npc,new Location(world,95.5,160,-10.5),.7,.28,l->true);phase=3;ticks=0;start=npc.getLocation();}
            else if(ticks>1600)throw new IllegalStateException("physical walking stalled "+walker.state()+" at "+npc.getLocation());
        }else if(phase==3){walker.tick(npc);ticks++;
            if(walker.state()==NpcWalker.State.ARRIVED){getLogger().info("CORCO FLIGHT PASS ascent="+(npc.getLocation().getY()-start.getY())+" ticks="+ticks);beginAside(true);}
            else if(ticks>1600)throw new IllegalStateException("physical flight stalled "+walker.state()+" at "+npc.getLocation());
        }else if(phase==4){
            ticks++;
            var q=(Deque<JsonObject>)get(session,"queue");
            if(ticks%4==0&&!q.isEmpty()) {
                JsonObject edit=q.peek();int wasPlaced=(int)get(session,"placedBlocks");
                BlockStateProbe probe=new BlockStateProbe(edit.get("x").getAsInt(),edit.get("y").getAsInt(),edit.get("z").getAsInt());
                boolean obstructed=(boolean)call(session,"npcInTheWay",new Class[]{org.bukkit.block.Block.class},world.getBlockAt(probe.x,probe.y,probe.z));
                call(session,"processQueue",new Class[0]);
                if((int)get(session,"placedBlocks")>wasPlaced)require(!obstructed,"placed into the builder's collider");
            }
            if(!q.isEmpty())call(session,"followOwner",new Class[0]);
            else {
                require((int)get(session,"placedBlocks")==8,"sidestep construction did not place all 8 targets");
                require((int)get(session,"planRejected")==0,"sidestep construction discarded a target");
                require(constructionTeleports==0,"sidestep construction used teleport recovery");
                for(var b:asideBlocks)require(world.getBlockAt(b.x(),b.y(),b.z()).getType()==Material.STONE,"sidestep target missing");
                getLogger().info("CORCO SIDESTEP "+(airCase?"AIR":"GROUND")+" PASS targets=8 ticks="+ticks+" teleports=0");
                if(airCase){airAsidePassed=true;beginAside(false);}else{groundAsidePassed=true;cancel();finish(null);}
            }
            if(ticks>800)throw new IllegalStateException("automatic sidestep stalled "+walker.state()+" at "+npc.getLocation());
        }
    }catch(Exception error){cancel();finish(error);}}}.runTaskTimer(this,1,1);}
    record BlockStateProbe(int x,int y,int z) {}
    @org.bukkit.event.EventHandler public void onTeleport(org.bukkit.event.entity.EntityTeleportEvent e) {
        if(phase==4&&e.getEntity().equals(npc))constructionTeleports++;
    }
    void beginAside(boolean air)throws Exception {
        phase=3;airCase=air;asideBlocks=new ArrayList<>();int first=air?96:1,y=air?160:64;
        npc.teleport(new Location(world,air?95.99:.99,air?159.5:64,-10.5));npc.setGravity(!air);
        for(int i=0;i<8;i++)asideBlocks.add(new BlueprintCompiler.Block(first+i,y,-11,"STONE",0));
        JsonObject area=new JsonObject();area.addProperty("world",world.getName());area.addProperty("x1",-5);area.addProperty("x2",132);area.addProperty("y1",64);area.addProperty("y2",200);area.addProperty("z1",-12);area.addProperty("z2",110);
        Class<?> bt=build.getClass();var bc=bt.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);bc.setAccessible(true);
        Object small=bc.newInstance("sidestep",world.getName(),asideBlocks,area);set(session,"activeBuild",small);
        var q=(Deque<JsonObject>)get(session,"queue");q.clear();q.addAll((List<JsonObject>)call(session,"blocksToActions",new Class[]{List.class},asideBlocks));for(var a:q)a.add("area",area.deepCopy());
        walker=new NpcWalker();set(session,"walker",walker);call(session,"resetProgress",new Class[0]);set(session,"nextMovementAttemptAt",0L);
        Location wrong=new Location(world,first+.5,y,-10.5);
        if(air)walker.flyToWork(npc,wrong,.35,.2,l->true);else walker.requestWork(npc,wrong,.35,.2,l->true);
        require((boolean)call(session,"npcInTheWay",new Class[]{org.bukkit.block.Block.class},world.getBlockAt(first,y,-11)),"sidestep fixture did not initially overlap");
        constructionTeleports=0;ticks=0;phase=4;
    }
    void finish(Exception error){try{
        if(error!=null){getLogger().log(java.util.logging.Level.SEVERE,"CORCO FAIL",error);failures.add(error.toString());}
        JsonObject report=new JsonObject();report.addProperty("pass",error==null);report.addProperty("blocks",blocks==null?0:blocks.size());report.addProperty("placed",placed);report.addProperty("existingAtStart",existing);report.addProperty("removed",broken);report.addProperty("durationMs",System.currentTimeMillis()-began);report.add("failures",new Gson().toJsonTree(failures));
        report.addProperty("groundAutoSidestep",groundAsidePassed);report.addProperty("airAutoSidestep",airAsidePassed);report.addProperty("constructionTeleports",constructionTeleports);
        report.addProperty("scope","Continuous automatic 8-block construction from obstructing ground/air positions, plus real Paper block-state/support checks for complete model with controlled NPC placement positions, batched existing verification, plus separate physical 95-block walking and 96-block flight. Not a player-server acceptance test.");
        Files.writeString(Path.of("corcovado-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }catch(Exception e){e.printStackTrace();}finally{getServer().shutdown();}}
}
