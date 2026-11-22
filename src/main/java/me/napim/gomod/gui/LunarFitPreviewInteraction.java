package me.napim.gomod.gui;

import java.util.*;

/** Screen-local rotation and clipped pointer capture for individual shared Fit messages. */
final class LunarFitPreviewInteraction {
    private final LinkedHashMap<String,Float> angles=new LinkedHashMap<String,Float>(16,.75F,true);
    private final List<Hit> hits=new ArrayList<Hit>();
    private UUID owner;
    private String peer="",dragging;
    private int lastX;
    private static String peer(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
    void beginFrame(UUID owner,String peer){
        if(!Objects.equals(this.owner,owner)){clear();this.owner=owner;}
        String target=peer(peer);if(!target.equals(this.peer)){release();this.peer=target;}
        hits.clear();
    }
    void add(String key,int left,int top,int right,int bottom,int clipLeft,int clipTop,int clipRight,int clipBottom){
        left=Math.max(left,clipLeft);top=Math.max(top,clipTop);right=Math.min(right,clipRight);bottom=Math.min(bottom,clipBottom);
        if(owner!=null&&!peer.isEmpty()&&key!=null&&right>left&&bottom>top)hits.add(new Hit(key,left,top,right,bottom));
    }
    void endFrame(){
        if(dragging!=null){for(Hit hit:hits)if(hit.key.equals(dragging))return;release();}
    }
    float yaw(String key,long now){Float angle=angles.get(key);return angle!=null?angle:(now%14000L)*360F/14000F;}
    boolean start(UUID owner,String peer,int x,int y,long now){
        if(!context(owner,peer))return false;
        for(Hit hit:hits)if(x>=hit.left&&x<hit.right&&y>=hit.top&&y<hit.bottom){
            float angle=yaw(hit.key,now);dragging=hit.key;lastX=x;angles.put(hit.key,angle);
            while(angles.size()>128)angles.remove(angles.keySet().iterator().next());return true;
        }
        return false;
    }
    boolean move(UUID owner,String peer,int x){
        if(!context(owner,peer)||dragging==null){release();return false;}
        float angle=(angles.get(dragging)+((long)x-lastX)*.8F)%360F;
        angles.put(dragging,angle<0?angle+360F:angle);lastX=x;return true;
    }
    private boolean context(UUID owner,String peer){return owner!=null&&owner.equals(this.owner)&&!this.peer.isEmpty()&&this.peer.equals(peer(peer));}
    void release(){dragging=null;}
    void clear(){release();hits.clear();angles.clear();owner=null;peer="";}
    private static final class Hit {
        final String key;final int left,top,right,bottom;
        Hit(String key,int left,int top,int right,int bottom){this.key=key;this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
    }
}
