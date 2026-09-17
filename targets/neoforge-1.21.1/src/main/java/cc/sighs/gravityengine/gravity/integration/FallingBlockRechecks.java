package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.field.GravityFieldInstance;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.mixin.ChunkMapFallingAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.FallingBlock;
import java.util.*;

/** Publication-driven, level-owned discovery. Empty registries never scan sections.
 * Unknown/time-dependent evaluators are revisited at most once per 20 ticks per section,
 * within their published bounds and a shared per-tick budget. Removal drains only affected
 * position bits once. No BlockState, collision subject or evaluation is retained. */
public final class FallingBlockRechecks {
    public static final int CHUNK_VISITS_PER_TICK = 32;
    public static final int CELLS_PER_VISIT = 256;
    public static final int CHECKS_PER_TICK = 64;
    private static final int RECHECK_INTERVAL = 20;
    private final LinkedHashMap<Long, Cursor> chunks = new LinkedHashMap<>();
    private static final class Cursor {
        int section, cell;
        long nextPass;
        final BitSet affected = new BitSet();
    }
    private final Set<Long> executed = new HashSet<>();
    private long receiptTick = Long.MIN_VALUE, revision = -1;
    private long scannedPositions;
    private List<GravityFieldInstance> fields = List.of();
    // ChunkMap publishes a replacement visible map; this iterator is bounded per tick,
    // used only during publication discovery and released at completion/removal/unload.
    private Iterator<ChunkHolder> discovery;

