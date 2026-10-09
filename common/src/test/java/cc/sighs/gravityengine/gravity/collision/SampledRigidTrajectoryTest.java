package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SampledRigidTrajectoryTest {
    private static SampledRigidTrajectory turn() {
        var segments = new ArrayList<RigidTrajectory>();
        var pose = new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY);
        for (int i=0;i<4;i++) {
            var segment = new RigidMotionSnapshot(pose, new Vec3d(i<2?.2:-.2,0,0),
                    new Vec3d(0,Math.PI/2,0), 4,2,1,.25);
            segments.add(segment); pose=segment.poseAt(1);
        }
        return new SampledRigidTrajectory(segments);
    }
    @Test void equalEndpointsDoNotEraseIntermediateRevolution() {
        var trajectory=turn(); var point=new Vec3d(3,0,0);
        assertTrue(trajectory.poseAt(0).transformPoint(point).distance(trajectory.poseAt(1).transformPoint(point))<1e-12);
        assertTrue(trajectory.poseAt(.5).transformPoint(point).distance(point)>5);
        var body=new OrientedBox(point,.1,OrthonormalFrame3d.IDENTITY);
        var observer=new Vec3d(1,2,-1);
        for (double lo:new double[]{0,.13,.25,.49}) {
            double hi=.93;
            var bounds=trajectory.envelope(body,lo,hi,observer).enclosingAabb();
            for (int i=0;i<=200;i++) {
                double f=i/200.0,t=lo+f*(hi-lo);
                var p=trajectory.poseAt(t).transformPoint(point).subtract(observer.multiply(f));
                assertTrue(p.x()>=bounds.minX()-1e-10 && p.x()<=bounds.maxX()+1e-10);
                assertTrue(p.y()>=bounds.minY()-1e-10 && p.y()<=bounds.maxY()+1e-10);
                assertTrue(p.z()>=bounds.minZ()-1e-10 && p.z()<=bounds.maxZ()+1e-10);
            }
        }
    }
    @Test void pointVelocityAndSupportChordsFollowCapturedSamples() {
        var trajectory=turn(); var point=new Vec3d(3,0,0);
        for (double t:new double[]{.1,.3,.6,.9}) {
            double h=1e-6;
            var numerical=trajectory.poseAt(t+h).transformPoint(point).subtract(trajectory.poseAt(t-h).transformPoint(point)).divide(2*h);
            assertTrue(numerical.distance(trajectory.velocityAt(trajectory.poseAt(t).transformPoint(point),t))<1e-7);
        }
        var support=new SupportMotionTrajectory(trajectory,point);
        int count=support.requiredSegments(.001);
        assertEquals(0,count%4);
        for(int i=0;i<count;i++) {
            double a=(double)i/count,b=(double)(i+1)/count;
            var chord=support.positionAt(a).add(support.positionAt(b)).multiply(.5);
            assertTrue(chord.distance(support.positionAt((a+b)*.5))<=.001);
        }
    }
    @Test void missingAndDiscontinuousEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new SampledRigidTrajectory(List.of()));
        var first=turn().segments().get(0);
        assertThrows(CollisionSceneCoverageException.class,()->new SampledRigidTrajectory(List.of(first,first)));
        assertThrows(CollisionSceneCoverageException.class,()->turn().poseAt(1.01));
    }
}
