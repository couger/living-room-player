package cn.couger.livingplayer;

import org.json.*;
import java.util.ArrayDeque;

/** Explicit, short-lived inspection. Never persists input events to disk. */
final class KeyInspection {
 private static long until=0, sequence=0;
 private static final ArrayDeque<JSONObject> events=new ArrayDeque<>();
 static synchronized void start(){events.clear();until=System.currentTimeMillis()+60000;}
 static synchronized void stop(){until=0;events.clear();}
 static synchronized boolean active(){if(System.currentTimeMillis()>=until){stop();return false;}return true;}
 static synchronized boolean record(String key,String source){
  if(!active())return false;
  try{events.addLast(new JSONObject().put("sequence",++sequence).put("key",key).put("source",source).put("time",System.currentTimeMillis()).put("bindingCandidate",KeyBinding.accepts(key)));while(events.size()>32)events.removeFirst();}catch(JSONException ignored){}
  return true;
 }
 static synchronized JSONObject status()throws JSONException{
  boolean active=active();JSONArray list=new JSONArray();for(JSONObject event:events)list.put(new JSONObject(event.toString()));
  return new JSONObject().put("seconds",active?Math.max(0,(until-System.currentTimeMillis()+999)/1000):0).put("events",list);
 }
}
