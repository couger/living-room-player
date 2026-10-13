package cn.couger.livingplayer;

/** Compatible with existing receivers; accepting a command is not playback success. */
final class QuickPlayState {
 static int result(String message){
  if(message.startsWith("所选 APP 已进入播放状态")||message.equals("所选 APP 已暂停"))return 1;
  if(message.startsWith("播放请求失败")||message.contains("尚未确认开始播放"))return -1;
  return 0;
 }
 static boolean fresh(long now,long checked){return checked>0&&now>=checked&&now-checked<=120000;}
 static boolean playing(String state){return "playing".equals(state);}
 static int completed(long expected,long actual,boolean busy,String message){if(expected<=0||expected!=actual)return -1;return busy?0:result(message);}
}
