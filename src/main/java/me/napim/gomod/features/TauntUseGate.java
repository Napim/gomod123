package me.napim.gomod.features;

/** One command attempt per match; missing sidebars and deathmatch world rebuilds never re-arm it. */
public final class TauntUseGate {
    private String match=""; private boolean used;
    public void observe(String identity,boolean roundBoundary) {
        if(roundBoundary){used=false;match=identity==null?"":identity;return;}
        if(identity!=null && !identity.isEmpty() && !identity.equals(match)){
            if(!match.isEmpty())used=false;
            match=identity;
        }
    }
    public boolean claim(){if(used)return false;used=true;return true;}
    public void markUsed(){used=true;}
    public void clear(){match="";used=false;}
}
