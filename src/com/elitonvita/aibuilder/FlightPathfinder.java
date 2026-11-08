package com.elitonvita.aibuilder;

import java.util.*;

/** Bounded 3D route through open air for a flying builder. Six-neighbour steps, no diagonal cuts, no server API. */
public final class FlightPathfinder {
    public interface Air {
        /** True when the builder's body (two blocks) fits in this cell. */
        boolean open(WalkPathfinder.Cell cell);
    }
    private record Entry(WalkPathfinder.Cell cell, double score) {}
    private FlightPathfinder() {}

    public static List<WalkPathfinder.Cell> find(WalkPathfinder.Cell start, WalkPathfinder.Cell goal, Air air, int maxNodes, int radius) {
        return find(start, goal, air, maxNodes, radius, false);
    }

    /** Safe progress toward a distant work target; the controller replans after each segment. */
    public static List<WalkPathfinder.Cell> findTowards(WalkPathfinder.Cell start, WalkPathfinder.Cell goal, Air air, int maxNodes, int radius) {
        return find(start, goal, air, maxNodes, radius, true);
    }

    /** Find the nearest safe escape cell in open air, including exits above or around an enclosure. */
    public static List<WalkPathfinder.Cell> findNearest(WalkPathfinder.Cell start, Air air,
            java.util.function.Predicate<WalkPathfinder.Cell> destination, int maxNodes, int radius) {
        if (maxNodes < 1 || radius < 1) return List.of();
        maxNodes = Math.min(maxNodes, 6000); radius = Math.min(radius, 32);
        Map<WalkPathfinder.Cell, WalkPathfinder.Cell> previous = new HashMap<>();
        ArrayDeque<WalkPathfinder.Cell> open = new ArrayDeque<>();
        Set<WalkPathfinder.Cell> visited = new HashSet<>();
        previous.put(start, null); visited.add(start); open.add(start);
        WalkPathfinder.Cell found = null;
        // Prefer rising out of a construction cavity, then horizontal clearance, then descent.
        int[][] directions = {{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,-1,0}};
        while (!open.isEmpty() && visited.size() <= maxNodes) {
            WalkPathfinder.Cell current = open.removeFirst();
            if (!current.equals(start) && destination.test(current)) { found = current; break; }
            for (int[] d : directions) {
                int x=current.x()+d[0], y=current.y()+d[1], z=current.z()+d[2];
                if (Math.abs((long)x-start.x())>radius || Math.abs((long)y-start.y())>radius
                        || Math.abs((long)z-start.z())>radius) continue;
                WalkPathfinder.Cell next=new WalkPathfinder.Cell(x,y,z);
                if (visited.contains(next) || !air.open(next)) continue;
                visited.add(next); previous.put(next,current); open.addLast(next);
            }
        }
        if (found == null) return List.of();
        LinkedList<WalkPathfinder.Cell> path=new LinkedList<>();
        for (WalkPathfinder.Cell cursor=found; !cursor.equals(start); cursor=previous.get(cursor)) path.addFirst(cursor);
        return List.copyOf(path);
    }

    private static List<WalkPathfinder.Cell> find(WalkPathfinder.Cell start, WalkPathfinder.Cell goal, Air air, int maxNodes, int radius, boolean partial) {
        if (maxNodes < 1 || radius < 1 || start.equals(goal) || !air.open(goal)) return List.of();
        maxNodes = Math.min(maxNodes, 6000); radius = Math.min(radius, 32);
        Map<WalkPathfinder.Cell, WalkPathfinder.Cell> previous = new HashMap<>();
        Map<WalkPathfinder.Cell, Double> costs = new HashMap<>();
        Set<WalkPathfinder.Cell> closed = new HashSet<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        costs.put(start, 0d); open.add(new Entry(start, distance(start, goal)));
        WalkPathfinder.Cell best = start;
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!open.isEmpty() && closed.size() < maxNodes) {
            WalkPathfinder.Cell current = open.remove().cell();
            if (!closed.add(current)) continue;
            if (distance(current, goal) < distance(best, goal)) best = current;
            if (current.equals(goal)) { best = current; break; }
            for (int[] d : directions) {
                int x = current.x() + d[0], y = current.y() + d[1], z = current.z() + d[2];
                if (Math.abs(x - start.x()) > radius || Math.abs(z - start.z()) > radius || Math.abs(y - start.y()) > radius) continue;
                WalkPathfinder.Cell next = new WalkPathfinder.Cell(x, y, z);
                if (!air.open(next)) continue;
                double cost = costs.get(current) + 1;
                if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                costs.put(next, cost); previous.put(next, current);
                open.add(new Entry(next, cost + distance(next, goal)));
            }
        }
        // voo so vale com rota completa: caminho parcial deixaria o construtor parado no meio do ar
        if (best.equals(start) || (!partial && !best.equals(goal))) return List.of();
        LinkedList<WalkPathfinder.Cell> path = new LinkedList<>();
        for (WalkPathfinder.Cell cursor = best; !cursor.equals(start); cursor = previous.get(cursor)) path.addFirst(cursor);
        return List.copyOf(path);
    }

    private static double distance(WalkPathfinder.Cell a, WalkPathfinder.Cell b) {
        return Math.abs((long) a.x() - b.x()) + Math.abs((long) a.y() - b.y()) + Math.abs((long) a.z() - b.z());
    }
}
