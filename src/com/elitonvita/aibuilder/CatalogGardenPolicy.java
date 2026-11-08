package com.elitonvita.aibuilder;

import java.util.*;

/** Pure, fail-closed geometry rules for catalog gardens. No Bukkit or AI authorization. */
public final class CatalogGardenPolicy {
    private CatalogGardenPolicy() {}
    /** Bounds live world/claim reads per source placement. Larger basins fail closed at import. */
    public static final int MAX_BASIN_SUPPORTS = 4096;
    public static final Set<String> FLOWERS = Set.of("POPPY", "DANDELION", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY");
    public static final Set<String> SOIL = Set.of("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MOSS_BLOCK");
    // Deliberately enumerated full cubes; isSolid alone accepts slabs, stairs, leaves and doors.
    public static final Set<String> FULL_SEAL = Set.of("STONE", "SMOOTH_STONE", "STONE_BRICKS", "MOSSY_STONE_BRICKS",
            "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS", "COBBLESTONE", "MOSSY_COBBLESTONE",
            "ANDESITE", "POLISHED_ANDESITE", "DIORITE", "POLISHED_DIORITE", "GRANITE", "POLISHED_GRANITE",
            "DEEPSLATE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES",
            "SANDSTONE", "SMOOTH_SANDSTONE", "CUT_SANDSTONE", "CHISELED_SANDSTONE", "RED_SANDSTONE", "SMOOTH_RED_SANDSTONE",
            "SEA_LANTERN", "QUARTZ_BLOCK", "SMOOTH_QUARTZ", "QUARTZ_BRICKS", "QUARTZ_PILLAR", "BRICKS", "TERRACOTTA",
            "WHITE_CONCRETE", "LIGHT_GRAY_CONCRETE", "GRAY_CONCRETE", "BLACK_CONCRETE", "BLUE_CONCRETE", "CYAN_CONCRETE",
            "WHITE_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "GRAY_TERRACOTTA", "GLASS", "PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE",
            "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MOSS_BLOCK");
    public record Point(int x, int y, int z) {
        Point below() { return new Point(x, y-1, z); }
        List<Point> sides() { return List.of(new Point(x-1,y,z),new Point(x+1,y,z),new Point(x,y,z-1),new Point(x,y,z+1)); }
        public String key() { return x+","+y+","+z; }
    }
    public record Cell(String material, String data) {}
    public static boolean isGarden(String material) { return "WATER".equals(material) || "LIGHT".equals(material) || FLOWERS.contains(material); }
    public static boolean source(String material, String data) {
        return "WATER".equals(material) && ("minecraft:water[level=0]".equals(data) || "water[level=0]".equals(data));
    }
    public static boolean light(String material, String data) {
        return "LIGHT".equals(material) && ("minecraft:light[level=15,waterlogged=false]".equals(data)
                || "light[level=15,waterlogged=false]".equals(data)
                || "minecraft:light[waterlogged=false,level=15]".equals(data) || "light[waterlogged=false,level=15]".equals(data));
    }
    public static boolean sealed(Cell c) { return c != null && FULL_SEAL.contains(c.material()) && (c.data()==null || !c.data().contains("waterlogged=true")); }
    public static Map<Point, Cell> cells(List<BlueprintCompiler.Block> blocks) {
        Map<Point,Cell> out = new HashMap<>();
        for (var b:blocks) out.put(new Point(b.x(),b.y(),b.z()),new Cell(b.material(),b.data()));
        return out;
    }
    /** Constant-size runtime guard: never remove support of any planned source or flower. */
    public static String mutationProblem(Map<Point,Cell> map, Point p, Cell replacement) {
        List<Point> dependent = new ArrayList<>(p.sides());
        Point above = new Point(p.x(),p.y()+1,p.z());
        dependent.add(above);
        for (Point q : dependent) {
            Cell c = map.get(q);
            if (c != null && source(c.material(),c.data()) && !sealed(replacement))
                return "rejeitado: alteracao removeria vedacao da bacia";
        }
        Cell plant = map.get(above);
        if (plant != null && FLOWERS.contains(plant.material())
                && (replacement == null || !SOIL.contains(replacement.material())))
            return "rejeitado: alteracao removeria solo da flor";
        return null;
    }
    /** Whole final map, after all overlapping parts have been resolved. */
    public static String problem(Map<Point,Cell> map) {
        for (var entry:map.entrySet()) {
            Point p=entry.getKey(); Cell c=entry.getValue();
            if (c.data()!=null && c.data().contains("waterlogged=true")) return "jardim: waterlogged nao permitido";
            if ("LIGHT".equals(c.material()) && !light(c.material(),c.data())) return "jardim: LIGHT exige level=15,waterlogged=false";
            if ("WATER".equals(c.material())) {
                if (!source(c.material(),c.data())) return "jardim: apenas WATER[level=0]";
                if (!sealed(map.get(p.below()))) return "jardim: agua sem fundo inteiro vedado em "+p;
                for (Point side:p.sides()) {
                    Cell n=map.get(side);
                    if (!sealed(n) && (n==null || !source(n.material(),n.data()))) return "jardim: agua sem parede inteira vedada em "+p;
                }
            } else if (FLOWERS.contains(c.material())) {
                Cell soil=map.get(p.below());
                if (soil==null || !SOIL.contains(soil.material())) return "jardim: flor sem solo em "+p;
                if (c.data()!=null && !c.data().equals("minecraft:"+c.material().toLowerCase(Locale.ROOT))
                        && !c.data().equals(c.material().toLowerCase(Locale.ROOT))) return "jardim: estado de flor nao permitido";
            }
        }
        Set<Point> inspected = new HashSet<>();
        for (var e:map.entrySet()) if (source(e.getValue().material(),e.getValue().data()) && !inspected.contains(e.getKey())) {
            Basin basin=basin(map,e.getKey()); inspected.addAll(basin.water());
            if (basin.supports().size()>MAX_BASIN_SUPPORTS) return "jardim: bacia excede "+MAX_BASIN_SUPPORTS+" apoios; subdivida fisicamente";
        }
        return null;
    }
    /** All bottom/wall cells of a connected single-layer basin must exist before its first source. */
    public record Basin(Set<Point> water, Set<Point> supports) {}
    public static Set<Point> basinSupports(Map<Point,Cell> map, Point start) { return basin(map,start).supports(); }
    public static Basin basin(Map<Point,Cell> map, Point start) {
        Set<Point> visited=new HashSet<>(), supports=new HashSet<>();
        Deque<Point> todo=new ArrayDeque<>(); todo.add(start);
        while(!todo.isEmpty()) {
            Point p=todo.removeFirst(); if(!visited.add(p))continue;
            Cell c=map.get(p); if(c==null || !source(c.material(),c.data())) throw new IllegalArgumentException("agua fora do plano");
            if(!sealed(map.get(p.below()))) throw new IllegalArgumentException("fundo inseguro");
            supports.add(p.below());
            for(Point side:p.sides()) {
                Cell n=map.get(side);
                if(n!=null && source(n.material(),n.data())) todo.add(side);
                else if(sealed(n)) supports.add(side);
                else throw new IllegalArgumentException("parede insegura");
            }
        }
        return new Basin(Set.copyOf(visited),Set.copyOf(supports));
    }
    public static Map<Point,Set<Point>> basinIndex(Map<Point,Cell> map) {
        Map<Point,Set<Point>> index = new HashMap<>();
        for (var e:map.entrySet()) if(source(e.getValue().material(),e.getValue().data()) && !index.containsKey(e.getKey())) {
            Basin basin=basin(map,e.getKey());
            if(basin.supports().size()>MAX_BASIN_SUPPORTS)throw new IllegalArgumentException("bacia grande demais");
            for(Point p:basin.water())index.put(p,basin.supports());
        }
        return Map.copyOf(index);
    }
    public static int order(String material) { return "WATER".equals(material)?3:(FLOWERS.contains(material)||"LIGHT".equals(material))?2:material.endsWith("_DOOR")?1:0; }
}
