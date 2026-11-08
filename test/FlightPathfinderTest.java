import com.elitonvita.aibuilder.FlightPathfinder;
import com.elitonvita.aibuilder.WalkPathfinder.Cell;
import java.util.*;

public class FlightPathfinderTest {
    private static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** Air is open everywhere except the solid cells given. */
    static FlightPathfinder.Air air(Set<Cell> solid) {
        return cell -> !solid.contains(cell);
    }

    static void assertSteps(Cell start, List<Cell> path) {
        Cell previous = start;
        for (Cell next : path) {
            int steps = Math.abs(next.x() - previous.x()) + Math.abs(next.y() - previous.y()) + Math.abs(next.z() - previous.z());
            check(steps == 1, "diagonal or jump in flight path: " + previous + " -> " + next);
            previous = next;
        }
    }

    public static void main(String[] args) {
        Cell start = new Cell(0, 64, 0);

        // 1) open air: straight climb to a high point (the builder must reach heights above the ground)
        Cell high = new Cell(0, 90, 0);
        var up = FlightPathfinder.find(start, high, air(Set.of()), 6000, 32);
        check(!up.isEmpty() && up.getLast().equals(high), "open-air climb to y=90 not found");
        check(up.size() == 26, "open-air climb is not direct: " + up.size());
        assertSteps(start, up);

        // 2) a solid roof between builder and target: route must go around it, never through it
        Set<Cell> roof = new HashSet<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) roof.add(new Cell(x, 70, z));
        Cell above = new Cell(0, 74, 0);
        var around = FlightPathfinder.find(start, above, air(roof), 6000, 32);
        check(!around.isEmpty() && around.getLast().equals(above), "route around roof not found");
        check(around.stream().noneMatch(roof::contains), "flight crossed a solid block");
        assertSteps(start, around);

        // 3) unreachable: target sealed inside a box of solid cells
        Set<Cell> sealed = new HashSet<>();
        Cell target = new Cell(10, 80, 10);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            sealed.add(new Cell(target.x() + dx, target.y() + dy, target.z() + dz));
        }
        check(FlightPathfinder.find(start, target, air(sealed), 6000, 32).isEmpty(), "found a path into a sealed cell");

        // 4) guards: goal inside a solid cell, start == goal, node budget, radius budget
        check(FlightPathfinder.find(start, new Cell(1, 64, 0), air(Set.of(new Cell(1, 64, 0))), 6000, 32).isEmpty(), "routed to a solid goal");
        check(FlightPathfinder.find(start, start, air(Set.of()), 6000, 32).isEmpty(), "already at destination");
        check(FlightPathfinder.find(start, high, air(Set.of()), 5, 32).isEmpty(), "node budget ignored");
        check(FlightPathfinder.find(start, new Cell(0, 110, 0), air(Set.of()), 6000, 20).isEmpty(), "radius budget ignored");

        // 5) a builder trapped in its own room must seek the overhead exit to reach a safe outside cell.
        Set<Cell> room = new HashSet<>();
        for (int y=64;y<=72;y++) for(int n=-3;n<=3;n++) {
            room.add(new Cell(-3,y,n)); room.add(new Cell(3,y,n));
            room.add(new Cell(n,y,-3)); room.add(new Cell(n,y,3));
        }
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)room.add(new Cell(x,63,z));
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x!=0||z!=0)room.add(new Cell(x,73,z));
        var escape=FlightPathfinder.findNearest(start,air(room),cell->cell.x()>3&&cell.y()>=74,6000,16);
        check(!escape.isEmpty()&&escape.getLast().x()>3&&escape.getLast().y()>=74,"no autonomous flight escape over the builder's own walls");
        assertSteps(start,escape);
        check(escape.stream().noneMatch(room::contains),"flight escape crossed its own structure");
        room.add(new Cell(0,73,0));
        check(FlightPathfinder.findNearest(start,air(room),cell->cell.x()>3&&cell.y()>=74,6000,16).isEmpty(),
                "escape search crossed a fully sealed roof");

        System.out.println("PASS: " + checks + " flight path assertions");
    }
}
