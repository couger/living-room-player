package cn.couger.livingplayer;

import android.content.*;
import android.graphics.drawable.Icon;
import android.service.quicksettings.*;

public final class QuickPlayTile extends TileService {
 boolean listening=false;
 final BroadcastReceiver changes=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){paint();}};
 public void onStartListening(){if(!listening){if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(changes,new IntentFilter("cn.couger.livingplayer.QUICK_STATE"),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(changes,new IntentFilter("cn.couger.livingplayer.QUICK_STATE"));listening=true;}paint();}
 public void onStopListening(){if(listening){unregisterReceiver(changes);listening=false;}}
 public void onDestroy(){onStopListening();super.onDestroy();}
 public void onClick(){
  if(QuickPlay.profile(this,QuickPlay.target(this,-1))==null){startActivityAndCollapse(new Intent(this,QuickPlayConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
  // Receiver decides play or pause from its current selected media session.
  QuickPlay.start(this,-1,true);
 }
 void paint(){Tile tile=getQsTile();if(tile==null)return;String pin=QuickPlay.target(this,-1);int color=QuickPlay.color(this,pin);tile.setIcon(Icon.createWithBitmap(QuickPlay.icon(color,!QuickPlay.playing(this,pin))));tile.setState(color==0xff70dba1?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);tile.setLabel(QuickPlay.playing(this,pin)?"暂停":"播放");tile.setContentDescription("已选播放器的播放／暂停；高亮表示最近检查可连接");tile.updateTile();}
}
