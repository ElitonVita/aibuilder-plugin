package com.elitonvita.aibuilder;

import java.util.*;

/** Bounded, immutable-after-survey terrain snapshot. No server dependency. */
public final class TerrainGrid {
    public static final long MAX_VOLUME = 16_000_000L;
    public enum Kind { AIR, NATURAL, VEGETATION, SOLID, LIQUID, OTHER }
    public enum Advice { NONE, MISSING_GROUND, DEEP_LEVEL }
    public static final class PlanningException extends IllegalArgumentException {
        private final Advice advice;
        PlanningException(String message, Advice advice) { super(message); this.advice = advice; }
        public Advice advice() { return advice; }
    }
    /** Normal cleaning preserves these cells; warn rather than silently escalating to Deep. */
    public boolean containsStructuresOrLiquids() {
        requireComplete();
        for (Cell cell : cells) if (cell.kind()==Kind.SOLID || cell.kind()==Kind.LIQUID || cell.kind()==Kind.OTHER) return true;
        return false;
    }
    public enum Mode {
        CLEAR, LEVEL, DEEP_CLEAR, DEEP_LEVEL;
        public boolean deep() { return this == DEEP_CLEAR || this == DEEP_LEVEL; }
        public boolean level() { return this == LEVEL || this == DEEP_LEVEL; }
    }
    public record Cell(String material, String data, Kind kind) {}
    public record Point(int x, int y, int z) {}
    public record Bounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        public Bounds {
            long width = (long)x2-x1+1, height = (long)y2-y1+1, depth = (long)z2-z1+1;
            long volume;
            try { volume=Math.multiplyExact(Math.multiplyExact(width,height),depth); }
            catch(ArithmeticException overflow) { throw new IllegalArgumentException("volume pesquisado excede o limite tecnico",overflow); }
            if (width < 1 || height < 1 || depth < 1 || volume > MAX_VOLUME)
                throw new IllegalArgumentException("regiao invalida ou acima do limite tecnico de "+MAX_VOLUME+" posicoes");
        }
        public int width() { return x2-x1+1; }
        public int height() { return y2-y1+1; }
        public int depth() { return z2-z1+1; }
        public long volume() { return Math.multiplyExact(Math.multiplyExact((long)width(),height()),depth()); }
    }
    @FunctionalInterface public interface Reader { Cell read(Point point); }
    public record Change(Point point, Cell before, String material) {
        public boolean removes() { return material.equals("AIR"); }
    }
    public record Plan(Mode mode, int level, int dominantColumns, int columns, List<Change> changes,
                       Map<String,Integer> materials) {
        public Plan { changes = List.copyOf(changes); materials = Map.copyOf(materials); }
        public long removed() { return changes.stream().filter(Change::removes).count(); }
        public long filled() { return changes.size()-removed(); }
    }

    private final Bounds bounds;
    private final Cell[] cells;
    private int read;
    private volatile boolean planningCancelled;
    public TerrainGrid(Bounds bounds) { this.bounds = bounds; cells = new Cell[Math.toIntExact(bounds.volume())]; }
    public Bounds bounds() { return bounds; }
    public int readCount() { return read; }
    public boolean complete() { return read == cells.length; }
    public Point point(int index) {
        int column = index / bounds.height();
        return new Point(bounds.x1()+column%bounds.width(), bounds.y1()+index%bounds.height(), bounds.z1()+column/bounds.width());
    }
    private int index(int x, int y, int z) { return ((z-bounds.z1())*bounds.width()+x-bounds.x1())*bounds.height()+y-bounds.y1(); }
    public Cell cell(int index) { return cells[index]; }
    void cancelPlanning() { planningCancelled=true; }
    private void checkPlanningActive() {
        if(planningCancelled||Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("terrain planning cancelled");
    }
    public void scan(Reader reader, int budget) { scan(reader, budget, Long.MAX_VALUE); }
    public void scan(Reader reader, int budget, long deadlineNanos) {
        for (int n=0; n<budget && !complete() && (n%32!=0 || System.nanoTime()<deadlineNanos); n++,read++) cells[read] = Objects.requireNonNull(reader.read(point(read)));
    }
    private void requireComplete() { if (!complete()) throw new IllegalStateException("levantamento incompleto"); }
    private int surface(int x,int z) {
        for (int y=bounds.y2(); y>=bounds.y1(); y--) {
            Kind kind=cells[index(x,y,z)].kind();
            if (kind==Kind.NATURAL || kind==Kind.SOLID || kind==Kind.LIQUID) return y;
        }
        return Integer.MIN_VALUE;
    }
    public Plan plan(Mode mode,int maxChanges) {
        return plan(mode,maxChanges,bounds.y1());
    }

    /** Deep leveling can rebuild a floor even if a structure/liquid replaced every natural column. */
    public Plan plan(Mode mode,int maxChanges,int selectedBase) {
        if(selectedBase<bounds.y1() || selectedBase>bounds.y2()) throw new IllegalArgumentException("base selecionada fora do levantamento");
        checkPlanningActive();
        requireComplete();
        Map<Integer,String> changes=new HashMap<>();
        Map<String,Integer> materials=new TreeMap<>();
        for(int i=0;i<cells.length;i++) {
            if((i&0x3fff)==0) checkPlanningActive();
            Cell cell=cells[i];if(cell.kind()!=Kind.AIR) materials.merge(cell.material(),1,Integer::sum);
        }
        if(mode==Mode.DEEP_CLEAR) {
            for(int i=0;i<cells.length;i++) { if((i&0x3fff)==0) checkPlanningActive();if(cells[i].kind()!=Kind.AIR) put(changes,i,"AIR",maxChanges); }
        } else if(mode==Mode.CLEAR) {
            for(int i=0;i<cells.length;i++) { if((i&0x3fff)==0) checkPlanningActive();if(cells[i].kind()==Kind.VEGETATION) put(changes,i,"AIR",maxChanges); }
        } else if(mode.level()) {
            // Leveling always starts with the same vegetation-only cleanup as normal Clean.
            // The ordered plan applies removals before any leveling fills, preventing plants
            // and tree parts from being left floating over the new surface.
            for(int i=0;i<cells.length;i++) { if((i&0x3fff)==0) checkPlanningActive();if(cells[i].kind()==Kind.VEGETATION) put(changes,i,"AIR",maxChanges); }
        }
        int level=bounds.y1(), dominant=0, columns=bounds.width()*bounds.depth();
        if(mode.level()) {
            Map<Integer,Integer> heights=new TreeMap<>();
            Map<String,Integer> surfaceMaterials=new TreeMap<>();
            for(int z=bounds.z1();z<=bounds.z2();z++) for(int x=bounds.x1();x<=bounds.x2();x++) {
                checkPlanningActive();
                int top=mode.deep()?naturalSurface(x,z,selectedBase):surface(x,z);
                if(top==Integer.MIN_VALUE) {
                    // Empty/clipped columns can be filled inside the selected box. Never read
                    // or write below it in normal mode; occupied fill cells are still refused.
                    continue;
                }
                if(top==bounds.y2() && !mode.deep()) throw new IllegalArgumentException("chao atinge o limite superior observado; marque uma regiao mais alta");
                for(int y=top+1;y<=bounds.y2();y++) if(!mode.deep() && cells[index(x,y,z)].kind()==Kind.OTHER)
                    throw new PlanningException("obstaculo acima do chao; remova-o antes de nivelar", Advice.DEEP_LEVEL);
                Cell ground=cells[index(x,top,z)];
                if(ground.kind()!=Kind.NATURAL && !mode.deep()) throw new PlanningException("nivelamento normal recusado: liquido ou construcao na superficie; use Deep (OP) para remover construcoes", Advice.DEEP_LEVEL);
                heights.merge(top,1,Integer::sum);
            }
            dominant=heights.isEmpty()?0:Collections.max(heights.values());
            // A tie has a deterministic lower floor; the user sees its height before confirming.
            level=selectedBase;
            // Deep uses the lower modal floor on ties. A fully clipped/missing natural floor
            // falls back to the explicitly marked base, never to the below-selection scan bound.
            for(var entry:heights.entrySet()) if(entry.getValue()==dominant) { level=entry.getKey();break; }
            if(mode.deep() && level==bounds.y2()) { level=selectedBase;dominant=0; }
            for(int z=bounds.z1();z<=bounds.z2();z++) for(int x=bounds.x1();x<=bounds.x2();x++) {
                checkPlanningActive();
                int top=mode.deep()?naturalSurface(x,z,selectedBase):surface(x,z);
                if(top!=Integer.MIN_VALUE && top==level) surfaceMaterials.merge(cells[index(x,top,z)].material(),1,Integer::sum);
            }
            if(surfaceMaterials.isEmpty()) for(int z=bounds.z1();z<=bounds.z2();z++) for(int x=bounds.x1();x<=bounds.x2();x++) {
                checkPlanningActive();
                int top=mode.deep()?naturalSurface(x,z,selectedBase):surface(x,z);
                if(top!=Integer.MIN_VALUE) surfaceMaterials.merge(cells[index(x,top,z)].material(),1,Integer::sum);
            }
            if(surfaceMaterials.isEmpty()) surfaceMaterials.put("DIRT",1);
            String topMaterial=surfaceMaterials.entrySet().stream().max(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).thenComparing(Map.Entry::getKey)).orElseThrow().getKey();
            String body=Set.of("GRASS_BLOCK","PODZOL","MYCELIUM","ROOTED_DIRT").contains(topMaterial)?"DIRT":topMaterial;
            for(int z=bounds.z1();z<=bounds.z2();z++) for(int x=bounds.x1();x<=bounds.x2();x++) {
                checkPlanningActive();
                int top=mode.deep()?naturalSurface(x,z,selectedBase):surface(x,z);
                if(mode.deep()) {
                    // Deep leveling removes every block above the chosen natural floor,
                    // including structures/trees and buried structures within the selection.
                    for(int y=level+1;y<=bounds.y2();y++) {
                        int i=index(x,y,z);
                        if(cells[i].kind()!=Kind.AIR) put(changes,i,"AIR",maxChanges);
                    }
                    for(int y=top==Integer.MIN_VALUE?bounds.y1():top+1;y<=level;y++) {
                        int i=index(x,y,z);
                        String target=y==level?topMaterial:body;
                        if(!cells[i].material().equals(target)) put(changes,i,target,maxChanges);
                    }
                    int i=index(x,level,z);
                    if(cells[i].kind()==Kind.AIR || !cells[i].material().equals(topMaterial)) put(changes,i,topMaterial,maxChanges);
                } else {
                    for(int y=top;y>level;y--) {
                        int i=index(x,y,z);
                        if(cells[i].kind()!=Kind.NATURAL && cells[i].kind()!=Kind.AIR && cells[i].kind()!=Kind.VEGETATION)
                            throw new PlanningException("nivelamento recusado: bloco artificial ou liquido na escavacao", Advice.DEEP_LEVEL);
                        if(cells[i].kind()!=Kind.AIR) put(changes,i,"AIR",maxChanges);
                    }
                    for(int y=top==Integer.MIN_VALUE?bounds.y1():top+1;y<=level;y++) {
                        int i=index(x,y,z);
                        if(cells[i].kind()!=Kind.AIR && cells[i].kind()!=Kind.VEGETATION) throw new PlanningException("preenchimento ocupado", Advice.DEEP_LEVEL);
                        put(changes,i,y==level?topMaterial:body,maxChanges);
                    }
                }
            }
        }
        if(changes.size()>maxChanges) throw new IllegalArgumentException("preparacao excede o limite de "+maxChanges+" alteracoes");
        List<Change> ordered=new ArrayList<>();
        for(var entry:changes.entrySet()) ordered.add(new Change(point(entry.getKey()),cells[entry.getKey()],entry.getValue()));
        // Excavate top down; fill bottom up. Each position is changed at most once.
        ordered.sort(Comparator.comparingInt((Change c)->c.removes()?0:1)
                .thenComparingInt(c->c.removes()?-c.point().y():c.point().y())
                .thenComparingInt(c->c.point().z()).thenComparingInt(c->c.point().x()));
        return new Plan(mode,level,dominant,columns,ordered,materials);
    }

    private int naturalSurface(int x,int z,int selectedBase) {
        // A stone roof is still classified NATURAL; only search at/below the marked base.
        for(int y=Math.min(bounds.y2(),selectedBase);y>=bounds.y1();y--) if(cells[index(x,y,z)].kind()==Kind.NATURAL) return y;
        return Integer.MIN_VALUE;
    }

    private static void put(Map<Integer,String> changes,int index,String material,int limit) {
        changes.put(index,material);
        if(changes.size()>limit) throw new IllegalArgumentException("preparacao excede o limite de "+limit+" alteracoes");
    }

    public String context() { return context(java.util.function.UnaryOperator.identity()); }

    public String context(java.util.function.UnaryOperator<String> localize) {
        requireComplete();
        Map<String,Integer> materials=new TreeMap<>();
        Map<Integer,Integer> heights=new TreeMap<>();
        int missing=0,min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
        for(Cell cell:cells) if(cell.kind()!=Kind.AIR) materials.merge(cell.material(),1,Integer::sum);
        for(int z=bounds.z1();z<=bounds.z2();z++) for(int x=bounds.x1();x<=bounds.x2();x++) {
            int y=surface(x,z); if(y==Integer.MIN_VALUE) missing++; else { heights.merge(y,1,Integer::sum);min=Math.min(min,y);max=Math.max(max,y); }
        }
        StringBuilder text=new StringBuilder(localize.apply("LEVANTAMENTO DA AREA INTEIRA: "));
        text.append(bounds.width()).append(" x ").append(bounds.depth()).append(localize.apply(" colunas; intervalo observado y="))
                .append(bounds.y1()).append("..").append(bounds.y2()).append(". ");
        text.append(localize.apply("Colunas sem superficie observada: ")).append(missing).append(". ");
        if(min!=Integer.MAX_VALUE) text.append(localize.apply("Superficies entre y=")).append(min).append(localize.apply(" e y=")).append(max).append(". ");
        text.append(localize.apply("Alturas observadas (y:colunas): "));
        heights.entrySet().stream().sorted(Comparator.<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue).reversed()).limit(8)
                .forEach(e->text.append(e.getKey()).append(':').append(e.getValue()).append(' '));
        text.append(localize.apply("Terreno abaixo/acima desse intervalo nao foi inspecionado. A area permitida continua sendo a AREA DE CONSTRUCAO.\n"));
        text.append(localize.apply("Materiais observados (contagem de blocos): "));
        materials.entrySet().stream().sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey)).limit(12)
                .forEach(e->text.append(e.getKey()).append('=').append(e.getValue()).append(' '));
        text.append(localize.apply("\nGrade resumida de superficies (amostras distribuidas por toda a area, incluindo bordas; nao representa cada bloco). X:"));
        List<Integer> xs=sample(bounds.x1(),bounds.x2()),zs=sample(bounds.z1(),bounds.z2());
        for(int x:xs) text.append(' ').append(x);
        for(int z:zs) {
            text.append("\nz=").append(z).append(':');
            for(int x:xs) {
                int y=surface(x,z);
                text.append(' ').append(y==Integer.MIN_VALUE?"?":y+":"+cells[index(x,y,z)].material());
            }
        }
        return text.toString();
    }
    private static List<Integer> sample(int first,int last) {
        int count=Math.min(12,last-first+1); List<Integer> values=new ArrayList<>();
        for(int i=0;i<count;i++) values.add(count==1?first:(int)(first+(long)(last-first)*i/(count-1)));
        return values;
    }
}
