package me.napim.gomod.features;

import com.google.gson.*;
import java.util.*;

/** Six-part Minecraft pose animation, authored in degrees with linear or smooth interpolation. */
public final class CustomEmoteClip {
    public static final String[] BONES={"head","body","rightArm","leftArm","rightLeg","leftLeg"};
    public final String name;public final double duration;public final boolean loop,smooth;
    private final double[] times;private final float[][] poses;
    public CustomEmoteClip(JsonObject value){
        name=value.get("name").getAsString().replaceAll("[\\p{Cntrl}\u00a7]","").trim();if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("Name the emote (up to 40 characters).");
        duration=EmoteSpeedProfile.number(value,"duration",.5,60);loop=value.get("loop").getAsBoolean();smooth=value.get("smooth").getAsBoolean();
        JsonArray frames=value.getAsJsonArray("frames");if(frames==null||frames.size()<2||frames.size()>64)throw new IllegalArgumentException("Use 2–64 keyframes.");
        times=new double[frames.size()];poses=new float[frames.size()][18];double previous=-1;
        for(int i=0;i<frames.size();i++){JsonObject frame=frames.get(i).getAsJsonObject();times[i]=EmoteSpeedProfile.number(frame,"time",0,duration);if(times[i]<=previous)throw new IllegalArgumentException("Keyframe times must increase.");previous=times[i];
            JsonObject pose=frame.getAsJsonObject("pose");if(pose==null||pose.entrySet().size()!=BONES.length)throw new IllegalArgumentException("Every keyframe needs all six body parts.");
            for(int bone=0;bone<BONES.length;bone++){JsonArray angles=pose.getAsJsonArray(BONES[bone]);if(angles==null||angles.size()!=3)throw new IllegalArgumentException("Use three angles per body part.");for(int axis=0;axis<3;axis++){JsonElement raw=angles.get(axis);if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid pose angle.");double angle=raw.getAsDouble();if(!Double.isFinite(angle)||angle< -180||angle>180)throw new IllegalArgumentException("Angles must be between -180 and 180 degrees.");poses[i][bone*3+axis]=(float)angle;}}
        }
        if(times[0]!=0||Math.abs(times[times.length-1]-duration)>.0001)throw new IllegalArgumentException("Keep keyframes at the start and end of the emote.");
    }
    public float[] sample(double time){
        return sample(time,loop);
    }
    public float[] sample(double time,boolean looping){
        time=looping?Math.max(0,time)%duration:Math.max(0,Math.min(duration,time));int end=1;while(end<times.length-1&&times[end]<time)end++;
        float amount=(float)((time-times[end-1])/(times[end]-times[end-1]));if(smooth)amount=amount*amount*(3-2*amount);
        float[] pose=new float[18];for(int i=0;i<18;i++)pose[i]=(float)Math.toRadians(poses[end-1][i]+(poses[end][i]-poses[end-1][i])*amount);return pose;
    }
    public JsonObject json(){JsonObject value=new JsonObject();value.addProperty("name",name);value.addProperty("duration",duration);value.addProperty("loop",loop);value.addProperty("smooth",smooth);JsonArray frames=new JsonArray();for(int i=0;i<times.length;i++){JsonObject frame=new JsonObject(),pose=new JsonObject();frame.addProperty("time",times[i]);for(int bone=0;bone<6;bone++){JsonArray angles=new JsonArray();for(int axis=0;axis<3;axis++)angles.add(new JsonPrimitive(poses[i][bone*3+axis]));pose.add(BONES[bone],angles);}frame.add("pose",pose);frames.add(frame);}value.add("frames",frames);return value;}
    public static JsonObject defaults(){JsonObject value=new JsonObject();value.addProperty("name","My emote");value.addProperty("duration",3);value.addProperty("loop",true);value.addProperty("smooth",true);JsonArray frames=new JsonArray();for(int i=0;i<2;i++){JsonObject frame=new JsonObject(),pose=new JsonObject();frame.addProperty("time",i*3);for(String bone:BONES){JsonArray angles=new JsonArray();for(int axis=0;axis<3;axis++)angles.add(new JsonPrimitive(0));pose.add(bone,angles);}frame.add("pose",pose);frames.add(frame);}value.add("frames",frames);return value;}
}
