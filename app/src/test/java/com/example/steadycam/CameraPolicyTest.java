package com.example.steadycam;
import org.junit.Test;
import static org.junit.Assert.*;
public class CameraPolicyTest {
 @Test public void real60FrameDurationSurvivesNanosecondRounding(){assertTrue(CameraPolicy.supportsRate(16_666_667,60));}
 @Test public void a30FpsStreamCannotBeAdvertisedAs60(){assertFalse(CameraPolicy.supportsRate(33_333_333,60));}
 @Test public void unknownDurationDoesNotHideHalAdvertisedMode(){assertTrue(CameraPolicy.supportsRate(0,60));}
 @Test public void fixedRangeIsPreferredOverAutomatic(){assertEquals(1,CameraPolicy.bestRange(new int[][]{{30,60},{60,60}},60));}
 @Test public void variable60IsSelectable(){assertEquals(1,CameraPolicy.bestRange(new int[][]{{15,60},{30,60}},60));}
 @Test public void slowMotionRangeDoesNotInventNormal60(){assertEquals(-1,CameraPolicy.bestRange(new int[][]{{30,30},{30,120},{120,120}},60));}
 @Test public void thirtyIsNotInventedFrom24Range(){assertEquals(-1,CameraPolicy.bestRange(new int[][]{{24,24}},30));}
 @Test public void videoOrientationCoversFrontBackAndLandscape(){assertEquals(90,CameraPolicy.mediaRotation(90,0,false));assertEquals(0,CameraPolicy.mediaRotation(90,90,false));assertEquals(180,CameraPolicy.mediaRotation(90,270,false));assertEquals(0,CameraPolicy.mediaRotation(270,90,true));}
 @Test public void frontFocusCoordinatesUndoPreviewMirror(){assertArrayEquals(new double[]{.25,.75},CameraPolicy.sensorPoint(.75,.25,90,true),1e-8);}
 @Test public void presentationTimestampsAreMeasuredNotNominalFps(){long[] pts=new long[61];for(int i=0;i<pts.length;i++)pts[i]=Math.round(i*1_000_000.0/30);CameraPolicy.Stats s=CameraPolicy.analyze(pts,60);assertEquals(30,s.fps,.01);assertEquals(60,s.longIntervals);}
 @Test public void bFrameOrderAndDuplicatePtsDoNotInflateFps(){CameraPolicy.Stats s=CameraPolicy.analyze(new long[]{0,66667,33333,100000,100000},30);assertEquals(30,s.fps,.01);assertEquals(4,s.frames);assertEquals(1,s.duplicateTimestamps);assertEquals(0,s.longIntervals);}
 @Test public void longFrameGapIsFlagged(){CameraPolicy.Stats s=CameraPolicy.analyze(new long[]{0,16667,33333,83333,100000},60);assertEquals(1,s.longIntervals);}
 @Test public void tooShortClipHasNoInventedRate(){assertEquals(0,CameraPolicy.analyze(new long[]{100},60).fps,0);}
}
