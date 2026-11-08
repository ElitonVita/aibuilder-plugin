import com.elitonvita.aibuilder.*;
import org.bukkit.Material;
import java.nio.file.*;
import java.util.*;

/** Import contract and human-sized circulation regressions; no Minecraft physics simulation. */
public class RevisedPrebuiltsTest {
    static int checks;
    record P(int x,int y,int z) {}
    static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static boolean clear(Map<P,BlueprintCompiler.Block> map,int x,int y,int z) {
        var b=map.get(new P(x,y,z));return b==null||b.material().endsWith("_DOOR");
    }
    static Set<P> walk(Map<P,BlueprintCompiler.Block> map,P start) {
        Set<P> seen=new HashSet<>();var queue=new ArrayDeque<P>();queue.add(start);
        while(!queue.isEmpty()) {
            P p=queue.remove();
            var floor=map.get(new P(p.x,p.y-1,p.z));
            if(floor==null||!clear(map,p.x,p.y,p.z)||!clear(map,p.x,p.y+1,p.z)||!seen.add(p))continue;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})queue.add(new P(p.x+d[0],p.y,p.z+d[1]));
        }
        return seen;
    }
    public static void main(String[] args) throws Exception {
        Set<String> reserved=new HashSet<>();PrebuiltCatalog.builtIn().forEach(e->reserved.add(e.id()));
        var load=UserPrebuilts.load(Path.of("exports/premoldados-revisados"),reserved,1500,(m,d)->{
            try {Material.valueOf(m);return null;}catch(IllegalArgumentException e){return "unknown material "+m;}
        });
        check(load.errors().isEmpty()&&load.entries().size()==2,"import failed: "+load.errors());
        for(var entry:load.entries()) {
            var result=BlueprintCompiler.compile(entry.blueprint(),0,0,0,1500);
            check(result.isValid(),"compile failed "+result.errors);
            Map<P,BlueprintCompiler.Block> map=new HashMap<>();
            for(var b:result.blocks)map.put(new P(b.x(),b.y(),b.z()),b);
            if(entry.id().equals("cubo_moderno_habitavel")) {
                // Imported cube must exactly match the revised built-in.
                check(result.blocks.equals(BlueprintCompiler.compile(PrebuiltCatalog.find("cubo_moderno").blueprint(),0,0,0,1500).blocks),"export drifted from built-in");
                check(map.keySet().stream().mapToInt(P::x).max().orElseThrow()==9&&map.keySet().stream().mapToInt(P::z).max().orElseThrow()==10,"cube size");
                var accessible=walk(map,new P(4,1,10));
                for(P p:List.of(new P(4,1,7),new P(3,1,3),new P(3,1,4),new P(6,1,2),new P(6,1,5)))
                    check(accessible.contains(p),"cube cannot reach bed/workbench/living room: "+p);
                for(int x=4;x<=5;x++)for(int z=2;z<=7;z++)for(int y=1;y<=3;y++)check(clear(map,x,y,z),"cube central aisle/headroom blocked");
                var head=map.get(new P(2,1,3));var foot=map.get(new P(2,1,4));
                check(head.material().equals("LIGHT_BLUE_BED")&&foot.material().equals(head.material()),"not a real paired bed");
                check(head.data().contains("part=head")&&foot.data().contains("part=foot")&&head.data().contains("facing=north")&&foot.data().contains("facing=north"),"bed halves disagree");
                check(map.containsKey(new P(2,0,3))&&map.containsKey(new P(2,0,4)),"bed unsupported");
                check(clear(map,2,2,3)&&clear(map,2,3,3)&&clear(map,2,2,4)&&clear(map,2,3,4),"bed has no sleeping headroom");
                for(int x=4;x<=5;x++)for(int y=1;y<=2;y++)check(map.get(new P(x,y,8)).material().equals("BIRCH_DOOR"),"double door incomplete");
            } else {
                check(result.blocks.size()<=1500,"castle exceeds default budget");
                check(map.keySet().stream().mapToInt(P::x).max().orElseThrow()==16&&map.keySet().stream().mapToInt(P::z).max().orElseThrow()==16&&map.keySet().stream().mapToInt(P::y).max().orElseThrow()==26,"castle dimensions");
                Set<P> accessible=walk(map,new P(8,1,16));
                for(P p:List.of(new P(8,1,12),new P(8,1,9),new P(8,1,7),new P(7,1,4),new P(2,1,12),new P(14,1,12),new P(2,1,2),new P(14,1,2)))
                    check(accessible.contains(p),"castle portal/court/hall/turret unreachable: "+p);
                for(int x=7;x<=9;x++)for(int y=1;y<=3;y++)for(int z=11;z<=13;z++)check(clear(map,x,y,z),"castle arch sealed");
                check(result.blocks.stream().filter(b->b.material().equals("GOLD_BLOCK")&&b.y()>10).count()==5,"missing five gold spires");
                check(result.blocks.stream().anyMatch(b->b.y()>20&&b.material().equals("BLUE_CONCRETE")),"missing tall blue central roof");
            }
        }
        System.out.println("PASS: "+checks+" revised prebuilt import/circulation assertions");
    }
}
