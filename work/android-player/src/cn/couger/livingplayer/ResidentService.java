package cn.couger.livingplayer;
import android.app.*;
import android.content.*;
import android.os.*;

public final class ResidentService extends Service {
 public void onCreate(){super.onCreate();NotificationManager m=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);m.createNotificationChannel(new NotificationChannel("receiver","客厅受控端",NotificationManager.IMPORTANCE_LOW));Intent open=new Intent(this,MainActivity.class);PendingIntent p=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);startForeground(1,new Notification.Builder(this,"receiver").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("客厅播放器后台服务").setContentText("点此查看受控服务和本机状态").setContentIntent(p).setOngoing(true).build());}
 final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
 public int onStartCommand(Intent i,int flags,int id){worker.execute(()->{try{ReceiverRuntime.resume(this);}catch(Exception e){android.util.Log.e("LivingPlayer","Receiver startup failed",e);}});return START_STICKY;}
 public void onDestroy(){worker.shutdown();super.onDestroy();}
 public IBinder onBind(Intent i){return null;}
}
