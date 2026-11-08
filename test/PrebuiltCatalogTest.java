import com.elitonvita.aibuilder.BlueprintCompiler;
import com.elitonvita.aibuilder.PrebuiltCatalog;
import com.elitonvita.aibuilder.BlockStateSpec;
import org.bukkit.Material;
import com.google.gson.*;
import java.util.*;

/** Geometry and circulation checks, independent of Minecraft physics/rendering. */
public class PrebuiltCatalogTest {
    private static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    record P(int x,int y,int z){}
    static P at(BlueprintCompiler.Block b){return new P(b.x(),b.y(),b.z());}
    static boolean clear(Map<P,BlueprintCompiler.Block> map,P p){var b=map.get(p);return b==null||b.material().endsWith("_DOOR");}
    static boolean stand(Map<P,BlueprintCompiler.Block> map,P p){
        var floor=map.get(new P(p.x,p.y-1,p.z));
        return floor!=null&&!floor.material().endsWith("_PANE")&&!floor.material().endsWith("_DOOR")&&!floor.material().endsWith("_LEAVES")
            &&clear(map,p)&&clear(map,new P(p.x,p.y+1,p.z));
    }
    static Set<P> reachable(Map<P,BlueprintCompiler.Block> map,P start){
        Set<P> seen=new HashSet<>();Deque<P> queue=new ArrayDeque<>();
        if(stand(map,start)){seen.add(start);queue.add(start);}
        while(!queue.isEmpty()){
            P p=queue.remove();
            for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int dy=-1;dy<=1;dy++){
                P next=new P(p.x+direction[0],p.y+dy,p.z+direction[1]);
                // No clipping through an overhead block when stepping up/down.
                boolean head=dy<=0||clear(map,new P(p.x,p.y+2,p.z));
                if(head&&stand(map,next)&&seen.add(next))queue.add(next);
            }
        }
        return seen;
    }
    public static void main(String[] args) {
        check(PrebuiltCatalog.all().size()==7,"expected four revised houses and three new designs");
        check(PrebuiltCatalog.find("CASA_CAMPO")!=null,"case insensitive lookup");
        check(PrebuiltCatalog.find(null)==null&&PrebuiltCatalog.find("missing")==null,"unknown id lookup");
        check(BlockStateSpec.matches("minecraft:oak_leaves[persistent=true]","minecraft:oak_leaves[distance=3,persistent=true,waterlogged=false]"),"leaf distance counted as damage");
        check(!BlockStateSpec.matches("minecraft:oak_leaves[persistent=true]","minecraft:oak_leaves[distance=3,persistent=false]"),"lost persistence ignored");
        check(BlockStateSpec.matches("minecraft:oak_door[half=lower,facing=south,open=false,powered=false]","minecraft:oak_door[half=lower,facing=south,open=true,powered=true]"),"opened door counted as missing");
        check(!BlockStateSpec.matches("minecraft:oak_door[half=lower,facing=south]","minecraft:oak_door[half=upper,facing=south]"),"wrong door half accepted");
        check(!BlockStateSpec.matches("minecraft:oak_stairs[facing=east]","minecraft:oak_stairs[facing=west]"),"wrong stair orientation accepted");
        check(BlockStateSpec.matches("oak_stairs[facing=east]","minecraft:oak_stairs[facing=east,half=bottom]"),"implicit namespace rejected");
        Set<String> ids=new HashSet<>();
        JsonArray export=new JsonArray();
        for(var entry:PrebuiltCatalog.all()){
            check(ids.add(entry.id()),"duplicate id");check(PrebuiltCatalog.CATEGORIES.contains(entry.category()),"unlisted category");
            var result=BlueprintCompiler.compile(entry.blueprint(),0,0,0,1500);
            check(result.isValid(),entry.id()+" compile: "+result.errors+" parts="+entry.blueprint().getAsJsonArray("parts").size());
            check(result.blocks.size()>=80&&result.blocks.size()<=1500,entry.id()+" block budget");
            Map<P,BlueprintCompiler.Block> map=new HashMap<>();Set<String> materials=new HashSet<>();
            for(var b:result.blocks){
                check(map.put(at(b),b)==null,entry.id()+" overlaps");Material.valueOf(b.material());materials.add(b.material());
                check(b.x()>=0&&b.z()>=0&&b.x()<18&&b.z()<18&&b.y()>=0&&b.y()<24,entry.id()+" exceeds footprint/height");
                if(b.data()!=null)check(b.data().startsWith("minecraft:"+b.material().toLowerCase(Locale.ROOT)+"["),"state material lost");
                if(b.material().endsWith("_PANE"))check(b.data()!=null&&b.data().contains("waterlogged=false")
                    &&(b.data().contains("north=true")||b.data().contains("east=true")||b.data().contains("south=true")||b.data().contains("west=true")),entry.id()+" disconnected pane");
                if(b.material().endsWith("_LEAVES"))check(b.data()!=null&&b.data().contains("persistent=true"),entry.id()+" decaying leaves");
                if(b.material().endsWith("_STAIRS"))check(b.data()!=null&&b.data().contains("facing=")&&b.data().contains("half=bottom"),entry.id()+" unoriented stairs");
            }
            check(materials.size()>=3,entry.id()+" missing palette");
            if(!entry.category().equals("trees")){
                check(materials.stream().anyMatch(m->m.endsWith("_PANE")),entry.id()+" missing panes");
                check(materials.stream().anyMatch(m->m.endsWith("_DOOR")),entry.id()+" missing door");
            }
            for(var b:result.blocks)if(b.material().endsWith("_DOOR")){
                boolean lower=b.data().contains("half=lower");var mate=map.get(new P(b.x(),b.y()+(lower?1:-1),b.z()));
                check(mate!=null&&mate.material().equals(b.material())&&mate.data().contains(lower?"half=upper":"half=lower"),entry.id()+" incomplete door");
                if(lower){
                    check(map.containsKey(new P(b.x(),b.y()-1,b.z())),entry.id()+" unsupported door");
                    check(clear(map,new P(b.x(),b.y(),b.z()-1))&&clear(map,new P(b.x(),b.y()+1,b.z()-1))
                        &&clear(map,new P(b.x(),b.y(),b.z()+1))&&clear(map,new P(b.x(),b.y()+1,b.z()+1)),entry.id()+" blocked doorway");
                }
            }
            JsonArray route=entry.blueprint().getAsJsonArray("walkthrough");
            if(!route.isEmpty()){
                var v=route.get(0).getAsJsonArray();P start=new P(v.get(0).getAsInt(),v.get(1).getAsInt(),v.get(2).getAsInt());
                Set<P> reached=reachable(map,start);
                for(JsonElement e:route){var c=e.getAsJsonArray();P target=new P(c.get(0).getAsInt(),c.get(1).getAsInt(),c.get(2).getAsInt());
                    check(reached.contains(target),entry.id()+" inaccessible entrance/floor/checkpoint "+target);}
            }
            if(entry.id().equals("predio_moderno_3_andares"))for(int y:new int[]{1,6,11})
                check(stand(map,new P(7,y,7)),"building floor missing at "+y);
            if(entry.id().equals("casa_na_arvore"))check(materials.contains("DARK_OAK_LOG")&&materials.contains("OAK_LEAVES"),"treehouse missing its own tree");
            if(entry.category().equals("trees")) {
                Set<P> logs=new HashSet<>();for(var b:result.blocks)if(b.material().endsWith("_LOG"))logs.add(at(b));
                Set<P> linked=new HashSet<>();Deque<P> branches=new ArrayDeque<>();P root=logs.iterator().next();linked.add(root);branches.add(root);
                while(!branches.isEmpty()){P p=branches.remove();for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){P q=new P(p.x+d[0],p.y+d[1],p.z+d[2]);if(logs.contains(q)&&linked.add(q))branches.add(q);}}
                check(linked.equals(logs),"giant tree has disconnected/floating branches");
            }
            var shifted=BlueprintCompiler.compile(entry.blueprint(),-31,64,17,1500);
            check(shifted.isValid()&&shifted.blocks.size()==result.blocks.size(),"translation lost geometry");
            for(int i=0;i<result.blocks.size();i++)check(Objects.equals(result.blocks.get(i).data(),shifted.blocks.get(i).data()),"translation lost states");
            System.out.println(entry.id()+": "+result.blocks.size()+" blocks / "+entry.blueprint().getAsJsonArray("parts").size()+" parts");
            JsonObject item=new JsonObject();item.addProperty("id",entry.id());item.addProperty("title",entry.title());item.add("blueprint",entry.blueprint());
            item.add("blocks",new Gson().toJsonTree(result.blocks));export.add(item);
        }
        if(args.length==1)try{java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),new Gson().toJson(export));}catch(java.io.IOException e){throw new RuntimeException(e);}
        System.out.println("PASS: "+checks+" prebuilt architecture assertions");
    }
}
