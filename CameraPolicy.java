package com.example.steadycam;

import java.util.Arrays;

/** Android-free camera policy, covered by unit tests. */
public final class CameraPolicy {
    private CameraPolicy() {}
    // Camera HALs commonly report 16,666,667 ns for 60 fps. Never use integer division.
    public static boolean supportsRate(long durationNs, int fps) {
        return durationNs <= 0 || durationNs <= 1_000_000_000.0 / fps * 1.02;
    }
    public static int bestRange(int[][] ranges, int fps) {
        int best = -1, lower = -1;
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i][0] == fps && ranges[i][1] == fps) return i;
            if (ranges[i][1] == fps && ranges[i][0] <= fps && ranges[i][0] > lower) {
                best = i; lower = ranges[i][0];
            }
        }
        return best;
    }
    public static int mediaRotation(int sensor, int displayDegrees, boolean front) {
        return (sensor + (front ? displayDegrees : -displayDegrees) + 360) % 360;
    }
    public static int previewRotation(int sensor, int displayDegrees, boolean front) {
        return (sensor - displayDegrees * (front ? 1 : -1) + 360) % 360;
    }
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    public static double[] sensorPoint(double x, double y, int rotation, boolean mirror) {
        if (mirror) x = 1 - x;
        double sx = x, sy = y;
        if (rotation == 90) { sx = y; sy = 1 - x; }
        else if (rotation == 180) { sx = 1 - x; sy = 1 - y; }
        else if (rotation == 270) { sx = 1 - y; sy = x; }
        return new double[]{Math.max(0, Math.min(1, sx)), Math.max(0, Math.min(1, sy))};
    }
    public static final class Stats {
        public final int frames, longIntervals, duplicateTimestamps;
        public final double fps, seconds;
        Stats(int n, int gaps, int duplicates, double rate, double duration) {
            frames=n; longIntervals=gaps; duplicateTimestamps=duplicates; fps=rate; seconds=duration;
        }
    }
    /** Uses presentation timestamps, so encoder B-frame packet ordering cannot corrupt FPS. */
    public static Stats analyze(long[] ptsUs, int targetFps) {
        if (ptsUs.length < 2) return new Stats(ptsUs.length,0,0,0,0);
        long[] p = ptsUs.clone(); Arrays.sort(p);
        int n=1, duplicates=0;
        for (int i=1;i<p.length;i++) {
            if (p[i] == p[n-1]) duplicates++;
            else p[n++]=p[i];
        }
        if (n<2) return new Stats(n,0,duplicates,0,0);
        long span=p[n-1]-p[0]; int gaps=0;
        double expected=1_000_000.0 / targetFps;
        for (int i=1;i<n;i++) if (p[i]-p[i-1] > expected*1.8) gaps++;
        return new Stats(n,gaps,duplicates,(n-1)*1_000_000.0/span,span/1_000_000.0);
    }
}
