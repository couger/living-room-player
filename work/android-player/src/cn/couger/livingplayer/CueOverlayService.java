package cn.couger.livingplayer;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Small, non-interactive HDMI cue shown only while a remote controller lease is active. */
public final class CueOverlayService extends Service {
 private static final int NOTIFICATION_ID=8732;
 private static final String CHANNEL="remote_control_cue";
 private WindowManager windows;private View badge;
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 public void onCreate(){super.onCreate();windows=(WindowManager)getSystemService(WINDOW_SERVICE);if(android.os.Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"远程控制提示",NotificationManager.IMPORTANCE_MIN);channel.setSound(null,null);channel.enableVibration(false);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);}}
 public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&intent.getBooleanExtra("active",false)){Notification.Builder notification=android.os.Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);notification.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("客厅播放器").setContentText("远程控制已接入").setOngoing(true).setOnlyAlertOnce(true);startForeground(NOTIFICATION_ID,notification.build());show();}else hide();return START_NOT_STICKY;}
 private void show(){if(badge!=null)return;TextView label=new TextView(this);label.setText("远程控制中");label.setTextSize(12);label.setTextColor(Color.WHITE);label.setGravity(Gravity.CENTER);label.setPadding(dp(12),dp(5),dp(12),dp(5));GradientDrawable bg=new GradientDrawable();bg.setColor(0xdd155e63);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xff72e2d9);label.setBackground(bg);badge=label;WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-2,dp(36),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.END;lp.x=dp(18);lp.y=dp(12);try{windows.addView(badge,lp);}catch(Exception e){badge=null;}}
 private void hide(){if(badge!=null){try{windows.removeView(badge);}catch(Exception ignored){}badge=null;}stopForeground(true);stopSelf();}
 public void onDestroy(){hide();super.onDestroy();}
 public IBinder onBind(Intent intent){return null;}
}
