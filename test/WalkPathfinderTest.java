import com.elitonvita.aibuilder.WalkPathfinder;
import com.elitonvita.aibuilder.WalkPathfinder.Cell;
import java.util.*;

public class WalkPathfinderTest {
    private static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static WalkPathfinder.Terrain terrain(Set<Cell> floor, Set<Cell> lowCeilings) {
        return new WalkPathfinder.Terrain() {
            public boolean standable(Cell cell) { return floor.contains(cell); }
            public boolean clearAbove(Cell cell) { return !lowCeilings.contains(cell); }
        };
    }
    public static void main(String[] args) {
        Cell start = new Cell(0,64,0), goal = new Cell(6,64,0);
        Set<Cell> ground = new HashSet<>();
        for (int x=-2; x<=8; x++) for (int z=-3;z<=3;z++) ground.add(new Cell(x,64,z));
        var path = WalkPathfinder.find(start,goal,terrain(ground,Set.of()),768,24);
        check(!path.isEmpty() && path.getLast().equals(goal), "flat path cannot reach owner");
        check(path.size()==6, "flat path is not direct");
        ground.remove(new Cell(2,64,0));
        path=WalkPathfinder.find(start,goal,terrain(ground,Set.of()),768,24);
        check(!path.contains(new Cell(2,64,0)) && path.getLast().equals(goal), "path crossed gap/obstacle");
        Cell previous = start;
        for (Cell next : path) {
            check(Math.abs(next.x()-previous.x()) + Math.abs(next.z()-previous.z()) == 1, "diagonal cut through wall");
            check(Math.abs(next.y()-previous.y())<=1, "unsafe height change"); previous=next;
        }
        Set<Cell> steps=Set.of(start,new Cell(1,65,0),new Cell(2,65,0));
        check(WalkPathfinder.find(start,new Cell(2,65,0),terrain(steps,Set.of()),128,8).size()==2, "one-block step rejected");
        check(WalkPathfinder.find(start,new Cell(2,65,0),terrain(steps,Set.of(start)),128,8).isEmpty(), "jump through low ceiling");
        check(WalkPathfinder.find(start,new Cell(1,62,0),terrain(Set.of(start,new Cell(1,62,0)),Set.of()),128,8).isEmpty(), "unsafe two-block drop");
        check(WalkPathfinder.find(start,goal,terrain(Set.of(start),Set.of()),128,8).isEmpty(), "crossed unsupported void");
        var shortPath=WalkPathfinder.find(start,goal,terrain(ground,Set.of()),768,2);
        check(shortPath.stream().allMatch(c->Math.abs(c.x())<=2 && Math.abs(c.z())<=2), "radius budget exceeded");
        check(WalkPathfinder.find(start,goal,terrain(ground,Set.of()),1,24).isEmpty(), "node budget ignored");
        check(WalkPathfinder.find(start,start,terrain(ground,Set.of()),128,8).isEmpty(), "already at destination");
        System.out.println("PASS: "+checks+" ground path assertions");
    }
}
