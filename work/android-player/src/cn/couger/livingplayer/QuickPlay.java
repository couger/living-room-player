package cn.couger.livingplayer;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import org.json.*;

/** Controller-only entry points. No receiver configuration, video, or idle polling. */
final class QuickPlay {
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("player",0);}
 static String target(Context c,int widget){return prefs(c).getString(widget>=0?"quickWidget."+widget:"quickTile","");}
 static JSONObject profile(Context c,String pin){return new SavedDevices(prefs(c)).find(pin);}
 static boolean audioOnly(Context c,String pin){return prefs(c).getBoolean("musicAudioOnly."+pin,false);}
 static int res(Context c,String type,String name){return c.getResources().getIdentifier(name,type,c.getPackageName());}
 static PendingIntent task(Context c,int widget,boolean play){
  Intent i=new Intent(c,QuickPlayService.class).setAction(play?"play":"status").putExtra("widget",widget);
  return PendingIntent.getForegroundService(c,(widget+2)*2+(play?1:0),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
 static void start(Context c,int widget,boolean play){c.startForegroundService(new Intent(c,QuickPlayService.class).setAction(play?"play":"status").putExtra("widget",widget));}
 static int color(Context c,String pin){
  long at=prefs(c).getLong("quickAt."+pin,0);
  if(profile(c,pin)==null||!QuickPlayState.fresh(System.currentTimeMillis(),at))return 0xff718095;
  return prefs(c).getBoolean("quickOnline."+pin,false)?0xff70dba1:0xffb1b9c5;
 }
 static boolean playing(Context c,String pin){return profile(c,pin)!=null&&prefs(c).getBoolean("quickOnline."+pin,false)&&QuickPlayState.playing(prefs(c).getString("quickState."+pin,"unknown"));}
 static Bitmap icon(int color,boolean play){Bitmap b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint p=new Paint(3);p.setColor(color);if(play){Path path=new Path();path.moveTo(30,20);path.lineTo(30,76);path.lineTo(75,48);path.close();canvas.drawPath(path,p);}else{canvas.drawRect(26,22,42,74,p);canvas.drawRect(54,22,70,74,p);}return b;}
 static void record(Context c,String pin,boolean online){prefs(c).edit().putLong("quickAt."+pin,System.currentTimeMillis()).putBoolean("quickOnline."+pin,online).apply();refresh(c);}
 static void record(Context c,String pin,JSONObject status){prefs(c).edit().putLong("quickAt."+pin,System.currentTimeMillis()).putBoolean("quickOnline."+pin,true).putString("quickState."+pin,status.optString("playbackState","unknown")).apply();refresh(c);}
 static int[] widgets(Context c){AppWidgetManager manager=AppWidgetManager.getInstance(c);int[] small=manager.getAppWidgetIds(new ComponentName(c,QuickPlayWidget.class)),wide=manager.getAppWidgetIds(new ComponentName(c,QuickPlayWideWidget.class)),all=java.util.Arrays.copyOf(small,small.length+wide.length);System.arraycopy(wide,0,all,small.length,wide.length);return all;}
 static PendingIntent open(Context c,int id,String pin){Intent i=new Intent(c,MainActivity.class).putExtra("quickPin",pin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);return PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static Bitmap openIcon(){Bitmap b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint p=new Paint(3);p.setColor(0xff73d5ce);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);Path path=new Path();path.moveTo(44,22);path.lineTo(24,22);path.lineTo(24,74);path.lineTo(76,74);path.lineTo(76,54);canvas.drawPath(path,p);path.reset();path.moveTo(48,18);path.lineTo(80,18);path.lineTo(80,50);path.moveTo(42,56);path.lineTo(80,18);canvas.drawPath(path,p);return b;}
 static void widget(Context c,int id){
  String pin=target(c,id);JSONObject entry=profile(c,pin);android.appwidget.AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);boolean wide=info!=null&&info.provider.getClassName().equals(QuickPlayWideWidget.class.getName());RemoteViews views=new RemoteViews(c.getPackageName(),res(c,"layout",wide?"quick_play_wide_widget":"quick_play_widget"));
  boolean playing=playing(c,pin);views.setImageViewBitmap(res(c,"id","quick_play"),icon(color(c,pin),!playing));
  String name=entry==null?"未配置设备":entry.optString("name");
  views.setContentDescription(res(c,"id","quick_play"),name+(playing?"，暂停":"，连接并播放")+"；绿色表示最近检查可连接，灰色表示未确认或不可达");
  views.setOnClickPendingIntent(res(c,"id","quick_play"),task(c,id,true));
  if(wide){views.setImageViewBitmap(res(c,"id","quick_open"),openIcon());views.setOnClickPendingIntent(res(c,"id","quick_open"),open(c,id,pin));views.setContentDescription(res(c,"id","quick_open"),"打开 "+name+" 的控制页");}
  AppWidgetManager.getInstance(c).updateAppWidget(id,views);
 }
 static void refresh(Context c){for(int id:widgets(c))widget(c,id);c.sendBroadcast(new Intent("cn.couger.livingplayer.QUICK_STATE").setPackage(c.getPackageName()));}
 static void failure(Context c){new Handler(Looper.getMainLooper()).post(()->{Toast toast=Toast.makeText(c,"播放控制失败",Toast.LENGTH_SHORT);toast.show();new Handler(Looper.getMainLooper()).postDelayed(toast::cancel,1000);});}
}
