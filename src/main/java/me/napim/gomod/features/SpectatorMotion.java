package me.napim.gomod.features;

import java.util.ArrayDeque;
import java.util.Deque;

/** Four transient samples for smooth rendering between bounded relay updates. */
public final class SpectatorMotion {
    public static final long DELAY_MS = 300L;
    private static final long EXTRAPOLATE_MS = 150L;
    private final Deque<Point> points = new ArrayDeque<Point>();
    public static final class Pose { public double x,y,z; public float yaw,pitch; public SpectatorVisualState visual; }
    private static final class Point {
        final SpectatorFeedState.Sample sample; final long at;
        Point(SpectatorFeedState.Sample sample,long at){this.sample=sample;this.at=at;}
    }
    public void accept(SpectatorFeedState.Sample sample,long now) {
        Point previous=points.peekLast();
        if(previous!=null && previous.sample==sample)return;
        if(previous!=null && (now<=previous.at || now-previous.at>=SpectatorFeedState.STALE_MS
                || distance(previous.sample,sample)>256D))points.clear();
        points.addLast(new Point(sample,now));
        while(points.size()>4)points.removeFirst();
    }
    public void sample(long now,Pose out) {
        if(points.isEmpty())return;
        long renderAt=now-DELAY_MS;
        Point first=points.peekFirst(),last=points.peekLast(),left=first;
        if(renderAt<=first.at || points.size()==1){blend(first.sample,first.sample,0D,out);return;}
        for(Point right:points){
            if(right==first)continue;
            if(renderAt<=right.at){blend(left.sample,right.sample,(double)(renderAt-left.at)/(right.at-left.at),out);return;}
            left=right;
        }
        Point previous=null;
        for(Point point:points){if(point==last)break;previous=point;}
        double extra=Math.min(EXTRAPOLATE_MS,Math.max(0L,renderAt-last.at));
        blend(previous.sample,last.sample,1D+extra/(last.at-previous.at),out);
    }
    private static double distance(SpectatorFeedState.Sample a,SpectatorFeedState.Sample b){
        double x=a.x-b.x,y=a.y-b.y,z=a.z-b.z;return x*x+y*y+z*z;
    }
    private static void blend(SpectatorFeedState.Sample a,SpectatorFeedState.Sample b,double t,Pose out){
        out.visual=t<1D?a.visual:b.visual;
        out.x=a.x+(b.x-a.x)*t;out.y=a.y+(b.y-a.y)*t;out.z=a.z+(b.z-a.z)*t;
        double angle=(b.yaw-a.yaw)%360D;if(angle>180D)angle-=360D;if(angle< -180D)angle+=360D;
        out.yaw=(float)(a.yaw+angle*t);out.pitch=(float)Math.max(-90D,Math.min(90D,a.pitch+(b.pitch-a.pitch)*t));
    }
}
