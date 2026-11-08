package com.elitonvita.aibuilder;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.Predicate;

/** Main-thread physical locomotion for Mannequins, which are not Mobs and have no native pathfinder. */
public final class NpcWalker {
    public enum State { IDLE, WALKING, ARRIVED, BLOCKED }
    private Location goal;
    private Location lookTarget;
    private Predicate<Location> allowed = location -> true;
    private Predicate<Location> boundary = location -> true;
    private double reach, speed;
    private final Deque<WalkPathfinder.Cell> path = new ArrayDeque<>();
    private State state = State.IDLE;
    private int ticks, repathAt, motionTicks;
    private Location progress;
    // voo: gravidade desligada enquanto o construtor esta no ar; flightPath = caminho 3D ate o goal atual
    private boolean airborne, falling, flightPath;
    private boolean workTravel;

    public State state() { return state; }
    public boolean hasGoal() { return goal != null; }
    public Location goal() { return goal == null ? null : goal.clone(); }
    public boolean airborne() { return airborne; }
    public void setBoundary(Predicate<Location> boundary) { this.boundary = boundary; }
    public void lookAt(Mannequin npc, Location target) {
        lookTarget = target == null ? null : target.clone();
        if (lookTarget != null && npc != null && !npc.isDead()) faceTarget(npc, npc.getLocation());
    }
    public void clearLookTarget() { lookTarget = null; }

    public void request(Mannequin npc, Location target, double reach, double speed, Predicate<Location> allowed) {
        workTravel = false;
        boolean changed = goal == null || flightPath || !Objects.equals(goal.getWorld(), target.getWorld())
                || goal.distanceSquared(target) > 1.0 || Math.abs(this.reach - reach) > .01;
        if (changed) { path.clear(); repathAt = ticks; progress = npc.getLocation(); motionTicks = 0; }
        goal = target.clone(); this.reach = Math.max(.35, Double.isFinite(reach) ? reach : 3);
        this.speed = Math.max(.08, Math.min(.28, Double.isFinite(speed) ? speed : .2));
        this.allowed = allowed;
        flightPath = false;
        if (airborne) land(npc); // andar pelo chao exige cair antes; a queda e tratada no tick
        if (changed) state = State.WALKING;
    }

    /** Construction may cross a large selected site through bounded physical route segments. */
    public void requestWork(Mannequin npc, Location target, double reach, double speed, Predicate<Location> allowed) {
        request(npc, target, reach, speed, allowed);
        workTravel = true;
    }

    public void flyToWork(Mannequin npc, Location target, double reach, double speed, Predicate<Location> allowed) {
        flyTo(npc, target, reach, speed, allowed);
        workTravel = true;
    }

    /** Voo: o construtor segue um caminho 3D pelo ar (sem gravidade) ate o alvo. Nao usa o chao. */
    public void flyTo(Mannequin npc, Location target, double reach, double speed, Predicate<Location> allowed) {
        workTravel = false;
        boolean changed = goal == null || !flightPath || !Objects.equals(goal.getWorld(), target.getWorld())
                || goal.distanceSquared(target) > 1.0 || Math.abs(this.reach - reach) > .01;
        if (changed) { path.clear(); repathAt = ticks; progress = npc.getLocation(); motionTicks = 0; }
        goal = target.clone(); this.reach = Math.max(.35, Double.isFinite(reach) ? reach : 3);
        this.speed = Math.max(.08, Math.min(.28, Double.isFinite(speed) ? speed : .2));
        this.allowed = allowed;
        flightPath = true; falling = false;
        if (!airborne && npc != null && !npc.isDead()) { npc.setGravity(false); airborne = true; }
        if (changed) state = State.WALKING;
    }

    /** Devolve a gravidade ao construtor: ele cai a partir do ponto em que esta. */
    public void land(Mannequin npc) {
        flightPath = false; path.clear();
        if (!airborne) return;
        airborne = false; falling = true;
        if (npc != null && !npc.isDead()) npc.setGravity(true);
    }

    /** Pausa a locomoção e mantém o mannequin pairando no lugar. */
    public void hover(Mannequin npc) {
        goal=null; path.clear(); state=State.IDLE; progress=null; motionTicks=0;
        flightPath=false; falling=false; airborne=true;
        if(npc!=null && !npc.isDead()) { npc.setGravity(false); npc.setVelocity(new Vector()); npc.setFallDistance(0); }
    }

