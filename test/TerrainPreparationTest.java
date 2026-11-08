import com.elitonvita.aibuilder.TerrainGrid;
import com.elitonvita.aibuilder.TerrainWork;
import java.util.*;

public class TerrainPreparationTest {
    private static int checks;
    static final TerrainGrid.Cell AIR=new TerrainGrid.Cell("AIR","air",TerrainGrid.Kind.AIR);
    static final TerrainGrid.Cell DIRT=new TerrainGrid.Cell("DIRT","dirt",TerrainGrid.Kind.NATURAL);
    static final TerrainGrid.Cell GRASS=new TerrainGrid.Cell("GRASS_BLOCK","grass",TerrainGrid.Kind.NATURAL);
    static final TerrainGrid.Cell LOG=new TerrainGrid.Cell("OAK_LOG","log",TerrainGrid.Kind.VEGETATION);
    static final TerrainGrid.Cell STONE_BRICKS=new TerrainGrid.Cell("STONE_BRICKS","stone_bricks",TerrainGrid.Kind.SOLID);
    static final TerrainGrid.Cell WATER=new TerrainGrid.Cell("WATER","water",TerrainGrid.Kind.LIQUID);
    static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    static void rejects(Runnable action,String message) { try { action.run(); throw new AssertionError(message); } catch(IllegalArgumentException expected){checks++;} }
    static final class World implements TerrainWork.Access {
        final Map<TerrainGrid.Point,TerrainGrid.Cell> cells=new HashMap<>();
        final Set<TerrainGrid.Point> protectedCells=new HashSet<>();
        final List<TerrainGrid.Change> writes=new ArrayList<>();
        int reads;
        public TerrainGrid.Cell read(TerrainGrid.Point p) { reads++;return cells.getOrDefault(p,AIR); }
        public void check(TerrainGrid.Change c) { if(protectedCells.contains(c.point())) throw new IllegalArgumentException("protected"); }
        public void apply(TerrainGrid.Change c) {
            writes.add(c);cells.put(c.point(),new TerrainGrid.Cell(c.material(),c.material().toLowerCase(Locale.ROOT),c.removes()?TerrainGrid.Kind.AIR:TerrainGrid.Kind.NATURAL));
        }
        void set(int x,int y,int z,TerrainGrid.Cell cell) { cells.put(new TerrainGrid.Point(x,y,z),cell); }
        void heights(int... heights) {
            for(int x=0;x<heights.length;x++) for(int y=60;y<=heights[x];y++) set(x,y,0,y==heights[x]?GRASS:DIRT);
        }
    }
    static TerrainGrid grid(World world,TerrainGrid.Bounds bounds) {
        TerrainGrid result=new TerrainGrid(bounds);while(!result.complete())result.scan(world,7);return result;
    }
    static TerrainWork work(World world,TerrainGrid.Mode mode) { return new TerrainWork(new TerrainGrid(new TerrainGrid.Bounds(0,60,0,3,68,0)),mode,1000); }
    static void ready(TerrainWork work,World world) {
        for(int i=0;i<5000 && work.stage()!=TerrainWork.Stage.READY && !work.terminal();i++) {
            work.advance(world,7,2,1000);
            if(work.stage()==TerrainWork.Stage.PLANNING) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        check(work.stage()==TerrainWork.Stage.READY,"preview failed: "+work.failure());
        check(world.writes.isEmpty(),"world changed before confirmation");
    }
    static void finish(TerrainWork work,World world,int budget) {
        for(int i=0;i<1000 && !work.terminal();i++) {
            int previous=world.writes.size();work.advance(world,7,budget,2000);
            check(world.writes.size()-previous<=budget,"write budget exceeded");
        }
    }
    public static void main(String[] args) {
        rejects(()->new TerrainGrid.Bounds(0,0,0,Integer.MAX_VALUE,1,1),"overflow accepted");
        rejects(()->new TerrainGrid.Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),"long multiplication overflow accepted");
        rejects(()->new TerrainGrid.Bounds(1,0,0,0,1,1),"inverted region accepted");
        check(new TerrainGrid.Bounds(0,0,0,63,31,63).volume()==262144/2,"64x64x32 no longer fits enlarged default");
        check(new TerrainGrid.Bounds(0,0,0,125,165,92).volume()==1_945_188L,"126x166x93 deep-level envelope rejected by technical bound");
        check(new TerrainGrid.Bounds(0,0,0,199,215,199).volume()==8_640_000L,"200-cube plus depth envelope rejected by technical bound");
        rejects(()->new TerrainGrid.Bounds(0,0,0,199,400,199),"volume above technical ceiling accepted");
        World world=new World();world.heights(64,64,63,65);
        TerrainGrid grid=grid(world,new TerrainGrid.Bounds(0,60,0,3,68,0));
        var plan=grid.plan(TerrainGrid.Mode.LEVEL,1000);
        check(plan.level()==64 && plan.dominantColumns()==2 && plan.columns()==4,"wrong modal ground level");
        check(plan.filled()==1 && plan.removed()==1,"wrong cut/fill counts");
        check(plan.changes().stream().anyMatch(c->c.point().equals(new TerrainGrid.Point(2,64,0))&&c.material().equals("GRASS_BLOCK")),"depression did not use predominant ground");
        rejects(()->grid.plan(TerrainGrid.Mode.LEVEL,1),"change limit ignored");
        World tied=new World();tied.heights(63,63,64,64);
        check(grid(tied,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100).level()==63,"tie did not choose the displayed lower floor");
        World missing=new World();missing.heights(64,64,63);
        check(grid(missing,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100).changes().stream().anyMatch(c->c.point().x()==3&&!c.removes()),"clipped column was not filled inside the selection");
        World building=new World();building.heights(64,64,63,65);building.set(2,66,0,STONE_BRICKS);
        rejects(()->grid(building,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100),"building accepted as ground");
        World water=new World();water.heights(64,64,63,65);water.set(2,64,0,WATER);
        rejects(()->grid(water,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100),"water was filled");
        World boundary=new World();boundary.heights(64,64,63,68);
        rejects(()->grid(boundary,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100),"clipped ground accepted as surface");
        World underground=new World();underground.heights(64,64,63,66);underground.set(3,65,0,STONE_BRICKS);
        rejects(()->grid(underground,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100),"excavation removed artificial block");
        World forest=new World();forest.heights(64,64,63,65);forest.set(0,65,0,LOG);forest.set(0,66,0,LOG);forest.set(1,65,0,STONE_BRICKS);
        var clear=grid(forest,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.CLEAR,100);
        check(clear.removed()==2 && clear.filled()==0,"clear changed soil/buildings");
        check(clear.changes().get(0).point().y()==66,"tree removal not top down");
        // Deep is destructive regardless of block category, while normal modes stay conservative.
        World structures=new World();structures.heights(64,64,63,65);
        var chest=new TerrainGrid.Cell("CHEST","chest",TerrainGrid.Kind.SOLID);
        var barrier=new TerrainGrid.Cell("BARRIER","barrier",TerrainGrid.Kind.OTHER);
        var bedrock=new TerrainGrid.Cell("BEDROCK","bedrock",TerrainGrid.Kind.SOLID);
        structures.set(0,66,0,chest);structures.set(1,66,0,WATER);
        structures.set(2,67,0,barrier);structures.set(3,68,0,bedrock);
        var structureGrid=grid(structures,new TerrainGrid.Bounds(0,60,0,3,68,0));
        var deepLevel=structureGrid.plan(TerrainGrid.Mode.DEEP_LEVEL,100,65);
        check(deepLevel.level()==64,"deep leveling changed modal natural floor");
        for(int x=0;x<4;x++) {
            int column=x;
            check(deepLevel.changes().stream().anyMatch(c->c.point().x()==column&&c.before().kind()!=TerrainGrid.Kind.NATURAL&&c.removes()),"deep level preserved construction/liquid/special block");
        }
        for(int x=0;x<4;x++) structures.set(x,67,0,new TerrainGrid.Cell("STONE","stone",TerrainGrid.Kind.NATURAL));
        var roof=grid(structures,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.DEEP_LEVEL,100,65);
        check(roof.level()==64,"stone roof was mistaken for natural ground");
        check(roof.changes().stream().filter(c->c.point().y()==67&&c.removes()).count()==4,"deep level preserved stone roof above the chosen floor");
        var deepClear=structureGrid.plan(TerrainGrid.Mode.DEEP_CLEAR,1000);
        check(deepClear.removed()==deepClear.materials().values().stream().mapToInt(Integer::intValue).sum()&&deepClear.filled()==0,"deep clear did not remove every occupied cell");
        rejects(()->structureGrid.plan(TerrainGrid.Mode.LEVEL,100),"normal level destroyed a structure");
        check(structureGrid.plan(TerrainGrid.Mode.CLEAR,100).changes().isEmpty(),"normal clear removed a constructed block");
        World artificial=new World();
        for(int x=0;x<4;x++)for(int y=60;y<=68;y++)artificial.set(x,y,0,y%2==0?STONE_BRICKS:WATER);
        var artificialGrid=grid(artificial,new TerrainGrid.Bounds(0,60,0,3,68,0));
        var rebuilt=artificialGrid.plan(TerrainGrid.Mode.DEEP_LEVEL,100,63);
        check(rebuilt.level()==63&&rebuilt.dominantColumns()==0,"missing natural floor did not use selected base");
        check(rebuilt.removed()==20,"deep level did not clear all blocks above fallback floor");
        check(rebuilt.changes().stream().filter(c->c.point().y()==63).allMatch(c->c.material().equals("DIRT")),"fully artificial floor did not use dirt");
        rejects(()->artificialGrid.plan(TerrainGrid.Mode.LEVEL,100),"normal level accepted a fully artificial/liquid selection");
        rejects(()->artificialGrid.plan(TerrainGrid.Mode.DEEP_CLEAR,1),"deep ignored change budget");
        World oneNatural=new World();oneNatural.heights(64);oneNatural.set(1,66,0,chest);
        check(grid(oneNatural,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.DEEP_LEVEL,100,64).level()==64,"one missing natural column stopped deep leveling");
        check(grid(tied,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.DEEP_LEVEL,100,64).level()==63,"deep tie did not deterministically use lower floor");
        World clipped=new World();clipped.heights(68,68,68,68);
        check(grid(clipped,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.DEEP_LEVEL,100,63).level()==63,"deep refused an occupied survey ceiling");
        World deep=new World();deep.heights(65,65,61,65);
        var fill=grid(deep,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100);
        check(fill.filled()==4,"deeper depression fill missing");
        check(fill.changes().get(0).point().y()==62 && fill.changes().get(0).material().equals("DIRT"),"fill not bottom up / wrong substrate");
        check(fill.changes().get(3).material().equals("GRASS_BLOCK"),"fill cap material missing");
        TerrainWork normal=work(world,TerrainGrid.Mode.LEVEL);
        normal.advance(world,100,100,1000);
        check(normal.stage()==TerrainWork.Stage.PLANNING,"full plan generation still runs inside the survey tick");
        ready(normal,world);
        World cancelPlanningWorld=new World();cancelPlanningWorld.heights(64,64,63,65);
        TerrainWork cancelPlanning=work(cancelPlanningWorld,TerrainGrid.Mode.LEVEL);
        cancelPlanning.advance(cancelPlanningWorld,100,100,1000);cancelPlanning.cancel();
        check(cancelPlanning.stage()==TerrainWork.Stage.CANCELLED&&cancelPlanningWorld.writes.isEmpty(),"cancel during asynchronous planning was ignored");
        check(normal.confirm(2000),"current preview rejected");finish(normal,world,1);
        check(normal.stage()==TerrainWork.Stage.DONE && normal.applied()==2,"confirmed work did not finish");
        check(world.read(new TerrainGrid.Point(2,64,0)).material().equals("GRASS_BLOCK") && world.read(new TerrainGrid.Point(3,65,0)).kind()==TerrainGrid.Kind.AIR,"level did not cut and fill");
        World denied=new World();denied.heights(64,64,63,65);denied.protectedCells.add(new TerrainGrid.Point(2,64,0));
        TerrainWork refused=work(denied,TerrainGrid.Mode.LEVEL);
        for(int i=0;i<5000&&!refused.terminal();i++) {
            refused.advance(denied,8,1,1000);
            if(refused.stage()==TerrainWork.Stage.PLANNING) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        check(refused.stage()==TerrainWork.Stage.FAILED && denied.writes.isEmpty(),"protected preflight made partial changes");
        World changed=new World();changed.heights(64,64,63,65);TerrainWork stale=work(changed,TerrainGrid.Mode.LEVEL);ready(stale,changed);
        changed.set(2,64,0,STONE_BRICKS);check(stale.confirm(2000),"confirmation not accepted for recheck");finish(stale,changed,1);
        check(stale.stage()==TerrainWork.Stage.DONE&&stale.skipped()==1&&changed.read(new TerrainGrid.Point(2,64,0)).equals(STONE_BRICKS),"changed structure was not preserved while completing other blocks");
        World later=new World();later.heights(64,64,63,65);TerrainWork lostPermission=work(later,TerrainGrid.Mode.LEVEL);ready(lostPermission,later);
        later.protectedCells.add(new TerrainGrid.Point(2,64,0));lostPermission.confirm(2000);finish(lostPermission,later,1);
        check(later.writes.isEmpty()&&lostPermission.stage()==TerrainWork.Stage.FAILED,"protection not rechecked after confirmation");
        World timed=new World();timed.heights(64,64,63,65);TerrainWork expired=work(timed,TerrainGrid.Mode.LEVEL);ready(expired,timed);
        check(!expired.confirm(121001)&&timed.writes.isEmpty(),"expired preview changed world");
        World cancelled=new World();cancelled.heights(64,64,63,65);TerrainWork stopped=work(cancelled,TerrainGrid.Mode.LEVEL);ready(stopped,cancelled);
        stopped.confirm(2000);stopped.cancel();stopped.advance(cancelled,10,10,2000);
        check(stopped.stage()==TerrainWork.Stage.CANCELLED&&cancelled.writes.isEmpty(),"cancelled task continued");
        World partial=new World();partial.heights(64,64,63,65);TerrainWork interrupted=work(partial,TerrainGrid.Mode.LEVEL);ready(interrupted,partial);interrupted.confirm(2000);
        while(interrupted.applied()==0&&!interrupted.terminal())interrupted.advance(partial,10,1,2000);
        interrupted.cancel();interrupted.advance(partial,10,1,2000);
        check(interrupted.applied()==1&&partial.writes.size()==1,"partial cancellation misreported completed changes");
        World far=new World();far.heights(64);far.set(63,64,63,WATER);
        TerrainGrid area=grid(far,new TerrainGrid.Bounds(0,60,0,63,68,63));
        String context=area.context();
        check(context.contains("64 x 64 colunas")&&context.contains("z=63:")&&context.contains("64:WATER"),"context missed distant edge of selection");
        check(context.contains("WATER=1")&&context.contains("incluindo bordas"),"context lost full-area material summary");
        System.out.println("PASS: "+checks+" terrain survey/preparation assertions");
    }
}
