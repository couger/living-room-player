package cn.couger.livingplayer;

import android.os.Process;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Root backend. No account APIs, remote shell, microphone or audio capture. */
public final class ReceiverServer {
 public static final String BASE="/data/adb/living-player";
 static final File ROOT=new File(BASE), CONFIG=new File(ROOT,"config.json"), OWNED=new File(ROOT,"charge-owned");
 static final String VOTE="/sys/kernel/debug/pmic-votable/CHG_DISABLE/";
 static final boolean BOX=ReceiverRuntime.box();
 static volatile JSONObject config;
 static volatile boolean chargeHold=false, thermalHold=false;
 static volatile String reason="starting", fault="";
 static volatile long lastTick=0;
 static final AtomicBoolean streaming=new AtomicBoolean(false);
 static volatile java.lang.Process capture;
 static volatile long videoLease=0;
 static volatile long remoteLeaseUntil=0, localInputAt=0, captureKeyUntil=0;
 static volatile String capturedKey="";
 static volatile boolean remoteCueActive=false;
 static String remoteCuePid="";
 static long nextCueCheck=0, nextCueRetry=0;
 static volatile String captureSession="";
 static final AtomicLong videoBytes=new AtomicLong();
 static String token, fingerprint;
 static volatile long pairingUntil=0;
 static int originalTimeout=-1, originalStay=-1;
 static final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,6,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(12));
 static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
 static String read(String path){try{byte[] b=new byte[16384];try(InputStream in=new FileInputStream(path)){int n=in.read(b);return n<0?"":new String(b,0,n,"UTF-8").trim();}}catch(Exception e){return "";}}
 static int number(String path,int fallback){try{return Integer.parseInt(read(path));}catch(Exception e){return fallback;}}
 static void write(String path,String text)throws IOException{try(FileOutputStream f=new FileOutputStream(path)){f.write(text.getBytes("UTF-8"));}}
 static void atomic(File f,String text)throws IOException{File temp=new File(f.getPath()+".new");write(temp.getPath(),text);if(!temp.renameTo(f))throw new IOException("save failed");}
 static String command(String... args)throws Exception{
  java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();
  Thread reader=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))>0)synchronized(out){if(out.size()<65536)out.write(b,0,Math.min(n,65536-out.size()));}}catch(IOException ignored){}},"command-output");reader.setDaemon(true);reader.start();
  try{if(!p.waitFor(8,TimeUnit.SECONDS))throw new IOException("command timeout");reader.join(1000);if(p.exitValue()!=0)throw new IOException("command failed");synchronized(out){return out.toString("UTF-8").trim();}}
  finally{if(p.isAlive())p.destroyForcibly();try{p.getInputStream().close();}catch(IOException ignored){}}
 }
 static JSONObject defaults()throws JSONException{return new JSONObject("{\"enabled\":true,\"protect\":true,\"low\":60,\"high\":80,\"hot\":420,\"cool\":380,\"idle\":120,\"webEnabled\":false,\"discoveryEnabled\":true,\"legacyAllowed\":false,\"playerPackage\":\"\",\"oneTapKey\":\"\"}");}
 static void init()throws Exception{
  ROOT.mkdirs();if(!CONFIG.exists())atomic(CONFIG,defaults().toString());
  else {JSONObject c=new JSONObject(read(CONFIG.getPath()));boolean changed=false;for(String k:new String[]{"webEnabled","discoveryEnabled","legacyAllowed"})if(!c.has(k)){c.put(k,true);changed=true;}for(String k:new String[]{"playerPackage","oneTapKey"})if(!c.has(k)){c.put(k,"");changed=true;}if(changed)atomic(CONFIG,c.toString());}
  File secret=new File(ROOT,"secret.json"), key=new File(ROOT,"tls.bks");
  if(!secret.exists()||!key.exists()){
   byte[] random=new byte[24];new SecureRandom().nextBytes(random);String t=CertificateMaker.hex(random);
   char[] password=t.toCharArray();KeyStore ks=CertificateMaker.create(password);
   try(OutputStream out=new FileOutputStream(key)){ks.store(out,password);}
   String fp=CertificateMaker.hex(MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("device").getEncoded()));
   atomic(secret,new JSONObject().put("token",t).put("fingerprint",fp).toString());
  }
  command("chmod","700",BASE);command("chmod","600",secret.getPath(),key.getPath(),CONFIG.getPath());
  try{restoreDisplay();}catch(Exception ignored){}
 }
 static synchronized void restoreCharge()throws IOException{
  if(OWNED.exists()){
   write(VOTE+"force_active","0");write(VOTE+"force_val","0");
   if(number(VOTE+"force_active",-1)!=0)throw new IOException("charge restore failed");
   OWNED.delete();
  }
 }
 static synchronized void holdCharge()throws IOException{
  if(!OWNED.exists()){
   if(number(VOTE+"force_active",-1)!=0)throw new IOException("charge override unavailable or already used");
   atomic(OWNED,"living-player");
  }
  write(VOTE+"force_val","1");write(VOTE+"force_active","1");
  if(number(VOTE+"force_active",-1)!=1)throw new IOException("charge pause failed");
 }
 // Pure state transition so charge/temperature hysteresis can be checked separately.
 static String decide(boolean powered,int level,int temp,JSONObject c)throws Exception{
  if(!powered){chargeHold=false;thermalHold=false;return "unplugged";}
  if(!c.optBoolean("protect",true)){chargeHold=false;thermalHold=false;return "system";}
  if(temp>=c.getInt("hot"))thermalHold=true;else if(temp<=c.getInt("cool"))thermalHold=false;
  if(level>=c.getInt("high"))chargeHold=true;else if(level<=c.getInt("low"))chargeHold=false;
  return thermalHold?"temperature":chargeHold?"target":"charging";
 }
 static synchronized void tick(){
  try{
   if(BOX){chargeHold=false;thermalHold=false;reason="external_power";fault="";lastTick=System.currentTimeMillis();return;}
   int level=number("/sys/class/power_supply/battery/capacity",-1),temp=number("/sys/class/power_supply/battery/temp",-1),usb=number("/sys/class/power_supply/usb/online",-1);
   if(level<0||level>100||temp<0||temp>800||usb<0)throw new IOException("battery sensor unavailable");
   reason=decide(usb==1,level,temp,config);
   if(reason.equals("temperature")||reason.equals("target"))holdCharge();else restoreCharge();
   fault="";
  }catch(Exception e){fault=e.getMessage();reason="fault";try{restoreCharge();}catch(Exception ignored){fault+="; restore failed";}}
  lastTick=System.currentTimeMillis();
 }
 static synchronized void idleSettings()throws Exception{
  if(BOX)return;
  int ms=config.getInt("idle")*1000;
  command("settings","put","system","screen_off_timeout",String.valueOf(ms));
  command("settings","put","global","stay_on_while_plugged_in","0");
 }
 static synchronized void restoreSettings(){
  if(BOX)return;
  try{if(originalTimeout>0&&numberSetting("system","screen_off_timeout")==config.optInt("idle",120)*1000)command("settings","put","system","screen_off_timeout",String.valueOf(originalTimeout));
   if(originalStay>=0&&numberSetting("global","stay_on_while_plugged_in")==0)command("settings","put","global","stay_on_while_plugged_in",String.valueOf(originalStay));
  }catch(Exception ignored){}
 }
 static int numberSetting(String table,String name){try{return Integer.parseInt(command("settings","get",table,name));}catch(Exception e){return -1;}}
 static String screen(){try{String s=command("dumpsys","power");for(String l:s.split("\n"))if(l.trim().startsWith("mWakefulness="))return l.trim().split("=")[1];}catch(Exception ignored){}return "Unknown";}
 static JSONArray addresses(){JSONArray a=new JSONArray();try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){NetworkInterface ni=interfaces.nextElement();if(!ni.getName().startsWith("wlan")&&!ni.getName().startsWith("eth"))continue;Enumeration<InetAddress> ips=ni.getInetAddresses();while(ips.hasMoreElements()){InetAddress ip=ips.nextElement();if(ip instanceof Inet4Address&&!ip.isLoopbackAddress())a.put(ip.getHostAddress());}}}catch(Exception ignored){}return a;}
 static JSONArray usbDevices(){JSONArray a=new JSONArray();File[] devices=new File("/sys/bus/usb/devices").listFiles();if(devices!=null)for(File f:devices){String product=read(f+"/product");if(product.length()>0&&!product.toLowerCase(Locale.US).contains("root hub"))a.put(product);}return a;}
 static int[] dimensions(){try{
  // Query the display service directly: DisplayManagerGlobal caches stale values in app_process.
  Class<?> sm=Class.forName("android.os.ServiceManager");Object binder=sm.getMethod("getService",String.class).invoke(null,"display");
  Class<?> stub=Class.forName("android.hardware.display.IDisplayManager$Stub"),api=Class.forName("android.hardware.display.IDisplayManager");
  Object service=stub.getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
  Object info=api.getMethod("getDisplayInfo",int.class).invoke(service,0);
  return new int[]{info.getClass().getField("logicalWidth").getInt(info),info.getClass().getField("logicalHeight").getInt(info),info.getClass().getField("rotation").getInt(info)};
 }catch(Exception ignored){return new int[]{1080,2400,0};}}
 static JSONObject status(boolean admin)throws Exception{
  String b="/sys/class/power_supply/battery/",u="/sys/class/power_supply/usb/";int[] size=dimensions();
  return new JSONObject().put("version","0.9.3").put("deviceType",BOX?"box":"phone").put("batteryAvailable",!BOX).put("videoMode",BOX?"snapshot":"h264").put("socTemp",number("/sys/class/thermal/thermal_zone0/temp",-1)).put("pairing",admin?Pairing.list():new JSONArray()).put("capacity",number(b+"capacity",-1)).put("temp",number(b+"temp",-1)).put("current",number(b+"current_now",0)).put("voltage",number(b+"voltage_now",0))
   .put("batteryStatus",read(b+"status")).put("usbOnline",number(u+"online",-1)).put("usbPresent",number(u+"present",-1)).put("usbType",read(u+"real_type"))
   .put("inputVoltage",number(u+"voltage_now",0)).put("inputCurrent",number(u+"input_current_now",0)).put("disabled",number(VOTE+"force_active",-1))
   .put("owned",OWNED.exists()).put("reason",reason).put("fault",fault).put("lastTick",lastTick).put("screen",screen()).put("addresses",addresses()).put("usbDevices",usbDevices())
   .put("config",new JSONObject(config.toString())).put("streaming",streaming.get()).put("width",size[0]).put("height",size[1]).put("rotation",size[2]).put("videoBytes",videoBytes.get())
   .put("keyListenerReady",admin?Boolean.valueOf(BOX&&PhysicalInputMonitor.ready()):null).put("keyInspection",admin?KeyInspection.status():null).put("displayOnlyOffSupported",BOX&&new File("/sys/class/drm/card0-HDMI-A-1/dpms").exists()).put("displayOutput",BOX?read("/sys/class/drm/card0-HDMI-A-1/dpms"):"internal")
   .put("mediaStatusSupported",true).put("mediaIntentSupported",true).put("audioOnlySupported",BOX).put("mediaToggleSupported",true).put("playbackState",MediaActions.state(config.optString("playerPackage",""))).put("playbackBusy",startingPlayback.get()).put("mediaRequestId",mediaRequestId.get())
   .put("playbackRequest",config.optString("playerPackage","").equals(playbackPackage)?playbackRequest:"").put("remoteCueRequested",remoteCueActive).put("localInputAgeMs",localInputAt==0?-1:Math.max(0,System.currentTimeMillis()-localInputAt)).put("remoteControlActive",System.currentTimeMillis()<remoteLeaseUntil).put("localInputAt",localInputAt).put("capturedKey",admin?capturedKey:null).put("keyCaptureSeconds",admin?Long.valueOf(Math.max(0,(captureKeyUntil-System.currentTimeMillis()+999)/1000)):null).put("playerName",MediaActions.name(config.optString("playerPackage",""))).put("playerPackage",config.optString("playerPackage","")).put("oneTapKey",config.optString("oneTapKey",""));
 }
 static String cueProcess(){try{return command("pidof","cn.couger.livingplayer");}catch(Exception ignored){return "";}}
 static void updateRemoteCue(){long now=System.currentTimeMillis();boolean active=BOX&&now<remoteLeaseUntil;
  if(active&&remoteCueActive&&now>=nextCueCheck){nextCueCheck=now+5000;String pid=cueProcess();if(pid.isEmpty()||!pid.equals(remoteCuePid))remoteCueActive=false;}
  if(active==remoteCueActive||active&&now<nextCueRetry)return;
  try{String result;if(active)result=command("am","start-foreground-service","--user","0","-n","cn.couger.livingplayer/.CueOverlayService","--ez","active","true");else result=command("am","stopservice","--user","0","-n","cn.couger.livingplayer/.CueOverlayService");if(result.contains("Error:"))throw new IOException("cue service failed");remoteCueActive=active;remoteCuePid=active?cueProcess():"";nextCueCheck=now+5000;nextCueRetry=0;}catch(Exception ignored){nextCueRetry=now+5000;}
 }
 static void physicalInput(String key){localInputAt=System.currentTimeMillis();if(System.currentTimeMillis()<captureKeyUntil){if(KeyBinding.accepts(key)){capturedKey=key;captureKeyUntil=0;}return;}if(!key.equals(config.optString("oneTapKey",""))||config.optString("playerPackage","").isEmpty())return;boolean screenOff=sleeping(screen()),hdmiOff=BOX&&read("/sys/class/drm/card0-HDMI-A-1/dpms").equalsIgnoreCase("Off");String pkg=config.optString("playerPackage","");if(!screenOff&&!hdmiOff&&!MediaActions.active(MediaActions.state(pkg)))return;new Thread(()->{try{requestPlayback(pkg,true);}catch(Exception ignored){}} ,"remote-one-tap").start();}
 static void physicalInput(String key,String source){if(KeyInspection.record(key,source)){localInputAt=System.currentTimeMillis();return;}physicalInput(key);}
 static void beginKeyCapture(){KeyInspection.stop();capturedKey="";captureKeyUntil=System.currentTimeMillis()+20000;}
 static final AtomicBoolean startingPlayback=new AtomicBoolean();
 static volatile String playbackRequest="", playbackPackage="";
 static final java.util.concurrent.atomic.AtomicLong mediaRequestId=new java.util.concurrent.atomic.AtomicLong();
 static void oneTap(String pkg)throws Exception{requestPlayback(pkg,false);}
 static long requestPlayback(String pkg,boolean toggle)throws Exception{return requestPlayback(pkg,toggle,"",false);}
 static long requestPlayback(String pkg,boolean toggle,String desired,boolean audioOnly)throws Exception{
  if(!MediaActions.allowed(pkg))throw new IOException("请先配置音乐 APP");
  if(!desired.isEmpty()&&!desired.equals("playing")&&!desired.equals("paused"))throw new IOException("无效的播放意图");
  if(audioOnly&&!BOX)throw new IOException("关闭 HDMI 音乐模式仅支持盒子");
  if(!startingPlayback.compareAndSet(false,true))throw new IOException("正在处理播放控制，请稍候");
  long id=mediaRequestId.incrementAndGet();playbackPackage=pkg;playbackRequest="正在处理播放／暂停";
  Thread worker=new Thread(()->{String outcome;try{
   if(audioOnly){if(!desired.equals("paused")||!sleeping(screen()))wake(false);synchronized(powerLock){displayPower(false);}}
   outcome=!desired.isEmpty()?MediaActions.desired(pkg,desired):toggle?MediaActions.toggle(pkg):MediaActions.oneTap(pkg);
  }catch(Exception e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();outcome="播放请求失败："+(cause.getMessage()==null?"系统媒体会话暂不可用":cause.getMessage());}
  if(audioOnly)try{synchronized(powerLock){displayPower(false);}if(!read("/sys/class/drm/card0-HDMI-A-1/dpms").equalsIgnoreCase("Off"))throw new IOException("HDMI 未确认关闭");}catch(Exception e){outcome="播放请求失败：音乐状态可能已变化，但 HDMI 关闭未确认";}
  if(audioOnly&&outcome.startsWith("所选 APP 已")&&!desired.isEmpty()){try{Thread.sleep(250);String state=MediaActions.state(pkg);if(desired.equals("playing")&&!state.equals("playing")||desired.equals("paused")&&(state.equals("playing")||state.equals("buffering")||state.equals("connecting")))outcome="播放请求失败：HDMI 关闭后音乐状态未保持，请检查音乐 APP";}catch(Exception e){outcome="播放请求失败：最终音乐状态未确认";}}
  playbackRequest=outcome;startingPlayback.set(false);
  },"media-playback");worker.setDaemon(true);worker.start();return id;
 }
 static JSONObject mediaStatus()throws Exception{String pkg=config.optString("playerPackage","");return new JSONObject().put("playerPackage",pkg).put("playbackState",MediaActions.state(pkg)).put("playbackBusy",startingPlayback.get()).put("mediaRequestId",mediaRequestId.get()).put("playbackRequest",pkg.equals(playbackPackage)?playbackRequest:"");}
 static void response(OutputStream out,int code,String type,byte[] bytes)throws IOException{String reason=code==200?"OK":code==401?"Unauthorized":"Error";out.write(("HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(bytes);out.flush();}
 static void json(OutputStream out,JSONObject data)throws IOException{response(out,200,"application/json; charset=utf-8",data.toString().getBytes("UTF-8"));}
 static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<2048;i++){int v=in.read();if(v<0)throw new EOFException();if(v==10)return b.toString("US-ASCII").replace("\r","");b.write(v);}throw new IOException("header too large");}
 static boolean localPeer(InetAddress ip){if(ip.isLoopbackAddress())return true;try{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements()){NetworkInterface n=ns.nextElement();if(!n.getName().startsWith("wlan")&&!n.getName().startsWith("eth"))continue;for(InterfaceAddress a:n.getInterfaceAddresses()){if(!(a.getAddress() instanceof Inet4Address)||!(ip instanceof Inet4Address))continue;byte[] left=a.getAddress().getAddress(),right=ip.getAddress();int bits=a.getNetworkPrefixLength();boolean equal=true;for(int j=0;j<4;j++){int k=Math.min(8,Math.max(0,bits-8*j)),mask=k==0?0:(255<<(8-k))&255;if((left[j]&mask)!=(right[j]&mask))equal=false;}if(equal)return true;}}}catch(Exception ignored){}return false;}
 static void stream(OutputStream out,String session,final String bearer,final boolean loopback)throws Exception{
  if(!streaming.compareAndSet(false,true)){response(out,409,"text/plain",new byte[0]);return;}
  captureSession=session;
  try{
   final int[] size=dimensions();double scale=Math.min(1200.0/Math.max(size[0],size[1]),540.0/Math.min(size[0],size[1]));int width=((int)(size[0]*scale)/2)*2,height=((int)(size[1]*scale)/2)*2;
   out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/h264\r\nX-Video-Width: "+width+"\r\nX-Video-Height: "+height+"\r\nX-Display-Width: "+size[0]+"\r\nX-Display-Height: "+size[1]+"\r\nX-Display-Rotation: "+size[2]+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.flush();
   capture=new ProcessBuilder("screenrecord","--output-format=h264","--size",width+"x"+height,"--bit-rate","1500000","--time-limit","120","-").redirectError(new File(ROOT,"video-error.txt")).start();
   final java.lang.Process p=capture;final long start=System.currentTimeMillis();videoLease=start;
   ScheduledFuture<?> guard=timer.scheduleAtFixedRate(new Runnable(){public void run(){int[] now=dimensions();if(!authorized(bearer,loopback)||System.currentTimeMillis()-start>123000||System.currentTimeMillis()-videoLease>15000||!Arrays.equals(size,now))p.destroy();}},1,1,TimeUnit.SECONDS);
   try{byte[] b=new byte[32768];int n;InputStream in=p.getInputStream();while((n=in.read(b))>=0){out.write(b,0,n);out.flush();videoBytes.addAndGet(n);}}finally{guard.cancel(false);p.destroy();p.waitFor(3,TimeUnit.SECONDS);}
  }finally{capture=null;captureSession="";streaming.set(false);}
 }
 static synchronized void frame(OutputStream out)throws Exception{

  java.lang.Process process=new ProcessBuilder("screencap","-p").redirectError(new File(ROOT,"frame-error.txt")).start();
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();final IOException[] error={null};
  Thread reader=new Thread(()->{try(InputStream in=process.getInputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))>0){if(bytes.size()+n>16777216)throw new IOException("frame too large");bytes.write(b,0,n);}}catch(IOException e){error[0]=e;}});reader.start();
  if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroy();reader.join(1000);throw new IOException("screen capture timeout");}reader.join(1000);
  if(reader.isAlive()||error[0]!=null||process.exitValue()!=0)throw new IOException("screen capture failed");
  android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size());if(bitmap==null)throw new IOException("screen capture unavailable");
  int[] display=dimensions();int width=bitmap.getWidth(),height=bitmap.getHeight();double scale=Math.min(1,1280.0/Math.max(width,height));android.graphics.Bitmap scaled=scale<1?android.graphics.Bitmap.createScaledBitmap(bitmap,(int)(width*scale),(int)(height*scale),true):bitmap;
  ByteArrayOutputStream jpeg=new ByteArrayOutputStream();try{scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,jpeg);}finally{if(scaled!=bitmap)scaled.recycle();bitmap.recycle();}
  byte[] data=jpeg.toByteArray();out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "+data.length+"\r\nX-Display-Width: "+display[0]+"\r\nX-Display-Height: "+display[1]+"\r\nX-Display-Rotation: "+display[2]+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();
 }
 static int coord(JSONObject p,String key)throws Exception{int n=p.getInt(key);if(n<0||n>5000)throw new IOException("coordinate out of range");return n;}
 static final Object powerLock=new Object();
 static File displayOwned(){return new File(ROOT,"display-off-owned");}
 static void displayPower(boolean on)throws Exception{
  if(!BOX)throw new IOException("此显示输出操作仅用于已适配盒子");
  Class<?> c=Class.forName("android.view.SurfaceControl");
  long[] ids=(long[])c.getDeclaredMethod("getPhysicalDisplayIds").invoke(null);
  if(ids.length!=1)throw new IOException("显示设备数量变化，未切换 HDMI 输出");
  Object token=c.getDeclaredMethod("getPhysicalDisplayToken",long.class).invoke(null,ids[0]);
  if(!on)write(displayOwned().getPath(),"owned");
  c.getDeclaredMethod("setDisplayPowerMode",android.os.IBinder.class,int.class).invoke(null,token,on?2:0);
  if(on)displayOwned().delete();
 }
 static void restoreDisplay()throws Exception{if(BOX&&displayOwned().exists())displayPower(true);}
 static boolean sleeping(String state){return state.equals("Asleep")||state.equals("Dozing")||state.equals("Doze");}
 static void wake()throws Exception{wake(true);}
 static void wake(boolean restoreOutput)throws Exception{
  synchronized(powerLock){
   if(BOX&&screen().equals("Awake")){if(restoreOutput)restoreDisplay();return;}
   command("input","keyevent","224");
   if(!BOX)return;
   for(int i=0;i<3;i++){Thread.sleep(150);if(screen().equals("Awake")){if(restoreOutput)restoreDisplay();return;}}
   // This factory firmware ignores KEYCODE_WAKEUP. Never toggle on unknown/awake state.
   if(!sleeping(screen()))throw new IOException("无法确认盒子处于睡眠，未发送电源切换键");
   command("input","keyevent","26");
   for(int i=0;i<8;i++){Thread.sleep(200);if(screen().equals("Awake")){if(restoreOutput)restoreDisplay();return;}}
   throw new IOException("盒子尚未确认唤醒，请检查 HDMI 与待机状态");
  }
 }
 static void action(JSONObject p)throws Exception{
  String a=p.getString("action");
  switch(a){
   case "wake":wake();break;
   case "sleep":synchronized(powerLock){command("input","keyevent","223");}break;
   case "display_off":synchronized(powerLock){if(!BOX||!screen().equals("Awake"))throw new IOException("请先唤醒盒子，再仅关闭 HDMI");displayPower(false);}break;
   case "back":command("input","keyevent","4");break;
   case "home":command("input","keyevent","3");break;
   case "volume_up":command("input","keyevent","24");break;
   case "volume_down":command("input","keyevent","25");break;
   case "play":command("input","keyevent","126");break;
   case "pause":command("input","keyevent","127");break;
   case "previous":command("input","keyevent","88");break;
   case "next":command("input","keyevent","87");break;
   case "one_tap":oneTap(config.optString("playerPackage",""));break;
   case "capture_remote_key":if(!BOX)throw new IOException("仅盒子支持遥控器按键绑定");beginKeyCapture();break;
   case "inspect_keys_start":if(!BOX)throw new IOException("按键识别仅支持 ROOT 盒子");captureKeyUntil=0;capturedKey="";KeyInspection.start();break;
   case "inspect_keys_stop":KeyInspection.stop();break;
   case "brightness":int value=p.getInt("value");if(value<5||value>255)throw new IOException("brightness range");command("settings","put","system","screen_brightness_mode","0");command("settings","put","system","screen_brightness",String.valueOf(value));break;
   case "tap":command("input","tap",String.valueOf(coord(p,"x")),String.valueOf(coord(p,"y")));break;
   case "long_press":case "swipe":String x=String.valueOf(coord(p,"x")),y=String.valueOf(coord(p,"y"));command("input","swipe",x,y,a.equals("long_press")?x:String.valueOf(coord(p,"x2")),a.equals("long_press")?y:String.valueOf(coord(p,"y2")),a.equals("long_press")?"800":"350");break;
   case "video_stop":if(capture!=null&&captureSession.equals(p.optString("session","legacy")))capture.destroy();break;
   case "video_ping":if(captureSession.equals(p.optString("session","legacy")))videoLease=System.currentTimeMillis();break;
   case "stop":synchronized(ReceiverServer.class){config.put("enabled",false);atomic(CONFIG,config.toString());restoreCharge();restoreSettings();}timer.schedule(new Runnable(){public void run(){System.exit(0);}},1,TimeUnit.SECONDS);break;
   default:throw new IOException("unsupported action");
  }
 }
 static synchronized void configure(JSONObject p)throws Exception{
  JSONObject next=new JSONObject(config.toString());
  if(p.has("playerPackage")){String pkg=p.getString("playerPackage");if(!pkg.isEmpty()&&!MediaActions.allowed(pkg))throw new IOException("unsupported player");if(!pkg.isEmpty()&&!command("pm","path",pkg).startsWith("package:"))throw new IOException("所选音乐 APP 未安装");next.put("playerPackage",pkg);}
  if(p.has("oneTapKey")){String key=p.getString("oneTapKey");if(!key.isEmpty()&&!KeyBinding.accepts(key))throw new IOException("无效的系统按键名称");next.put("oneTapKey",key);}
  if(BOX){atomic(CONFIG,next.toString());config=next;return;}
  for(String k:new String[]{"protect","low","high","hot","cool","idle"})if(p.has(k))next.put(k,p.get(k));
  int low=next.getInt("low"),high=next.getInt("high"),hot=next.getInt("hot"),cool=next.getInt("cool"),idle=next.getInt("idle");
  if(low<40||high>95||high-low<5||hot<350||hot>480||cool<300||hot-cool<10||idle<30||idle>1800)throw new IOException("invalid battery settings");
  atomic(CONFIG,next.toString());config=next;idleSettings();tick();
 }
 static boolean pairingOpen(){return System.currentTimeMillis()<pairingUntil;}
 static boolean authorized(String bearer,boolean loopback){return (Controllers.same(bearer,"Bearer "+token)&&(loopback||config.optBoolean("legacyAllowed",true)))||Controllers.accepts(bearer);}
 static JSONObject securityStatus()throws Exception{return new JSONObject().put("webEnabled",config.optBoolean("webEnabled",false)).put("discoveryEnabled",config.optBoolean("discoveryEnabled",true)).put("legacyAllowed",config.optBoolean("legacyAllowed",false)).put("pairingSeconds",Math.max(0,(pairingUntil-System.currentTimeMillis()+999)/1000)).put("controllers",Controllers.list());}
 static synchronized void security(JSONObject p)throws Exception{
  String op=p.optString("op","settings");
  if(op.equals("revoke")){Controllers.revoke(p.getString("id"));return;}
  if(op.equals("pair_open")){JSONObject next=new JSONObject(config.toString());next.put("discoveryEnabled",true);atomic(CONFIG,next.toString());config=next;pairingUntil=System.currentTimeMillis()+180000;return;}
  if(op.equals("pair_close")){pairingUntil=0;Pairing.clear();return;}
  if(!op.equals("settings"))throw new IOException("unsupported security operation");
  JSONObject next=new JSONObject(config.toString());for(String k:new String[]{"webEnabled","discoveryEnabled","legacyAllowed"})if(p.has(k))next.put(k,p.getBoolean(k));atomic(CONFIG,next.toString());config=next;
 }
 static void client(SSLSocket socket){try(SSLSocket s=socket){s.setSoTimeout(10000);if(!localPeer(s.getInetAddress()))return;InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=new BufferedOutputStream(s.getOutputStream());String[] request=line(in).split(" ");if(request.length!=3)return;String auth="",videoSession="legacy";int length=0,total=0;while(true){String h=line(in);total+=h.length();if(total>8192)throw new IOException("headers");if(h.isEmpty())break;int c=h.indexOf(':');if(c<0)continue;String key=h.substring(0,c).trim(),val=h.substring(c+1).trim();if(key.equalsIgnoreCase("Authorization"))auth=val;if(key.equalsIgnoreCase("X-Video-Session"))videoSession=val;if(key.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(val);}
   boolean pairing=request[0].equals("POST")&&(request[1].equals("/pair/request")||request[1].equals("/pair/result")||request[1].equals("/pair/cancel"));
   boolean loopback=s.getInetAddress().isLoopbackAddress();boolean admin=loopback&&Controllers.same(auth,"Bearer "+token);
   if(!pairing&&!authorized(auth,loopback)){response(out,401,"text/plain",new byte[0]);return;}
   if(!pairing&&!admin)remoteLeaseUntil=System.currentTimeMillis()+25000;
   if(length<0||length>4096)throw new IOException("body too large");byte[] body=new byte[length];int pos=0,n;while(pos<length&&(n=in.read(body,pos,length-pos))>0)pos+=n;if(pos!=length)throw new EOFException();
   if(!pairing&&!authorized(auth,loopback)){response(out,401,"text/plain",new byte[0]);return;}
   try{if(pairing){JSONObject p=new JSONObject(new String(body,"UTF-8"));String peer=s.getInetAddress().getHostAddress();json(out,request[1].equals("/pair/request")?Pairing.request(p,peer,fingerprint):request[1].equals("/pair/cancel")?Pairing.cancel(p,peer):Pairing.result(p,peer));}
    else if(request[1].equals("/security")&&(request[0].equals("GET")||request[0].equals("POST"))){if(!admin){response(out,403,"text/plain",new byte[0]);return;}if(request[0].equals("POST"))security(new JSONObject(new String(body,"UTF-8")));json(out,securityStatus());}
    else if(request[0].equals("POST")&&request[1].equals("/pair/approve")){if(!admin){response(out,403,"text/plain",new byte[0]);return;}Pairing.approve(new JSONObject(new String(body,"UTF-8")),token,fingerprint);json(out,new JSONObject().put("ok",true));}
    else if(request[0].equals("GET")&&request[1].equals("/status"))json(out,status(admin));
    else if(request[0].equals("GET")&&request[1].equals("/media-status")){json(out,mediaStatus());}
    else if(request[0].equals("GET")&&request[1].equals("/frame")){frame(out);}
    else if(request[0].equals("GET")&&request[1].equals("/video")){if(!videoSession.equals("legacy")&&!videoSession.matches("[0-9a-f]{32}"))throw new IOException("video session required");s.setSoTimeout(0);stream(out,videoSession,auth,loopback);}
    else if(request[0].equals("POST")&&(request[1].equals("/action")||request[1].equals("/config"))){JSONObject p=new JSONObject(new String(body,"UTF-8"));boolean isAction=request[1].equals("/action");String actionName=p.optString("action");boolean localOnly=isAction?(actionName.equals("capture_remote_key")||actionName.equals("inspect_keys_start")||actionName.equals("inspect_keys_stop")||actionName.equals("brightness")||actionName.equals("stop")):true;if(localOnly&&!admin){response(out,403,"application/json",new JSONObject().put("error","设备配置、按键工具及服务管理只能在受控端本机操作").toString().getBytes("UTF-8"));return;}if(isAction&&actionName.equals("media_toggle")){long id=requestPlayback(config.optString("playerPackage",""),true,p.optString("desiredState",""),p.optBoolean("audioOnly",false));json(out,new JSONObject().put("ok",true).put("mediaRequestId",id));return;}if(isAction)action(p);else configure(p);json(out,new JSONObject().put("ok",true));}
    else response(out,404,"text/plain",new byte[0]);
   }catch(Exception e){response(out,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"failed":e.getMessage()).toString().getBytes("UTF-8"));}
  }catch(Exception ignored){}
 }
 public static void main(String[] args)throws Exception{
  if(Process.myUid()!=0)throw new SecurityException("Root required");init();
  if(args.length>0&&(args[0].equals("init")||args[0].equals("enable"))){if(args[0].equals("enable")){JSONObject c=new JSONObject(read(CONFIG.getPath()));c.put("enabled",true);atomic(CONFIG,c.toString());}System.out.println(read(ROOT+"/secret.json"));return;}
  config=new JSONObject(read(CONFIG.getPath()));Controllers.load();if(!config.optBoolean("enabled",true))return;
  restoreCharge();
  File original=new File(ROOT,"original-settings.json");if(original.exists()){JSONObject o=new JSONObject(read(original.getPath()));originalTimeout=o.getInt("timeout");originalStay=o.getInt("stay");}else{originalTimeout=numberSetting("system","screen_off_timeout");originalStay=numberSetting("global","stay_on_while_plugged_in");atomic(original,new JSONObject().put("timeout",originalTimeout).put("stay",originalStay).toString());}
  JSONObject secret=new JSONObject(read(ROOT+"/secret.json"));token=secret.getString("token");fingerprint=secret.getString("fingerprint");char[] password=token.toCharArray();KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType());try(InputStream f=new FileInputStream(ROOT+"/tls.bks")){ks.load(f,password);}KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,password);SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,new SecureRandom());
  final SSLServerSocket server=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket(8766,12);server.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
  write(ROOT+"/server.pid",String.valueOf(Process.myPid()));Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){try{restoreCharge();}catch(Exception ignored){}try{restoreDisplay();}catch(Exception ignored){}if(capture!=null)capture.destroy();PhysicalInputMonitor.stop();}}));
  new Thread(new Runnable(){public void run(){try(DatagramSocket udp=new DatagramSocket(8767)){byte[] b=new byte[512];while(true){DatagramPacket p=new DatagramPacket(b,b.length);udp.receive(p);if(config.optBoolean("discoveryEnabled",true)&&localPeer(p.getAddress())&&new String(p.getData(),0,p.getLength(),"UTF-8").equals("LIVING_PLAYER_DISCOVER_1")){byte[] reply=new JSONObject().put("name","客厅播放器 · "+android.os.Build.MODEL).put("fingerprint",fingerprint).put("port",8766).toString().getBytes("UTF-8");udp.send(new DatagramPacket(reply,reply.length,p.getAddress(),p.getPort()));}}}catch(Exception ignored){}}},"discovery").start();
  idleSettings();tick();if(BOX){remoteCueActive=true;updateRemoteCue();PhysicalInputMonitor.start();}timer.scheduleAtFixedRate(new Runnable(){public void run(){updateRemoteCue();}},1,1,TimeUnit.SECONDS);timer.scheduleAtFixedRate(new Runnable(){public void run(){tick();}},15,15,TimeUnit.SECONDS);
  while(true){final SSLSocket s=(SSLSocket)server.accept();try{clients.execute(new Runnable(){public void run(){client(s);}});}catch(RejectedExecutionException e){s.close();}}
 }
}
