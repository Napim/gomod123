package me.napim.gomod.features;

import java.util.*;

/** Occurrence-aware duplicate suppression; identical real messages remain distinct. */
public final class GameChatLedger {
    private static final long TTL = 4000;
    private final Deque<Record> records = new ArrayDeque<Record>();
    private final LinkedHashMap<String, Long> ids = new LinkedHashMap<String, Long>();
    private static final class Record {
        final String key; final long at; final int chatId; final boolean natural; boolean paired;
        Record(String key,long at,int chatId,boolean natural){this.key=key;this.at=at;this.chatId=chatId;this.natural=natural;}
    }
    /** Returns the optional relay line ID to delete when the actual server copy arrives later. */
    public int natural(String key,long now) {
        prune(now);
        for (Record record:records) if (!record.natural && !record.paired && record.key.equals(key)) {
            record.paired=true; return record.chatId;
        }
        records.addLast(new Record(key,now,0,true)); bound(); return 0;
    }
    public boolean relay(String id,String key,int chatId,long now) {
        prune(now);
        if(ids.containsKey(id)) return false;
        ids.put(id,now);
        for (Record record:records) if(record.natural && !record.paired && record.key.equals(key)) {
            record.paired=true; bound(); return false;
        }
        records.addLast(new Record(key,now,chatId,false)); bound(); return true;
    }
    public void clear(){records.clear();ids.clear();}
    private void prune(long now) {
        while(!records.isEmpty() && (now<records.peekFirst().at || now-records.peekFirst().at>TTL)) records.removeFirst();
        Iterator<Long> iterator=ids.values().iterator();
        while(iterator.hasNext()){long time=iterator.next();if(now<time || now-time>15000)iterator.remove();}
    }
    private void bound(){while(records.size()>256)records.removeFirst();while(ids.size()>512)ids.remove(ids.keySet().iterator().next());}
}
