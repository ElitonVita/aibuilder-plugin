import com.elitonvita.aibuilder.*;
import java.util.*;
import org.bukkit.command.CommandSender;

public class DeepGuidanceTest {
    static int checks;
    static void check(boolean ok,String text) { checks++;if(!ok)throw new AssertionError(text); }
    public static void main(String[]args)throws Exception {
        var empty=new TerrainPreparationTest.World();
        var work=TerrainPreparationTest.work(empty,TerrainGrid.Mode.LEVEL);
        for(int i=0;i<2000&&!work.terminal()&&work.stage()!=TerrainWork.Stage.READY;i++){work.advance(empty,1000,1000,System.currentTimeMillis());Thread.sleep(1);}
        check(work.stage()==TerrainWork.Stage.READY&&work.plan().level()==60,"clipped selection did not prepare a floor at its selected base");
        check(empty.writes.isEmpty(),"normal failure changed blocks");
        var water=new TerrainPreparationTest.World();water.heights(64,64,64,64);water.set(0,65,0,TerrainPreparationTest.WATER);
        try { TerrainPreparationTest.grid(water,new TerrainGrid.Bounds(0,60,0,3,68,0)).plan(TerrainGrid.Mode.LEVEL,100);throw new AssertionError("normal level accepted water"); }
        catch(TerrainGrid.PlanningException e){check(e.advice()==TerrainGrid.Advice.DEEP_LEVEL,"liquid needs Deep Level advice");}
        var grid=TerrainPreparationTest.grid(water,new TerrainGrid.Bounds(0,60,0,3,68,0));
        check(grid.containsStructuresOrLiquids(),"cleaning did not detect preserved liquid");
        check(grid.plan(TerrainGrid.Mode.CLEAR,100).changes().isEmpty(),"cleaning removed liquid automatically");
        var natural=new TerrainPreparationTest.World();natural.heights(64,64,64,64);
        check(!TerrainPreparationTest.grid(natural,new TerrainGrid.Bounds(0,60,0,3,68,0)).containsStructuresOrLiquids(),"natural terrain unnecessarily requires Deep");
        var fixtureType=Class.forName("PluginLifecycleTest$Fixture");var constructor=fixtureType.getDeclaredConstructor();constructor.setAccessible(true);
        try(AutoCloseable fixture=(AutoCloseable)constructor.newInstance()) {
            var plugin=(AIBuilderPlugin)CatalogGardenRuntimeTest.get(fixture,"plugin");Object session=CatalogGardenRuntimeTest.get(fixture,"session");
            List<String> messages=new ArrayList<>();CommandSender sender=CatalogGardenRuntimeTest.proxy(CommandSender.class,(o,m,a)->{if(m.getName().equals("sendMessage")){messages.add((String)a[0]);return null;}throw new UnsupportedOperationException(m.toString());});
            for(String language:List.of("EN","PT")){
                plugin.getConfig().set("language",language);plugin.getConfig().set("terrain.deep-clean.enabled",true);
                for(boolean level:List.of(false,true)){
                    messages.clear();CatalogGardenRuntimeTest.invoke(session,"adviseDeep",new Class[]{CommandSender.class,boolean.class,boolean.class},sender,level,false);
                    check(messages.getFirst().contains("/builder alfred terrain deep "+(level?"level":"clean")),"wrong or translated command in advice");
                    check(messages.get(1).contains("OP"),"destructive advice omitted OP");
                    check(messages.getFirst().contains(language.equals("EN")?"Normal":"normal"),"wrong advice language");
                }
                messages.clear();CatalogGardenRuntimeTest.invoke(session,"adviseDeep",new Class[]{CommandSender.class,boolean.class,boolean.class},sender,true,true);
                check(messages.getFirst().contains(language.equals("EN")?"Include lower terrain":"Inclua o terreno mais baixo"),"ground advice omitted safe selection alternative");
                plugin.getConfig().set("terrain.deep-clean.enabled",false);messages.clear();
                CatalogGardenRuntimeTest.invoke(session,"adviseDeep",new Class[]{CommandSender.class,boolean.class,boolean.class},sender,false,false);
                check(messages.size()==3&&messages.getLast().contains("terrain.deep-clean.enabled"),"disabled Deep advice omitted enabling requirement");
                check(CatalogGardenRuntimeTest.get(session,"terrainWork")==null,"advice escalated to Deep automatically");
            }
        }
        System.out.println("DeepGuidanceTest: "+checks+" checks passed");
    }
}
