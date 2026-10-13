package cn.couger.livingplayer;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Run with Android app_process: profile migration and identity isolation. No real credentials. */
public final class SavedDevicesCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  final Map<String,Object> data=new HashMap<>();
  final SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
   switch(m.getName()){case "putString":data.put((String)a[0],a[1]);return p;case "remove":data.remove(a[0]);return p;case "apply":return null;case "commit":return true;default:throw new UnsupportedOperationException(m.getName());}
  });
  SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
   switch(m.getName()){case "getString":return data.containsKey(a[0])?data.get(a[0]):a[1];case "contains":return data.containsKey(a[0]);case "edit":return editor;default:throw new UnsupportedOperationException(m.getName());}
  });
  String token=new String(new char[48]).replace('\0','a'),pin1=new String(new char[64]).replace('\0','b'),pin2=new String(new char[64]).replace('\0','c');
  DeviceClient phone=new DeviceClient(DeviceClient.code("192.168.1.2",token,pin1));
  DeviceClient box=new DeviceClient(DeviceClient.code("192.168.1.3",token,pin2));
  data.put("controllerCode",phone.code());SavedDevices store=new SavedDevices(prefs);store.migrate();check(store.list().length()==1,"legacy migration");store.migrate();check(store.list().length()==1,"idempotent migration");
  store.save(phone,null,"phone");store.rename(pin1,"手机");store.save(box,"盒子","box");check(store.list().length()==2,"two independent receivers");
  phone.host="192.168.1.8";store.save(phone,null,null);check(store.list().length()==2,"address change does not duplicate");check(store.find(pin1).getString("name").equals("手机"),"name preserved");check(store.find(pin1).getString("host").equals(phone.host),"address updated");
  SavedDevices reloaded=new SavedDevices(prefs);check(reloaded.find(pin2).getString("type").equals("box"),"box type persisted");check(reloaded.find(pin1).getString("type").equals("phone"),"phone type persisted");
  String previous=(String)data.get("controllerCode");box.host="192.168.1.9";check(reloaded.updateExisting(box),"background address refresh");check(previous.equals(data.get("controllerCode")),"shortcut must not switch main target");check(reloaded.find(pin2).optString("name").equals("盒子"),"shortcut preserves receiver name");
  reloaded.forget(pin1);check(reloaded.list().length()==1&&reloaded.find(pin2)!=null,"forget isolated");check(reloaded.find(pin1)==null,"forgotten profile absent");check(!reloaded.updateExisting(phone),"late shortcut result cannot restore forgotten profile");check(reloaded.find(pin1)==null,"forgotten profile stays absent");
  System.out.println("PASS: migration, persistence, identity isolation, background address refresh, no silent target switch, no resurrection after forget");
 }
}
