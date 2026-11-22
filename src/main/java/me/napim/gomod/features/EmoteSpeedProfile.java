package me.napim.gomod.features;

import com.google.gson.*;
import java.util.*;

/** Piecewise animation rate in SOURCE time; crossing a boundary consumes the remaining real time. */
public final class EmoteSpeedProfile {
    public final double speed;
    public final List<Section> sections;
    public static final class Section {
        public final double from,to,speed;
        Section(double from,double to,double speed){this.from=from;this.to=to;this.speed=speed;}
    }
    public EmoteSpeedProfile(JsonObject json){
        speed=number(json,"speed",.1,10);List<Section> parsed=new ArrayList<>();
        JsonArray rows=json.getAsJsonArray("sections");if(rows==null||rows.size()>32)throw new IllegalArgumentException("Use up to 32 speed sections.");
        for(JsonElement value:rows){JsonObject row=value.getAsJsonObject();double from=number(row,"from",0,1),to=number(row,"to",0,1);
            if(to-from<.0001)throw new IllegalArgumentException("A section must end after it starts.");parsed.add(new Section(from,to,number(row,"speed",.1,10)));}
        parsed.sort(Comparator.comparingDouble(s->s.from));double end=0;
        for(Section section:parsed){if(section.from<end)throw new IllegalArgumentException("Speed sections cannot overlap.");end=section.to;}
        sections=Collections.unmodifiableList(parsed);
    }
    public static double number(JsonObject row,String key,double min,double max){
        JsonElement raw=row.get(key);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid "+key);
        double value=raw.getAsDouble();if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Invalid "+key);return value;
    }
    public double rate(double source,double duration){
        double point=duration>0?source/duration:0;for(Section section:sections)if(point>=section.from&&point<section.to)return section.speed;return speed;
    }
    public double advance(double source,double realDelta,double duration,boolean loop){
        double absolute=advanceAbsolute(source,realDelta,duration,loop);return loop&&duration>0?absolute%duration:absolute;
    }
    public double advanceAbsolute(double source,double realDelta,double duration,boolean loop){
        if(!Double.isFinite(source)||!Double.isFinite(realDelta)||realDelta<0||duration<=0)return source;
        source=Math.max(0,source);double base=loop?Math.floor(source/duration)*duration:0;if(loop)source%=duration;
        double cycle=playbackDuration(duration);
        for(int guard=0;guard<132&&realDelta>1e-10;guard++){
            if(source>=duration-1e-9){if(!loop)return duration;base+=duration;source=0;}
            if(loop&&source<1e-9&&realDelta>=cycle){long cycles=(long)Math.floor(realDelta/cycle);base+=cycles*duration;realDelta-=cycles*cycle;}
            double boundary=duration;
            for(Section section:sections){double from=section.from*duration,to=section.to*duration;if(from>source+1e-9)boundary=Math.min(boundary,from);if(to>source+1e-9)boundary=Math.min(boundary,to);}
            double rate=rate(source+1e-9,duration),needed=(boundary-source)/rate;
            if(realDelta<needed){source+=realDelta*rate;realDelta=0;}else{source=boundary;realDelta-=needed;}
        }
        return base+Math.min(source,duration);
    }
    public double playbackDuration(double duration){double total=duration/speed;for(Section section:sections)total+=(section.to-section.from)*duration*(1/section.speed-1/speed);return total;}
    public JsonObject json(){JsonObject result=new JsonObject();result.addProperty("speed",speed);JsonArray rows=new JsonArray();for(Section s:sections){JsonObject row=new JsonObject();row.addProperty("from",s.from);row.addProperty("to",s.to);row.addProperty("speed",s.speed);rows.add(row);}result.add("sections",rows);return result;}
    public static JsonObject defaults(){JsonObject value=new JsonObject();value.addProperty("speed",1);value.add("sections",new JsonArray());return value;}
}
