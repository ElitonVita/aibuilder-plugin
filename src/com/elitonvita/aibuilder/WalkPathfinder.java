package com.elitonvita.aibuilder;

import java.util.*;

/** Bounded ground paths. No server API: a route never crosses unsupported cells or cuts corners. */
public final class WalkPathfinder {
    public record Cell(int x, int y, int z) {}
    public interface Terrain {
        boolean standable(Cell cell);
        boolean clearAbove(Cell cell);
    }
    private record Entry(Cell cell, double score) {}
    private WalkPathfinder() {}

    public static List<Cell> find(Cell start, Cell goal, Terrain terrain, int maxNodes, int radius) {
        if (maxNodes < 1 || radius < 1) return List.of();
        maxNodes = Math.min(maxNodes, 2048); radius = Math.min(radius, 32);
        Map<Cell, Cell> previous = new HashMap<>();
        Map<Cell, Double> costs = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        costs.put(start, 0d); open.add(new Entry(start, distance(start, goal)));
        Cell best = start;
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!open.isEmpty() && closed.size() < maxNodes) {
            Cell current = open.remove().cell();
            if (!closed.add(current)) continue;
            if (distance(current, goal) < distance(best, goal)) best = current;
            if (current.equals(goal)) { best = current; break; }
            for (int[] direction : directions) {
                for (int dy : new int[]{0, 1, -1}) {
                    long x = (long)current.x + direction[0], y = (long)current.y + dy, z = (long)current.z + direction[1];
                    if (Math.abs(x - start.x) > radius || Math.abs(z - start.z) > radius || Math.abs(y - start.y) > 8
                            || x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    Cell next = new Cell((int)x, (int)y, (int)z);
                    if (!terrain.standable(next)) continue;
                    // Ascending needs headroom above the current column; descending above the destination.
                    if ((dy > 0 && !terrain.clearAbove(current)) || (dy < 0 && !terrain.clearAbove(next))) continue;
                    double cost = costs.get(current) + 1 + Math.abs(dy) * .25;
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, cost); previous.put(next, current);
                    open.add(new Entry(next, cost + distance(next, goal)));
                    break;
                }
            }
        }
        if (best.equals(start)) return List.of();
        LinkedList<Cell> path = new LinkedList<>();
        for (Cell cursor = best; !cursor.equals(start); cursor = previous.get(cursor)) path.addFirst(cursor);
        return List.copyOf(path);
    }
    private static double distance(Cell a, Cell b) {
        return Math.abs((long)a.x - b.x) + Math.abs((long)a.z - b.z) + .25 * Math.abs((long)a.y - b.y);
    }
}
