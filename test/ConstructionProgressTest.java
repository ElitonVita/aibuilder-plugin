import com.elitonvita.aibuilder.*;
import com.google.gson.*;
import java.util.*;
import org.bukkit.*;

/** Existing blocks batch without writes, inventories, mutation budgets or fake placement credit. */
public class ConstructionProgressTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[]args)throws Exception {
        var c=Class.forName("PluginLifecycleTest$Fixture").getDeclaredConstructor();c.setAccessible(true);
        try(AutoCloseable f=(AutoCloseable)c.newInstance()){
            Object session=CatalogGardenRuntimeTest.get(f,"session");AIBuilderPlugin plugin=(AIBuilderPlugin)CatalogGardenRuntimeTest.get(f,"plugin");
            var scene=new NpcWalkerTest.Scene();CatalogGardenRuntimeTest.set(session,"npc",scene.npc);
            ((Map<String,Object>)CatalogGardenRuntimeTest.get(f,"serverValues")).put("getWorld",scene.world);
            Deque<JsonObject> queue=(Deque<JsonObject>)CatalogGardenRuntimeTest.get(session,"queue");
            JsonObject area=ConstructionWorkflowTest.area();area.addProperty("y1",60);
            for(int x=0;x<100;x++)for(int z=0;z<100;z++){
                JsonObject a=ConstructionWorkflowTest.action("place_project",x,63,z);a.add("area",area);queue.add(a);
            }
            long start=System.nanoTime();int passes=0,maxBatch=0;
            while(!queue.isEmpty()&&passes<2000){int before=queue.size();CatalogGardenRuntimeTest.invoke(session,"processQueue",new Class[0]);maxBatch=Math.max(maxBatch,before-queue.size());passes++;}
            check(queue.isEmpty(),"existing floor did not finish bounded checks");
            check(maxBatch>1&&maxBatch<=512,"existing checks still one per step or unbounded");
            check((int)CatalogGardenRuntimeTest.get(session,"existingBlocks")==10000,"existing count wrong");
            check((int)CatalogGardenRuntimeTest.get(session,"placedBlocks")==0,"existing blocks counted as placed");
            check((int)CatalogGardenRuntimeTest.get(session,"removedBlocks")==0,"existing blocks counted as removed");
            check((int)CatalogGardenRuntimeTest.get(session,"planRejected")==0,"valid floor rejected");
            var budget=CatalogGardenRuntimeTest.get(session,"actionBudget");
            check(((Deque<?>)CatalogGardenRuntimeTest.get(budget,"recorded")).isEmpty(),"existing floor consumed mutation rate budget");
            for(String language:List.of("EN","PT")){
                plugin.getConfig().set("language",language);String message=(String)CatalogGardenRuntimeTest.invoke(session,"constructionProgress",new Class[0]);
                check(message.contains(language.equals("EN")?"Placed: 0":"Colocados: 0"),"display claimed new placements or wrong locale");
                check(message.contains("10000"),"display omitted already-present count");
            }
            CatalogGardenRuntimeTest.invoke(session,"resetProgress",new Class[0]);
            check((int)CatalogGardenRuntimeTest.get(session,"existingBlocks")==0,"new operation reused old counters");
            System.out.printf("ConstructionProgressTest: %d checks; 10000 existing cells in %d bounded passes, %.1f ms (fixture, not live timing)%n",checks,passes,(System.nanoTime()-start)/1e6);
        }
    }
}
