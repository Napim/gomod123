package me.napim.gomod.features;

import java.util.*;

/** Complete native TAB membership from fresh, opted-in spectator sources in this match. */
public final class SpectatorRosterState {
    public static final int MAX_MEMBERS = 128;
    public static final int UNKNOWN = -1, ABSENT = 0, PRESENT = 1;
    public static final long STALE_MS = 3500L;
    private final Map<UUID, Frame> sources = new HashMap<UUID, Frame>();
    private final Map<UUID, Entry> byUuid = new HashMap<UUID, Entry>();
    private final Map<String, Entry> byName = new HashMap<String, Entry>();
    private boolean dirty;
    private long revision;

    public static final class Entry {
        public final UUID uuid;
        public final String name;
        public final boolean spectator;
        public Entry(UUID uuid, String name, boolean spectator) {
            this.uuid=uuid; this.name=name; this.spectator=spectator;
        }
        public boolean valid() {
            return uuid!=null && name!=null && name.matches("[A-Za-z0-9_]{1,16}");
        }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Entry))return false;
            Entry entry=(Entry)other;
            return uuid.equals(entry.uuid)&&name.equalsIgnoreCase(entry.name)&&spectator==entry.spectator;
        }
        @Override public int hashCode(){return Objects.hash(uuid,name.toLowerCase(Locale.ROOT),spectator);}
    }
    private static final class Frame {
        final String session;
        final long sequence, at;
        final Map<UUID,Entry> entries;
        Frame(String session,long sequence,long at,Map<UUID,Entry> entries){
            this.session=session;this.sequence=sequence;this.at=at;this.entries=entries;
        }
    }
    public boolean replace(UUID source,String session,long sequence,List<Entry> entries,long now) {
        prune(now);
        if(source==null||session==null||!session.matches("[a-fA-F0-9-]{36}")||sequence<1
                ||entries==null||entries.isEmpty()||entries.size()>MAX_MEMBERS)return false;
        Map<UUID,Entry> members=new HashMap<UUID,Entry>();
        Set<String> names=new HashSet<String>();
        for(Entry entry:entries)if(entry==null||!entry.valid()||members.put(entry.uuid,entry)!=null
                ||!names.add(entry.name.toLowerCase(Locale.ROOT)))return false;
        Entry self=members.get(source);
        if(self==null||!self.spectator)return false;
        Frame previous=sources.get(source);
        if(previous!=null&&previous.session.equals(session)&&sequence<=previous.sequence)return false;
        if(previous==null&&sources.size()>=SpectatorFeedState.MAX_PLAYERS)return false;
        sources.put(source,new Frame(session,sequence,now,members));
        if(previous==null||!previous.entries.equals(members)){dirty=true;revision++;}
        return true;
    }
    /** A null UUID is allowed only for a synthetic retained victim, matched by raw server IGN. */
    public int presence(UUID uuid,String name,long now) {
        Entry entry=lookup(uuid,name,now);
        return entry!=null?PRESENT:sources.isEmpty()?UNKNOWN:ABSENT;
    }
    public boolean isSpectator(UUID uuid,String name,long now) {
        Entry entry=lookup(uuid,name,now);return entry!=null&&entry.spectator;
    }
    private Entry lookup(UUID uuid,String name,long now) {
        prune(now);rebuild();
        if(name==null)return null;
        Entry entry=uuid==null?byName.get(name.toLowerCase(Locale.ROOT)):byUuid.get(uuid);
        return entry!=null&&entry.name.equalsIgnoreCase(name)?entry:null;
    }
    private void rebuild() {
        if(!dirty)return;
        byUuid.clear();byName.clear();
        List<Frame> frames=new ArrayList<Frame>(sources.values());
        Collections.sort(frames,new Comparator<Frame>(){
            @Override public int compare(Frame a,Frame b){return Long.compare(a.at,b.at);}
        });
        for(Frame frame:frames)for(Entry entry:frame.entries.values()){
            Entry old=byUuid.get(entry.uuid);
            if(old!=null&&old.name.equalsIgnoreCase(entry.name)&&old.spectator&&!entry.spectator)
                entry=new Entry(entry.uuid,entry.name,true);
            byUuid.put(entry.uuid,entry);byName.put(entry.name.toLowerCase(Locale.ROOT),entry);
        }
        dirty=false;
    }
    public long revision(long now){prune(now);return revision;}
    public int size(long now){prune(now);rebuild();return byUuid.size();}
    public void remove(UUID source){if(sources.remove(source)!=null){dirty=true;revision++;}}
    public void keepSession(UUID source,String session){Frame old=sources.get(source);if(old!=null&&!old.session.equals(session))remove(source);}
    public void clear(){if(!sources.isEmpty()){sources.clear();dirty=true;revision++;}}
    private void prune(long now){
        Iterator<Frame> frames=sources.values().iterator();
        while(frames.hasNext()){
            Frame frame=frames.next();
            if(now<frame.at||now-frame.at>=STALE_MS){frames.remove();dirty=true;revision++;}
        }
    }
}
