package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SupportDepartureTest {
    private static final Vec3d UP = new Vec3d(-.3751410896934297, .9051085866106957, .20011898777281173);
    private static final GravityFrame FRAME = GravityFrame.fromDown(UP.negate(), .08);
    private static final CharacterCapsule BODY = new CharacterCapsule(
            new Vec3d(.5, 1 + .3 + .6 * UP.y(), .5), UP, .3, .6);
    private static final BlockObstacle FLOOR = SceneFixtures.block(new CellPos(0, 0, 0));

    @Test void loggedFloorTangentIsNotDepartureDespitePositiveGravityUp() {
        var delta = new Vec3d(-.006540673024304056, 0, -.003369180763755253);
        assertTrue(delta.dot(UP) > .0017);
        var support = query(List.of(FLOOR), List.of());
        assertFalse(support.candidates().isEmpty());
        assertFalse(support.departsStationarySupport(delta));
        assertFalse(support.departsStationarySupport(delta.add(0, 1e-8, 0)));
        assertFalse(support.departsStationarySupport(delta.add(0, GravityGroundProbe.PROBE_DISTANCE / 2, 0)));
        assertFalse(support.departsStationarySupport(new Vec3d(0, -.1, 0)));
        assertTrue(support.departsStationarySupport(UP.multiply(.42)));
    }

    @Test void missingOrIndeterminateContactCannotAuthorizeImpulse() {
        assertFalse(query(List.of(), List.of()).departsStationarySupport(UP));
        assertFalse(new FeetSupportQuery.Result(query(List.of(FLOOR), List.of()).candidates(), true)
                .departsStationarySupport(UP));
    }

    @Test void RemainingSupportingConstraintPreventsDeparture() {
        var floor = query(List.of(FLOOR), List.of()).selected();
        var sloped = new FeetSupportQuery.Candidate(floor.identity(), FLOOR,
                new Vec3d(-1, 1, 0).normalized(), floor.point(), 0, .5, 0);
        var support = new FeetSupportQuery.Result(List.of(floor, sloped), false);
        assertFalse(support.departsStationarySupport(new Vec3d(.2, .1, 0)));
        assertTrue(support.departsStationarySupport(new Vec3d(0, .1, 0)));
    }

    @Test void MovingSupportNeedsMatchedDisplacementRatherThanAnAssumedTick() {
        var dynamic = SceneFixtures.dynamicObstacle(1, 0, 0, new Vec3d(.5, .5, .5), .5,
                new Vec3d(0, .2, 0), Vec3d.ZERO, 40, 1, 1);
        var support = query(List.of(), List.of(dynamic));
        assertFalse(support.candidates().isEmpty());
        assertFalse(support.departsStationarySupport(new Vec3d(0, .2, 0)));
        assertFalse(support.departsStationarySupport(UP.multiply(.42)));
    }

    private static FeetSupportQuery.Result query(List<BlockObstacle> blocks,
            List<DynamicCollisionObstacleSnapshot> dynamics) {
        return FeetSupportQuery.query(BODY, FRAME, SceneFixtures.scene(40, 1, 1, blocks, dynamics),
                0, 0, null, new ObbQueryContext());
    }
}