    /** Para o movimento. Nao muda a gravidade: no ar ele continua pairando ate um novo land(). */
    public void stop(Mannequin npc) {
        goal = null; path.clear(); state = State.IDLE; progress = null; motionTicks = 0; flightPath = false;
        if (npc != null && !npc.isDead()) {
            Vector velocity = npc.getVelocity();
            npc.setVelocity(airborne ? new Vector() : new Vector(0, velocity.getY(), 0));
            npc.setJumping(false);
        }
    }

    public void tick(Mannequin npc) {
        ticks++;
        if (npc == null || npc.isDead()) return;
        if (goal == null) { faceTarget(npc, npc.getLocation()); return; }
        if (flightPath) { tickFlight(npc); return; }
        if (falling) {
            // caindo depois de um voo: nao planeja caminho a partir do ar
            if (!npc.isOnGround()) { state = State.WALKING; return; }
            falling = false;
        }
        Location current = npc.getLocation();
        if (!boundary.test(current)) { block(npc); return; }
        Predicate<Location> permitted = location -> boundary.test(location) && allowed.test(location);
        if (!Objects.equals(current.getWorld(), goal.getWorld())) { block(npc); return; }
        if (current.distanceSquared(goal) <= reach * reach) {
            faceTarget(npc, current);
            stop(npc); state = State.ARRIVED; return;
        }
        if (!workTravel && current.distanceSquared(goal) > 64 * 64) { block(npc); return; }
        if (progress == null || Math.pow(progress.getX() - current.getX(), 2) + Math.pow(progress.getZ() - current.getZ(), 2) > .09) {
            progress = current.clone(); motionTicks = 0;
        } else if (++motionTicks > 60) {
            block(npc); path.clear(); repathAt = ticks + 40; motionTicks = 0; return;
        }
        if (ticks >= repathAt && (npc.isOnGround() || path.isEmpty())) {
            World world = current.getWorld();
            WalkPathfinder.Terrain terrain = new WalkPathfinder.Terrain() {
                public boolean standable(WalkPathfinder.Cell cell) { return canStand(world, cell, permitted); }
                public boolean clearAbove(WalkPathfinder.Cell cell) { return clear(world, cell.x(), cell.y() + 2, cell.z()); }
            };
            path.clear(); path.addAll(WalkPathfinder.find(cell(current), cell(goal), terrain, 768, 24));
            repathAt = ticks + 15;
        }
        WalkPathfinder.Cell next = path.peek();
        while (next != null && horizontalSquared(current, next) < .12 && Math.abs(current.getY() - next.y()) < .4) {
            path.remove(); next = path.peek();
        }
        if (next == null || !canStand(current.getWorld(), next, permitted)) { block(npc); return; }
        double dx = next.x() + .5 - current.getX(), dz = next.z() + .5 - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < .01) { brake(npc); return; }
        if (lookTarget != null) faceTarget(npc, current);
        else {
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            float turn = Math.max(-18f, Math.min(18f, wrapped(yaw - current.getYaw())));
            npc.setRotation(current.getYaw() + turn, 8f);
            npc.setBodyYaw(current.getYaw() + turn);
        }
        double vy = npc.getVelocity().getY();
        boolean jump = next.y() > current.getY() + .35 && npc.isOnGround();
        if (jump) vy = .42;
        npc.setJumping(jump);
        double pace = Math.min(speed, distance);
        if (!boundary.test(current.clone().add(dx / distance * pace, jump ? .42 : 0, dz / distance * pace))) {
            block(npc); return;
        }
        npc.setVelocity(new Vector(dx / distance * pace, vy, dz / distance * pace));
        state = State.WALKING;
    }

    private void tickFlight(Mannequin npc) {
        Location current = npc.getLocation();
        if (!Objects.equals(current.getWorld(), goal.getWorld()) || !boundary.test(current)) { block(npc); return; }
        if (current.distanceSquared(goal) <= reach * reach) {
            faceTarget(npc, current);
            stop(npc); state = State.ARRIVED; return;
        }
        if (progress == null || current.distanceSquared(progress) > .09) {
            progress = current.clone(); motionTicks = 0;
        } else if (++motionTicks > 60) {
            block(npc); path.clear(); repathAt = ticks + 40; motionTicks = 0; return;
        }
        if (ticks >= repathAt || path.isEmpty()) {
            World world = current.getWorld();
            FlightPathfinder.Air air = cell -> {
                if (!clear(world, cell.x(), cell.y(), cell.z()) || !clear(world, cell.x(), cell.y() + 1, cell.z())) return false;
                Location spot = new Location(world, cell.x() + .5, cell.y(), cell.z() + .5);
                return boundary.test(spot) && allowed.test(spot);
            };
            path.clear(); path.addAll(workTravel ? FlightPathfinder.findTowards(cell(current), cell(goal), air, 6000, 32)
                    : FlightPathfinder.find(cell(current), cell(goal), air, 6000, 32));
            repathAt = ticks + 15;
            if (path.isEmpty()) { block(npc); return; }
        }
        while (!path.isEmpty()) {
            WalkPathfinder.Cell n = path.peek();
            double hx = n.x() + .5 - current.getX(), hz = n.z() + .5 - current.getZ(), hy = n.y() - current.getY();
            if (hx * hx + hz * hz < .12 && Math.abs(hy) < .4) path.remove(); else break;
        }
        WalkPathfinder.Cell next = path.peek();
        if (next == null) { block(npc); return; }
        Location waypoint = new Location(current.getWorld(), next.x() + .5, next.y(), next.z() + .5);
        if (!boundary.test(waypoint) || !allowed.test(waypoint)
                || !clear(current.getWorld(), next.x(), next.y(), next.z())
                || !clear(current.getWorld(), next.x(), next.y() + 1, next.z())) { block(npc); return; }
        double dx = next.x() + .5 - current.getX(), dy = next.y() - current.getY(), dz = next.z() + .5 - current.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < .01) { brake(npc); return; }
        if (lookTarget != null) faceTarget(npc, current);
        else {
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            float turn = Math.max(-18f, Math.min(18f, wrapped(yaw - current.getYaw())));
            npc.setRotation(current.getYaw() + turn, 0f);
            npc.setBodyYaw(current.getYaw() + turn);
        }
        double pace = Math.min(speed, distance);
        npc.setVelocity(new Vector(dx / distance * pace, dy / distance * pace, dz / distance * pace));
        state = State.WALKING;
    }

    /** Inclinacao da cabeca enquanto analisa parado: positiva = olha o chao, negativa = olha a obra. Faixa -45..55. */
    public static float lookPitch(int tick) {
        return (float) (5 + 50 * Math.sin(tick / 160.0 * 2 * Math.PI));
    }

    private void block(Mannequin npc) { brake(npc); state = State.BLOCKED; }
    private void faceTarget(Mannequin npc, Location current) {
        if (lookTarget == null) return;
        if (npc == null || npc.isDead()) {
            // lookAt() may be called before a live mannequin reference is passed through tick().
            return;
        }
        if (current == null || lookTarget.getWorld() == null || !lookTarget.getWorld().equals(current.getWorld())) return;
        Location eye = current.clone().add(0, 1.45, 0);
        double dx = lookTarget.getX() - eye.getX();
        double dy = lookTarget.getY() - eye.getY();
        double dz = lookTarget.getZ() - eye.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < .001 && Math.abs(dy) < .001) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(.001, horizontal)));
        npc.setRotation(yaw, Math.max(-90, Math.min(90, pitch)));
        npc.setBodyYaw(yaw);
    }
    private void brake(Mannequin npc) {
        Vector velocity = npc.getVelocity();
        npc.setVelocity(airborne ? new Vector() : new Vector(0, velocity.getY(), 0));
        npc.setJumping(false);
    }
    private static float wrapped(float value) { return (value % 360 + 540) % 360 - 180; }
    private static WalkPathfinder.Cell cell(Location l) { return new WalkPathfinder.Cell(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
    private static double horizontalSquared(Location l, WalkPathfinder.Cell cell) {
        double dx = cell.x() + .5 - l.getX(), dz = cell.z() + .5 - l.getZ(); return dx * dx + dz * dz;
    }
    public static boolean canStand(World world, WalkPathfinder.Cell cell, Predicate<Location> allowed) {
        int x = cell.x(), y = cell.y(), z = cell.z();
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        if (!allowed.test(new Location(world, x + .5, y, z + .5))) return false;
        if (!clear(world, x, y, z) || !clear(world, x, y + 1, z)) return false;
        Block floor = world.getBlockAt(x, y - 1, z);
        org.bukkit.util.BoundingBox support = floor.getBoundingBox();
        return !dangerous(floor.getType()) && !floor.isPassable() && !floor.isLiquid()
                && Math.abs(support.getMaxY() - y) <= .05
                && support.getWidthX() >= .6 && support.getWidthZ() >= .6;
    }
    static boolean clear(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block block = world.getBlockAt(x, y, z);
        return block.isPassable() && !block.isLiquid() && !dangerous(block.getType());
    }
    private static boolean dangerous(Material material) {
        String name = material.name();
        return Set.of("LAVA", "WATER", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE",
                "CACTUS", "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "COBWEB", "TRIPWIRE").contains(name)
                || name.endsWith("_PRESSURE_PLATE");
    }
}
