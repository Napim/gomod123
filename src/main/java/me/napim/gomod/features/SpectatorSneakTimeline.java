package me.napim.gomod.features;

import java.util.ArrayDeque;
import java.util.Deque;

/** Short-lived tick poses on the same delayed timeline as spectator movement. */
public final class SpectatorSneakTimeline {
    private final Deque<Edge> pending = new ArrayDeque<Edge>();
    private String stream = "";
    private long tick = -1L, sequence = -1L, receivedAt, lastEdgeAt;
    private boolean observed, rendered;
    private static final class Edge {
        final long at; final boolean state;
        Edge(long at,boolean state){this.at=at;this.state=state;}
    }

    public void accept(SpectatorVisualState visual,long now) {
        if(visual==null){clear();return;}
        if(sequence<0L || !stream.equals(visual.stream) || now<receivedAt
                || now-receivedAt>=SpectatorFeedState.STALE_MS){baseline(visual,now);return;}
        if(visual.poseTick>=1L){
            long elapsed=visual.poseTick-tick;
            if(tick<1L || elapsed<0L || elapsed>16L){baseline(visual,now);return;}
            if(elapsed==0L)return;
            for(int age=(int)elapsed-1;age>=0;age--){
                boolean state=((visual.sneakHistory>>>age)&1)!=0;
                if(state!=observed)enqueue(now+SpectatorMotion.DELAY_MS-age*50L,state);
                observed=state;
            }
        }else{
            long edges=visual.sneakSequence-sequence;
            if(edges<0L || edges>8L){baseline(visual,now);return;}
            // Older clients retain the bounded counter fallback, without inventing timing.
            if(edges==0L && visual.sneaking!=observed)edges=1L;
            for(int i=0;i<edges;i++){
                observed=!observed;
                enqueue(now+SpectatorMotion.DELAY_MS+i*50L,observed);
            }
            if(observed!=visual.sneaking){baseline(visual,now);return;}
        }
        stream=visual.stream;tick=visual.poseTick;sequence=visual.sneakSequence;receivedAt=now;
    }
    private void enqueue(long at,boolean state){
        at=Math.max(at,lastEdgeAt+50L);lastEdgeAt=at;
        if(pending.size()>=32){pending.clear();rendered=state;}
        else pending.addLast(new Edge(at,state));
    }
    private void baseline(SpectatorVisualState visual,long now){
        pending.clear();stream=visual.stream;tick=visual.poseTick;sequence=visual.sneakSequence;
        observed=rendered=visual.sneaking;receivedAt=now;lastEdgeAt=0L;
    }
    public boolean sample(long now){
        while(!pending.isEmpty() && pending.peekFirst().at<=now)rendered=pending.removeFirst().state;
        return rendered;
    }
    public void clear(){pending.clear();stream="";tick=sequence=-1L;receivedAt=lastEdgeAt=0L;observed=rendered=false;}
    public int pendingCount(){return pending.size();}
}
