import com.elitonvita.aibuilder.*;
import com.google.gson.JsonObject;
import java.util.*;
import org.bukkit.*;

/** Permanent regression: whole-body clearance, arrival tolerance and stale goal interruption. */
public class BuilderStepAsideTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static Object call(Object o,String method,Class<?>[]types,Object...args)throws Exception{return CatalogGardenRuntimeTest.invoke(o,method,types,args);}
    static boolean blocked(Object s,org.bukkit.block.Block b)throws Exception{return(boolean)call(s,"npcInTheWay",new Class[]{org.bukkit.block.Block.class},b);}
    static boolean aside(Object s,org.bukkit.block.Block b)throws Exception{return(boolean)call(s,"stepOffBuild",new Class[]{org.bukkit.block.Block.class},b);}
    public static void main(String[]args)throws Exception{
        var c=Class.forName("PluginLifecycleTest$Fixture").getDeclaredConstructor();c.setAccessible(true);
        try(AutoCloseable f=(AutoCloseable)c.newInstance()){
            Object session=CatalogGardenRuntimeTest.get(f,"session");
            for(boolean airborne:List.of(false,true))for(boolean head:List.of(false,true))for(double edge:List.of(.79,.99)){
                var scene=new NpcWalkerTest.Scene();scene.location=new Location(scene.world,edge,airborne?100.25:64,.5);
                CatalogGardenRuntimeTest.set(session,"npc",scene.npc);NpcWalker walker=new NpcWalker();CatalogGardenRuntimeTest.set(session,"walker",walker);
                ((Map<String,Object>)CatalogGardenRuntimeTest.get(f,"serverValues")).put("getWorld",scene.world);
                var queue=(Deque<JsonObject>)CatalogGardenRuntimeTest.get(session,"queue");queue.clear();
                int y=scene.location.getBlockY()+(head?1:0);JsonObject edit=ConstructionWorkflowTest.action("place_project",1,y,0);queue.add(edit);
                var target=scene.block(1,y,0);check(blocked(session,target),"fixture must start with real body overlapping the target across a cell edge");
                if(airborne)walker.flyToWork(scene.npc,new Location(scene.world,1.5,y,.5),.35,.2,l->true);
                else walker.requestWork(scene.npc,new Location(scene.world,1.5,y,.5),.35,.2,l->true);
                check(aside(session,target),"no autonomous escape from "+(airborne?"air":"ground")+"/"+(head?"head":"feet"));
                check(walker.goal()!=null,"escape stopped inside arrival tolerance while still obstructing the edit");
                check(!walker.goal().equals(new Location(scene.world,1.5,y,.5)),"unrelated goal inside the edit was kept");
                int ticks=0;
                while(blocked(session,target)&&ticks++<160){
                    if(ticks%4==0)aside(session,target);
                    walker.tick(scene.npc);if(walker.airborne())scene.location.add(scene.velocity);else scene.advance();
                }
                check(!blocked(session,target),"escape arrived or waited while still intersecting target");
                check(scene.teleports==0,"escape teleported instead of moving");
                walker.stop(scene.npc);
                // Repeated next blocks must cause another fresh escape, not require a manual recall.
                for(int i=0;i<4;i++){
                    int x=scene.location.getBlockX(),yy=scene.location.getBlockY(),z=scene.location.getBlockZ();
                    queue.clear();queue.add(ConstructionWorkflowTest.action("place_project",x,yy,z));target=scene.block(x,yy,z);
                    check(aside(session,target),"consecutive edit did not start escape");
                    for(ticks=0;blocked(session,target)&&ticks++<160;){walker.tick(scene.npc);if(walker.airborne())scene.location.add(scene.velocity);else scene.advance();if(ticks%4==0&&blocked(session,target))aside(session,target);}
                    check(!blocked(session,target)&&scene.teleports==0,"consecutive edit stayed blocked");walker.stop(scene.npc);
                }
                scene.loaded=false;scene.reads=0;queue.clear();int x=scene.location.getBlockX(),yy=scene.location.getBlockY(),z=scene.location.getBlockZ();queue.add(ConstructionWorkflowTest.action("place_project",x,yy,z));
                check(!aside(session,scene.block(x,yy,z))&&scene.reads==0,"escape read or loaded unavailable chunks");
            }
        }
        System.out.println("BuilderStepAsideTest: "+checks+" checks passed");
    }
}
