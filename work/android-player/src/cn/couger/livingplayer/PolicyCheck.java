package cn.couger.livingplayer;
import org.json.JSONObject;

public final class PolicyCheck {
 static int count=0;
 static void check(String expected,boolean powered,int level,int temp,JSONObject config)throws Exception{String actual=ReceiverServer.decide(powered,level,temp,config);if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);count++;}
 public static void main(String[] args)throws Exception{
  JSONObject c=ReceiverServer.defaults();
  check("charging",true,60,350,c);
  check("target",true,80,350,c);
  check("target",true,75,350,c);
  check("charging",true,60,350,c);
  check("temperature",true,70,420,c);
  check("temperature",true,70,400,c);
  check("charging",true,70,380,c);
  check("temperature",true,80,420,c);
  check("target",true,80,380,c);
  check("unplugged",false,80,420,c);
  check("charging",true,70,350,c);
  check("target",true,80,350,c);
  c.put("protect",false);check("system",true,80,350,c);
  System.out.println("Policy checks passed: "+count+" transitions (level, thermal, unplug, disabled)");
 }
}