    public long scannedPositions() { return scannedPositions; }
    private void currentTick(ServerLevel level) {
        if (receiptTick != level.getGameTime()) { executed.clear(); receiptTick = level.getGameTime(); }
    }
    public static boolean mayExecute(ServerLevel level, long chunk) {
        return !level.isDebug() && level.tickRateManager().runsNormally()
                && level.areEntitiesLoaded(chunk) && level.getChunkSource().isPositionTicking(chunk);
    }
    private static int index(ServerLevel level, BlockPos pos) {
        return (pos.getY() - level.getMinBuildHeight()) * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15);
    }
    private static boolean intersects(GravityFieldInstance field, ChunkPos pos, int lowY, int highY) {
        var bounds = field.influence().finiteBounds();
        if (bounds.isEmpty()) return true;
        var b = bounds.get();
        return b.maxX() >= pos.getMinBlockX() && b.minX() < pos.getMinBlockX()+16
                && b.maxZ() >= pos.getMinBlockZ() && b.minZ() < pos.getMinBlockZ()+16
                && b.maxY() >= lowY && b.minY() < highY;
    }
    private boolean relevant(ChunkPos pos, int lowY, int highY) {
        return fields.stream().anyMatch(f -> intersects(f,pos,lowY,highY));
    }
    public void affected(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return;
        chunks.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new Cursor()).affected.set(index(level,pos));
    }
    public void changed(ServerLevel level, BlockPos pos) {
        var cursor = chunks.get(ChunkPos.asLong(pos));
        if (cursor != null && !level.isOutsideBuildHeight(pos)) {
            cursor.affected.clear(index(level,pos));
            cursor.nextPass = 0;
        }
        var runtime = GravityFieldRuntime.getIfPresent(level);
        if (runtime != null && runtime.registry().size() != 0) loaded(level, new ChunkPos(pos));
    }
    public void nativeExecution(ServerLevel level, BlockPos pos) {
        var runtime = GravityFieldRuntime.getIfPresent(level);
        if ((runtime == null || runtime.registry().size() == 0) && chunks.isEmpty()) return;
        currentTick(level); executed.add(pos.asLong());
        var evaluation = FallingBlockStartIntegration.sample(level,pos);
        if (evaluation.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) return;
        if (evaluation.sample().contributions().isEmpty()) {
            var cursor = chunks.get(ChunkPos.asLong(pos));
            if (cursor != null) cursor.affected.clear(index(level,pos));
        } else affected(level,pos);
    }
    public void loaded(ServerLevel level, ChunkPos pos) {
        var runtime = GravityFieldRuntime.getIfPresent(level);
        if (runtime != null && runtime.registry().allInstances().stream()
                .anyMatch(f -> intersects(f,pos,level.getMinBuildHeight(),level.getMaxBuildHeight())))
            chunks.putIfAbsent(pos.toLong(), new Cursor());
    }
    public void unloaded(ChunkPos pos) { chunks.remove(pos.toLong()); }
    public void clear() { chunks.clear(); executed.clear(); fields=List.of(); discovery=null; revision=-1; receiptTick=Long.MIN_VALUE; }
    public int loadedChunkCount() { return chunks.size(); }

    public void tick(ServerLevel level) {
        var runtime = GravityFieldRuntime.getIfPresent(level);
        if (runtime == null) return;
        var registry = runtime.registry();
        if (registry.publicationRevision() != revision) {
            revision = registry.publicationRevision(); fields = registry.allInstances();
            discovery = fields.isEmpty() ? null : ((ChunkMapFallingAccess)level.getChunkSource().chunkMap)
                    .gravityengine$visibleChunks().iterator();
        }
        if (fields.isEmpty() && chunks.isEmpty()) return;
        currentTick(level);
        if (discovery != null) {
            for (int i=0; i<CHUNK_VISITS_PER_TICK && discovery.hasNext(); i++) {
                var holder = discovery.next();
                var pos = holder.getPos();
                if (relevant(pos, level.getMinBuildHeight(), level.getMaxBuildHeight())) {
                    var cursor = chunks.computeIfAbsent(pos.toLong(), ignored -> new Cursor());
                    cursor.nextPass = 0;
                }
            }
            if (!discovery.hasNext()) discovery=null;
        }
        int checks = 0;
        int visits = Math.min(CHUNK_VISITS_PER_TICK, chunks.size());
        for (int visit=0; visit<visits && checks<CHECKS_PER_TICK; visit++) {
            var entry=chunks.pollFirstEntry();
            if (entry==null) break;
            long key=entry.getKey(); var cursor=entry.getValue();
            var pos=new ChunkPos(key);
            var chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);
            if (chunk==null) continue;
            boolean active=relevant(pos,level.getMinBuildHeight(),level.getMaxBuildHeight());
            if (!active && cursor.affected.isEmpty()) continue;
            chunks.put(key,cursor);
            if (!mayExecute(level,key) || SableMovementCompatibility.isPlotPosition(level,pos.getWorldPosition())) continue;
            if (!active) {
                // No empty-world scan: visit only positions previously authorized by a field.
                for (int bit=cursor.affected.nextSetBit(0); bit>=0 && checks<CHECKS_PER_TICK;
                        bit=cursor.affected.nextSetBit(bit+1)) {
                    var p=new BlockPos(pos.getMinBlockX()+(bit&15),level.getMinBuildHeight()+(bit>>8),pos.getMinBlockZ()+((bit>>4)&15));
                    checks++;
                    checkCandidate(level,key,cursor,p,level.getBlockState(p));
                }
                continue;
            }
            if (level.getGameTime()<cursor.nextPass) continue;
            if (cursor.section>=chunk.getSectionsCount()) {
                cursor.section=0; cursor.cell=0; cursor.nextPass=level.getGameTime()+RECHECK_INTERVAL; continue;
            }
            int yBase=level.getSectionYFromSectionIndex(cursor.section)<<4;
            var section=chunk.getSection(cursor.section);
            if (cursor.cell==0 && (!relevant(pos,yBase,yBase+16)
                    && cursor.affected.nextSetBit(cursor.section*4096) / 4096 != cursor.section
                    || !section.maybeHas(s -> s.getBlock() instanceof FallingBlock))) {
                cursor.section++; continue;
            }
            for (int work=0; work<CELLS_PER_VISIT && checks<CHECKS_PER_TICK; work++) {
                int cell=cursor.cell++; scannedPositions++;
                int x=cell&15,z=(cell>>4)&15,y=cell>>8;
                var state=section.getBlockState(x,y,z);
                if (state.getBlock() instanceof FallingBlock) {
                    checks++;
                    checkCandidate(level,key,cursor,new BlockPos(pos.getMinBlockX()+x,yBase+y,pos.getMinBlockZ()+z),state);
                }
                if (cursor.cell==4096) { cursor.cell=0; cursor.section++; break; }
            }
        }
    }
    private void checkCandidate(ServerLevel level,long key,Cursor cursor,BlockPos pos,
                                net.minecraft.world.level.block.state.BlockState state) {
        int index=index(level,pos);
        if (!(state.getBlock() instanceof FallingBlock)) { cursor.affected.clear(index); return; }
        var evaluation=FallingBlockStartIntegration.sample(level,pos);
        if (evaluation.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) return;
        var sample=evaluation.sample();
        boolean present=!sample.contributions().isEmpty();
        if (present) cursor.affected.set(index);
        if (!cursor.affected.get(index) || !mayExecute(level,key) || executed.contains(pos.asLong())
                || level.getBlockTicks().hasScheduledTick(pos,state.getBlock())
                || level.getBlockTicks().willTickThisTick(pos,state.getBlock())) return;
        var current=level.getBlockState(pos);
        if (current!=state || !mayExecute(level,key)) return;
        if (!present) cursor.affected.clear(index);
        executed.add(pos.asLong()); current.tick(level,pos,level.random);
    }
}
