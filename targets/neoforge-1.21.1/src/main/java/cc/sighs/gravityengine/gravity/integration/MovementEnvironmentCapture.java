package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.math.geometry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import java.util.ArrayList;
import java.util.List;

/** Immutable environmental geometry for one movement operation. Native blocks
 * decide eligibility using the real subject and their actual storage address. */
public final class MovementEnvironmentCapture {
    private MovementEnvironmentCapture() {}
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        private final LivingEntity actor;
        private final Snapshot snapshot;
        private final Scope previous;
        private Scope(LivingEntity actor,Snapshot snapshot) {
            this.actor=actor;this.snapshot=snapshot;previous=ACTIVE.get();ACTIVE.set(this);
        }
        @Override public void close() {
            if(ACTIVE.get()!=this) throw new IllegalStateException("environment scope closed out of order");
            if(previous==null) ACTIVE.remove();else ACTIVE.set(previous);
        }
    }
    public static Scope open(LivingEntity actor,Snapshot snapshot) { return new Scope(actor,snapshot); }
    public static Snapshot current(LivingEntity actor) {
        var scope=ACTIVE.get();return scope!=null && scope.actor==actor?scope.snapshot:null;
    }
    public record FluidRegion(FluidState state, OrientedBox volume, Vec3d flow, Vec3d materialVelocity,
                              double immersion, BlockPos address) {}
    public record ClimbRegion(BlockState state, OrientedBox volume, BlockPos address,
                              boolean scaffold, Vec3d materialVelocity) {}
    public record Snapshot(List<FluidRegion> fluids, List<ClimbRegion> climbables) {
        public Snapshot { fluids=List.copyOf(fluids); climbables=List.copyOf(climbables); }
        public Vec3d fluidMaterialVelocity() {
            boolean water=fluids.stream().anyMatch(f->f.state().is(net.minecraft.tags.FluidTags.WATER));
            Vec3d velocity=Vec3d.ZERO;int count=0;
            for(var fluid:fluids) if(!water || fluid.state().is(net.minecraft.tags.FluidTags.WATER)) {
                velocity=velocity.add(fluid.materialVelocity());count++;
            }
            return count==0?Vec3d.ZERO:velocity.divide(count);
        }
    }
    public static final class Builder {
        private final LivingEntity actor;
        private final CollisionBody body;
        private final CollisionWorkTracker work;
        private final List<FluidRegion> fluids=new ArrayList<>();
        private final List<ClimbRegion> climbables=new ArrayList<>();
        public Builder(LivingEntity actor,CollisionWorkTracker work) {
            this.actor=actor; this.body=GravityEntityGeometry.body(actor).deflated(.001); this.work=work;
        }
        public Aabb3d bounds() { return body.enclosingAabb(); }
        public LivingEntity actor() { return actor; }
        public void visit() {
            if(!work.recordBlockPosition()) throw new CollisionComplexityLimitException("environment capture block budget");
        }
        public void accept(Level blocks,BlockPos position,BlockState state,OrientedBox voxel,
                           Vec3d materialVelocity) {
            accept(blocks,position,state,voxel,materialVelocity,true);
        }
        public void accept(Level blocks,BlockPos position,BlockState state,OrientedBox voxel,
                           Vec3d materialVelocity,boolean fluidVisible) {
            var fluid=state.getFluidState();
            if(fluidVisible && !fluid.isEmpty()) {
                double height=fluid.getHeight(blocks,position);
                var half=voxel.halfExtents();
                var volume=new OrientedBox(voxel.center().fma((height-1)*half.y(),voxel.orientation().axisY()),
                        new Vec3d(half.x(),half.y()*height,half.z()),voxel.orientation());
                if(intersects(volume)) {
                    var flow=voxel.orientation().localToWorld(MinecraftMathAdapter.toVec3d(fluid.getFlow(blocks,position)));
                    var normal=volume.orientation().axisY();
                    double radius=body instanceof CharacterCapsule capsule
                            ? capsule.radius()+capsule.halfSegmentLength()*Math.abs(capsule.axis().dot(normal))
                            : projectionRadius((OrientedBox)body,normal);
                    double depth=volume.center().dot(normal)+volume.halfExtents().y()-body.center().dot(normal)+radius;
                    fluids.add(new FluidRegion(fluid,volume,flow,materialVelocity,Math.max(0,depth),position.immutable()));
                }
            }
            var foot=body instanceof CharacterCapsule capsule?capsule.center().fma(-capsule.radius()-capsule.halfSegmentLength(),capsule.axis())
                    :MinecraftMathAdapter.toVec3d(actor.position());
            boolean eligibleCell=net.neoforged.neoforge.common.NeoForgeConfig.SERVER.fullBoundingBoxLadders.get()
                    || voxel.closestPointTo(foot).distanceSquared(foot)<1e-14;
            if(!actor.isSpectator() && eligibleCell && state.isLadder(blocks,position,actor) && intersects(voxel))
                climbables.add(new ClimbRegion(state,voxel,position.immutable(),state.isScaffolding(actor),materialVelocity));
        }
        private boolean intersects(OrientedBox volume) {
            if(!work.recordNarrowPhaseTest()) throw new CollisionComplexityLimitException("environment capture geometry budget");
            return CollisionNarrowPhase.intersects(body,volume);
        }
        public Snapshot build() { return new Snapshot(fluids,climbables); }
    }
    private static double projectionRadius(OrientedBox box,Vec3d axis) {
        var h=box.halfExtents();var f=box.orientation();
        return h.x()*Math.abs(f.axisX().dot(axis))+h.y()*Math.abs(f.axisY().dot(axis))+h.z()*Math.abs(f.axisZ().dot(axis));
    }
    public static Snapshot capture(LivingEntity actor,CollisionWorkTracker work) {
        var result=new Builder(actor,work);
        var bounds=result.bounds(); var level=actor.level();
        boolean occluded=cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.fluidOccluded(actor);
        for(int x=(int)Math.floor(bounds.minX());x<=Math.floor(bounds.maxX());x++)
            for(int y=(int)Math.floor(bounds.minY());y<=Math.floor(bounds.maxY());y++)
                for(int z=(int)Math.floor(bounds.minZ());z<=Math.floor(bounds.maxZ());z++) {
                    result.visit(); var position=new BlockPos(x,y,z);
                    if(!level.hasChunkAt(position)) throw new CollisionSceneCoverageException("environment chunk unavailable");
                    result.accept(level,position,level.getBlockState(position),new OrientedBox(
                            new Vec3d(x+.5,y+.5,z+.5),.5,OrthonormalFrame3d.IDENTITY),Vec3d.ZERO,!occluded);
                }
        cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.captureEnvironment(result);
        cc.sighs.gravityengine.gravity.integration.compat.create.CreateCompatibility.captureEnvironment(result);
        return result.build();
    }
    public static Snapshot captureOrCurrent(LivingEntity actor) {
        var current=current(actor);
        return current!=null?current:capture(actor,new CollisionWorkTracker(CollisionWorkBudget.defaults()));
    }
    public record FluidUpdate(net.minecraft.world.phys.Vec3 velocity,
                              java.util.Map<net.neoforged.neoforge.fluids.FluidType,Double> heights) {
        public FluidUpdate { heights=java.util.Map.copyOf(heights); }
    }
    /** Native NeoForge flow coefficients applied to captured physical volumes. */
    public static FluidUpdate fluidUpdate(LivingEntity actor,Snapshot snapshot) {
        class Sum { double depth;net.minecraft.world.phys.Vec3 flow=net.minecraft.world.phys.Vec3.ZERO;int count; }
        var sums=new java.util.LinkedHashMap<net.neoforged.neoforge.fluids.FluidType,Sum>();
        for(var region:snapshot.fluids()) {
            var type=region.state().getFluidType();var sum=sums.computeIfAbsent(type,key->new Sum());
            sum.depth=Math.max(sum.depth,region.immersion());
            if(actor.isPushedByFluid(type)) {
                var flow=MinecraftMathAdapter.toMinecraft(region.flow());
                if(sum.depth<.4) flow=flow.scale(sum.depth);
                sum.flow=sum.flow.add(flow);sum.count++;
            }
        }
        var velocity=actor.getDeltaMovement();
        var heights=new java.util.LinkedHashMap<net.neoforged.neoforge.fluids.FluidType,Double>();
        for(var entry:sums.entrySet()) {
            var sum=entry.getValue();heights.put(entry.getKey(),sum.depth);
            if(sum.flow.length()>0) {
                var flow=sum.count>0?sum.flow.scale(1.0/sum.count):sum.flow;
                if(!(actor instanceof net.minecraft.world.entity.player.Player)) flow=flow.normalize();
                flow=flow.scale(actor.getFluidMotionScale(entry.getKey()));
                var local=cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(actor)
                        .worldToLocal(MinecraftMathAdapter.toVec3d(velocity));
                if(Math.abs(local.x())<.003 && Math.abs(local.z())<.003 && flow.length()<.0045000000000000005)
                    flow=flow.normalize().scale(.0045000000000000005);
                velocity=velocity.add(flow);
            }
        }
        return new FluidUpdate(velocity,heights);
    }
}
