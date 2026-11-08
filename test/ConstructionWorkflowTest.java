import com.elitonvita.aibuilder.*;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
import org.bukkit.*;
import org.bukkit.entity.Mannequin;

/** Large-site movement, actual queue preservation and token-bound cancellation. No live-world claim. */
public class ConstructionWorkflowTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static Object get(Object o,String name)throws Exception{return CatalogGardenRuntimeTest.get(o,name);}
    static void set(Object o,String name,Object v)throws Exception{CatalogGardenRuntimeTest.set(o,name,v);}
    static Object call(Object o,String name,Class<?>[]types,Object...args)throws Exception{return CatalogGardenRuntimeTest.invoke(o,name,types,args);}
    static AutoCloseable fixture()throws Exception{var c=Class.forName("PluginLifecycleTest$Fixture").getDeclaredConstructor();c.setAccessible(true);return(AutoCloseable)c.newInstance();}
    static JsonObject area(){JsonObject a=new JsonObject();a.addProperty("world","world");for(String axis:List.of("x","z")){a.addProperty(axis+"1",0);a.addProperty(axis+"2",126);}a.addProperty("y1",64);a.addProperty("y2",200);return a;}
    static Object pending(Object f)throws Exception{return call(f,"pending",new Class[]{JsonObject.class},area());}
    static void command(Object f,String...args)throws Exception{call(f,"command",new Class[]{String[].class},(Object)args);}
    static Deque<JsonObject> queue(Object session)throws Exception{return(Deque<JsonObject>)get(session,"queue");}
    static JsonObject action(String type,int x,int y,int z){JsonObject a=new JsonObject();a.addProperty("type",type);a.addProperty("x",x);a.addProperty("y",y);a.addProperty("z",z);a.add("area",area());a.addProperty("material","STONE");return a;}
    public static void main(String[]args)throws Exception {
        try(AutoCloseable f=fixture()) {
            Object session=get(f,"session");AIBuilderPlugin plugin=(AIBuilderPlugin)get(f,"plugin");
            Object build=pending(f);String token=(String)call(session,"confirmationToken",new Class[0]);command(f,"yes");
            check(queue(session).size()==1,"confirmation did not queue build");
            command(f,"cancel","stale");check(queue(session).size()==1,"stale button cancelled running work");
            command(f,"cancel",token);check(queue(session).isEmpty()&&get(session,"activeBuild")==null,"current preview Cancel cannot stop running build");
            Path saved=((Path)get(f,"directory")).resolve("builders").resolve(get(f,"builderId").toString()).resolve("builds/fixture.json");
            check(Files.readString(saved).contains("parada"),"cancel did not save stopped build");
            pending(f);command(f,"yes");command(f,"cancel");check(queue(session).isEmpty()&&get(session,"activeBuild")==null,"plain cancel did not stop execution");
            // A previous button cannot cancel a new plan, or its confirmed replacement.
            pending(f);String next=(String)call(session,"confirmationToken",new Class[0]);command(f,"cancel",token);
            check(get(session,"pendingActions")!=null,"old button cancelled replacement preview");
            command(f,"yes");command(f,"cancel",token);check(queue(session).size()==1,"old button cancelled replacement execution");command(f,"cancel",next);
            // Build a large future footprint. It must not act as a wall before blocks exist.
            List<BlueprintCompiler.Block> blocks=new ArrayList<>();for(int x=0;x<127;x++)for(int z=0;z<104;z++)blocks.add(new BlueprintCompiler.Block(x,64,z,"STONE",0));
            var bt=Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");var bc=bt.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);bc.setAccessible(true);
            set(session,"activeBuild",bc.newInstance("large","world",blocks,area()));
            var scene=new NpcWalkerTest.Scene();set(session,"npc",scene.npc);
            ((Map<String,Object>)get(f,"serverValues")).put("getWorld",scene.world);
            NpcWalker walker=new NpcWalker();set(session,"walker",walker);
            JsonObject edit=action("place_project",95,64,0);queue(session).add(edit);
            check((boolean)call(session,"freeOfBuild",new Class[]{Location.class},new Location(scene.world,20.5,64,.5)),"future footprint still blocks travel");
            check(!(boolean)call(session,"freeOfBuild",new Class[]{Location.class},new Location(scene.world,95.5,64,.5)),"current target not reserved");
            check((boolean)call(session,"moveNear",new Class[]{org.bukkit.block.Block.class,JsonObject.class},scene.block(95,64,0),edit),"distant first block has no work destination");
            for(int i=0;i<1200&&walker.hasGoal();i++){walker.tick(scene.npc);scene.advance();}
            check(walker.state()==NpcWalker.State.ARRIVED&&scene.location.getX()>90,"large build did not advance through bounded ground routes");
            check(scene.teleports==0,"construction teleported");
            // Boundary blocks need room outside the edit selection, while edits stay inside it.
            var narrow=action("place_project",95,64,0);narrow.getAsJsonObject("area").addProperty("x1",95);narrow.getAsJsonObject("area").addProperty("x2",95);narrow.getAsJsonObject("area").addProperty("z2",0);
            walker.stop(scene.npc);check((boolean)call(session,"walkNear",new Class[]{org.bukkit.block.Block.class,JsonObject.class},scene.block(95,64,0),narrow),"edge target could not use unselected air/ground for movement");
            // High targets must use continuous flight, including >32 vertical blocks.
            var tall=new NpcWalkerTest.Scene();set(session,"npc",tall.npc);set(session,"walker",walker=new NpcWalker());
            ((Map<String,Object>)get(f,"serverValues")).put("getWorld",tall.world);
            JsonObject high=action("place_project",0,160,0);queue(session).clear();queue(session).add(high);
            check((boolean)call(session,"flyNear",new Class[]{org.bukkit.block.Block.class,JsonObject.class},tall.block(0,160,0),high),"high sculpture target had no flight destination");
            for(int i=0;i<1200&&walker.hasGoal();i++){walker.tick(tall.npc);tall.location.add(tall.velocity);}
            check(walker.state()==NpcWalker.State.ARRIVED&&tall.location.getY()>155&&tall.teleports==0,"tall build failed segmented physical flight");
            var unloaded=new NpcWalkerTest.Scene();unloaded.loaded=false;
            NpcWalker unloadedWalker=new NpcWalker();unloadedWalker.requestWork(unloaded.npc,new Location(unloaded.world,95.5,64,.5),.65,.2,l->true);unloadedWalker.tick(unloaded.npc);
            check(unloadedWalker.state()==NpcWalker.State.BLOCKED&&unloaded.reads==0&&unloaded.teleports==0,"large-site movement force-loaded a chunk");
            // Force a genuine route failure through the real execute/processQueue path.
            tall.location=new Location(tall.world,.5,64,.5);
            queue(session).clear();JsonObject move=action("move",5,64,0);queue(session).add(move);set(session,"activeBuild",null);set(session,"walker",walker=new NpcWalker());
            call(session,"execute",new Class[]{JsonObject.class},move);set(walker,"state",NpcWalker.State.BLOCKED);
            int rejected=(int)get(session,"planRejected");call(session,"processQueue",new Class[0]);
            check(queue(session).size()==1&&queue(session).peek()==move,"no path discarded the queued block/action");
            check((int)get(session,"planRejected")==rejected,"temporary route failure counted as rejected progress");
            check(((String)get(session,"waitingReason")).startsWith("aguardando:"),"route failure did not become waiting");
            check((long)get(session,"nextMovementAttemptAt")>System.currentTimeMillis(),"blocked route retried without backoff");
            set(session,"nextMovementAttemptAt",0L);call(session,"processQueue",new Class[0]);
            for(int i=0;i<100&&walker.hasGoal();i++){walker.tick(tall.npc);tall.advance();}
            call(session,"processQueue",new Class[0]);
            check(queue(session).isEmpty()&&(int)get(session,"planRejected")==rejected,"route recovery did not execute the preserved action");
            check((int)get(session,"planOk")>0,"recovered action not counted as completed");
        }
        System.out.println("ConstructionWorkflowTest: "+checks+" checks passed");
    }
}
