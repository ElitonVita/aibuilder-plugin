package com.elitonvita.aibuilder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Server-thread state machine: batched survey/preflight/recheck/writes with an isolated CPU-only plan stage. */
public final class TerrainWork {
    public static final class PauseException extends RuntimeException {
        public PauseException(String message) { super(message); }
    }
    public enum Stage { SURVEY, PLANNING, PREFLIGHT, READY, RECHECK, FINAL_CHECK, APPLY, DONE, FAILED, CANCELLED }
    public interface Access extends TerrainGrid.Reader {
        /** Run once per tick so changing safety conditions can stop a long operation. */
        default void validate() {}
        /** Throws if a proposed change is no longer permitted. */
        void check(TerrainGrid.Change change);
        void apply(TerrainGrid.Change change);
        /** Return false to skip one protected target and continue with the rest of the plan. */
        default boolean tryApply(TerrainGrid.Change change) { apply(change); return true; }
    }
    private final TerrainGrid grid;
    private final TerrainGrid.Mode mode;
    private final int limit;
    private final int selectedBase;
    private Stage stage=Stage.SURVEY;
    private TerrainGrid.Plan plan;
    private CompletableFuture<TerrainGrid.Plan> planning;
    private int checked, rechecked, processed, applied, skipped, resolved;
    private final java.util.BitSet changedTargets = new java.util.BitSet();
    public int resolved() { return resolved; }
    private long expires;
    private TerrainGrid.Advice advice = TerrainGrid.Advice.NONE;
    public TerrainGrid.Advice advice() { return advice; }
    private String paused="";
    public String paused() { return paused; }
    private String failure="";
    public TerrainWork(TerrainGrid grid,TerrainGrid.Mode mode,int limit) { this(grid,mode,limit,grid.bounds().y1()); }
    public TerrainWork(TerrainGrid grid,TerrainGrid.Mode mode,int limit,int selectedBase) {
        this.grid=grid;this.mode=mode;this.limit=limit;this.selectedBase=selectedBase;
    }
    public Stage stage() { return stage; }
    public TerrainGrid grid() { return grid; }
    public TerrainGrid.Mode mode() { return mode; }
    public TerrainGrid.Plan plan() { return plan; }
    public int applied() { return applied; }
    public int processed() { return processed; }
    public int skipped() { return skipped; }
    public String failure() { return failure; }
    public boolean terminal() { return stage==Stage.DONE||stage==Stage.FAILED||stage==Stage.CANCELLED; }
    public void cancel() { if(!terminal()) { stage=Stage.CANCELLED;grid.cancelPlanning();if(planning!=null) planning.cancel(true); } }
    public boolean confirm(long now) {
        if(stage!=Stage.READY) return false;
        if(now>expires) { fail("a previa expirou; prepare o terreno novamente"); return false; }
        stage=Stage.RECHECK; rechecked=0; return true;
    }
    private void fail(String message) { stage=Stage.FAILED;failure=message;grid.cancelPlanning();if(planning!=null) planning.cancel(true); }
    public void advance(Access access,int scanBudget,int changeBudget,long now) {
        advance(access, scanBudget, changeBudget, now, Long.MAX_VALUE);
    }
    /** Same block type with a different ticking state is still the confirmed target. */
    private boolean compatible(TerrainGrid.Cell before, TerrainGrid.Cell current) {
        if (before.material().equals(current.material())) return true;
        return mode.deep() && soft(before.kind()) && soft(current.kind());
    }
    private static boolean soft(TerrainGrid.Kind kind) {
        return kind==TerrainGrid.Kind.AIR || kind==TerrainGrid.Kind.NATURAL || kind==TerrainGrid.Kind.VEGETATION || kind==TerrainGrid.Kind.LIQUID;
    }
    public void advance(Access access,int scanBudget,int changeBudget,long now,long deadlineNanos) {
        try {
            access.validate();
            paused="";
            switch(stage) {
                case SURVEY -> {
                    grid.scan(access,scanBudget,deadlineNanos);
                    if(grid.complete()) { planning=CompletableFuture.supplyAsync(()->grid.plan(mode,limit,selectedBase));stage=Stage.PLANNING; }
                }
                case PLANNING -> {
                    if(planning.isCompletedExceptionally()) {
                        try { planning.join(); } catch(CompletionException failure) {
                            Throwable cause=failure.getCause();
                            throw cause instanceof RuntimeException runtime?runtime:new IllegalArgumentException("falha ao gerar plano do terreno");
                        }
                    }
                    if(planning.isDone()) { plan=planning.join();planning=null;stage=Stage.PREFLIGHT;checked=0; }
                }
                case PREFLIGHT, FINAL_CHECK -> {
                    for(int i=0;i<changeBudget && checked<plan.changes().size() && System.nanoTime()<deadlineNanos;i++,checked++)
                        if (!changedTargets.get(checked)) access.check(plan.changes().get(checked));
                    if(checked==plan.changes().size()) {
                        checked=0;
                        if(stage==Stage.PREFLIGHT) { stage=Stage.READY;expires=now+120_000L; }
                        else stage=Stage.APPLY;
                    }
                }
                case READY -> { if(now>expires) fail("a previa expirou; prepare o terreno novamente"); }
                case RECHECK -> {
                    // Deep validates each current block immediately before its write. A second
                    // global scan creates a race with random ticks and delays every large job.
                    if (mode.deep()) { stage=Stage.APPLY; break; }
                    for(int i=0;i<scanBudget && rechecked<plan.changes().size() && System.nanoTime()<deadlineNanos;i++,rechecked++) {
                        TerrainGrid.Change change=plan.changes().get(rechecked);
                        if (!compatible(change.before(),access.read(change.point()))) changedTargets.set(rechecked);
                    }
                    if(rechecked==plan.changes().size()) stage=Stage.FINAL_CHECK;
                }
                case APPLY -> {
                    for(int i=0;i<changeBudget && processed<plan.changes().size()
                            && (mode.deep() || System.nanoTime()<deadlineNanos);i++) {
                        TerrainGrid.Change planned=plan.changes().get(processed);
                        TerrainGrid.Cell current=access.read(planned.point());
                        if (current.material().equals(planned.material())) { resolved++; processed++; continue; }
                        if (changedTargets.get(processed) || !compatible(planned.before(),current)) {
                            skipped++; processed++; continue; // Preserve a genuinely changed block; finish the rest.
                        }
                        TerrainGrid.Change change=new TerrainGrid.Change(planned.point(),current,planned.material());
                        access.check(change);
                        if(access.tryApply(change)) applied++; else skipped++;
                        processed++;
                    }
                    if(processed==plan.changes().size()) stage=Stage.DONE;
                }
                default -> { }
            }
        } catch(PauseException waiting) { paused=waiting.getMessage(); }
        catch(RuntimeException failure) { if (failure instanceof TerrainGrid.PlanningException planningFailure) advice = planningFailure.advice(); fail(failure.getMessage()==null?"falha ao preparar terreno":failure.getMessage()); }
    }
}
