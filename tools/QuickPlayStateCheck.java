package cn.couger.livingplayer;

/** Protocol regression: never equate acceptance or buffering with confirmed playback. */
public final class QuickPlayStateCheck {
 static int checks=0;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  check(QuickPlayState.result("")==0);
  check(QuickPlayState.result("正在启动音乐 APP")==0);
  check(QuickPlayState.result("播放请求已受理")==0);
  check(QuickPlayState.result("所选 APP 已进入播放状态（音箱实际出声尚未核验）")==1);
  check(QuickPlayState.result("播放请求失败：音乐 APP 未开始播放")==-1);
  check(QuickPlayState.result("音乐 APP 仍在缓冲，尚未确认开始播放")==-1);
  check(QuickPlayState.result("任意未知状态")==0);
  check(!QuickPlayState.fresh(1000,0));
  check(!QuickPlayState.fresh(1000,1001));
  check(QuickPlayState.fresh(121000,1000));
  check(!QuickPlayState.fresh(121001,1000));
  check(QuickPlayState.result("所选 APP 已暂停")==1);
  check(QuickPlayState.playing("playing"));
  check(!QuickPlayState.playing("paused"));
  check(!QuickPlayState.playing("unknown"));
  check(!QuickPlayState.playing("buffering"));
  check(QuickPlayState.completed(4,4,true,"所选 APP 已暂停")==0);
  check(QuickPlayState.completed(4,4,false,"所选 APP 已暂停")==1);
  check(QuickPlayState.completed(4,5,false,"所选 APP 已暂停")==-1);
  check(QuickPlayState.completed(0,0,false,"所选 APP 已暂停")==-1);
  check(QuickPlayState.completed(4,4,false,"播放请求失败：暂停未确认")==-1);
  QuickPlayState.Snapshot view=QuickPlayState.snapshot(true,true,true,"playing",1000,1000);
  check(view.online&&view.playing&&view.remainingMillis==120001);
  view=QuickPlayState.snapshot(true,true,true,"paused",1000,1000);
  check(view.online&&!view.playing);
  view=QuickPlayState.snapshot(true,true,true,"playing",121000,1000);
  check(view.online&&view.playing&&view.remainingMillis==1);
  view=QuickPlayState.snapshot(true,true,true,"playing",121001,1000);
  check(!view.online&&!view.playing&&view.remainingMillis==0);
  view=QuickPlayState.snapshot(true,false,true,"playing",1001,1000);
  check(!view.online&&!view.playing);
  view=QuickPlayState.snapshot(false,true,true,"playing",1001,1000);
  check(!view.online&&!view.playing&&view.remainingMillis==0);
  view=QuickPlayState.snapshot(true,true,false,"playing",1001,1000);
  check(!view.online&&!view.playing);
  view=QuickPlayState.snapshot(true,true,true,"playing",999,1000);
  check(!view.online&&!view.playing&&view.remainingMillis==0);
  view=QuickPlayState.snapshot(true,true,true,"unknown",1001,1000);
  check(view.online&&!view.playing);
  System.out.println(checks+" quick-play protocol checks passed");
 }
}
