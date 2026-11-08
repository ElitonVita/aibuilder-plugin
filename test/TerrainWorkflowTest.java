import com.elitonvita.aibuilder.*;
import java.util.*;

/** Large-area workflow with constantly changing block states, pause/resume, and genuine edits. */
public class TerrainWorkflowTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final TerrainGrid.Cell AIR=new TerrainGrid.Cell("AIR","air",TerrainGrid.Kind.AIR);
    static final TerrainGrid.Cell STRUCTURE=new TerrainGrid.Cell("STONE_BRICKS","bricks",TerrainGrid.Kind.SOLID);
    static final class World implements TerrainWork.Access {
        final Map<TerrainGrid.Point,TerrainGrid.Cell> changed=new HashMap<>();
        int reads,writes,checked;boolean paused,hills;
        public TerrainGrid.Cell read(TerrainGrid.Point p){
            reads++;
            TerrainGrid.Cell c=changed.get(p);if(c!=null)return c;
            if(p.y()>(hills?2+(p.x()+p.z())%3:2))return AIR;
            return new TerrainGrid.Cell("DIRT","ticking-state="+reads,TerrainGrid.Kind.NATURAL);
        }
        public void validate(){if(paused)throw new TerrainWork.PauseException("step outside");}
        public void check(TerrainGrid.Change change){checked++;}
        public void apply(TerrainGrid.Change change){writes++;changed.put(change.point(),change.removes()?AIR:new TerrainGrid.Cell(change.material(),change.material(),TerrainGrid.Kind.NATURAL));}
    }
    static void ready(TerrainWork work,World world)throws Exception {
        for(int i=0;i<2000&&work.stage()!=TerrainWork.Stage.READY&&!work.terminal();i++){
            work.advance(world,8192,512,1000);if(work.stage()==TerrainWork.Stage.PLANNING)Thread.sleep(1);
        }
        check(work.stage()==TerrainWork.Stage.READY,"large-area preview failed: "+work.failure());
        check(world.writes==0,"world changed before confirmation");
    }
    static void finish(TerrainWork work,World world) {
        for(int i=0;i<2000&&!work.terminal();i++)work.advance(world,8192,512,2000);
        check(work.stage()==TerrainWork.Stage.DONE,"job stopped on ordinary world updates: "+work.failure());
    }
    public static void main(String[]args)throws Exception {
        long start=System.nanoTime();
        World world=new World();
        TerrainWork work=new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,0,0,99,19,99)),TerrainGrid.Mode.DEEP_CLEAR,50000);
        ready(work,world);check(work.plan().removed()==30000,"100x100 preview has wrong block count");
        world.changed.put(new TerrainGrid.Point(50,1,50),STRUCTURE);
        world.changed.put(new TerrainGrid.Point(51,1,50),AIR);
        check(work.confirm(2000),"one confirmation rejected");world.paused=true;
        work.advance(world,8192,512,2000);check(!work.terminal()&&world.writes==0&&!work.paused().isEmpty(),"entering area discarded the job or wrote blocks");
        world.paused=false;finish(work,world);
        check(work.applied()==29998&&work.skipped()==1&&work.resolved()==1,"large job did not finish around a genuine changed block");
        check(world.changed.get(new TerrainGrid.Point(50,1,50)).equals(STRUCTURE),"new construction was overwritten");
        check(world.checked>=work.applied(),"writes skipped protection validation");
        World late=new World();TerrainWork during=new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,0,0,9,19,9)),TerrainGrid.Mode.DEEP_CLEAR,1000);
        ready(during,late);during.confirm(2000);during.advance(late,8192,1,2000);during.advance(late,8192,1,2000);
        late.changed.put(new TerrainGrid.Point(9,1,9),STRUCTURE);finish(during,late);
        check(during.skipped()==1&&late.changed.get(new TerrainGrid.Point(9,1,9)).equals(STRUCTURE),"mid-application edit aborted job or was overwritten");
        World hills=new World();hills.hills=true;
        TerrainWork leveling=new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,0,0,99,19,99)),TerrainGrid.Mode.DEEP_LEVEL,50000,2);
        ready(leveling,hills);check(leveling.plan().removed()==9999,"100x100 leveling did not plan every hill");
        hills.changed.put(new TerrainGrid.Point(1,3,0),new TerrainGrid.Cell("GRASS_BLOCK","grass",TerrainGrid.Kind.NATURAL));
        check(leveling.confirm(2000),"leveling confirmation failed");finish(leveling,hills);
        check(leveling.applied()==9999&&leveling.skipped()==0,"natural material changes stopped 100x100 leveling");
        for(int x=0;x<100;x++)for(int z=0;z<100;z++){
            check(hills.read(new TerrainGrid.Point(x,2,z)).kind()==TerrainGrid.Kind.NATURAL,"floor was removed");
            check(hills.read(new TerrainGrid.Point(x,3,z)).kind()==TerrainGrid.Kind.AIR,"hill remains above the floor");
        }
        World deadline=new World();TerrainWork bounded=new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,0,0,99,19,99)),TerrainGrid.Mode.DEEP_CLEAR,50000);
        bounded.advance(deadline,8192,512,1000,System.nanoTime()-1);check(deadline.reads==0,"expired tick budget still scanned blocks");
        System.out.println("TerrainWorkflowTest: "+checks+" checks passed; 100x100 synthetic job completed in "+((System.nanoTime()-start)/1_000_000)+" ms (not live-server timing)");
    }
}
