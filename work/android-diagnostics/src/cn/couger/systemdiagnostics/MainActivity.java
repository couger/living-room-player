package cn.couger.systemdiagnostics;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
 final ExecutorService jobs=Executors.newSingleThreadExecutor();final Handler ui=new Handler(Looper.getMainLooper());TextView state;boolean alive=true;
 int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
 TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(231,237,244));t.setPadding(dp(8),dp(8),dp(8),dp(8));return t;}
 void button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.rgb(231,237,244));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(41,57,74)));root.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->run(action));}
 public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(16,23,32));getWindow().setNavigationBarColor(Color.rgb(16,23,32));ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(10),dp(16),dp(10));root.setBackgroundColor(Color.rgb(16,23,32));scroll.addView(root);setContentView(scroll);
  root.addView(text("系统故障记录 · 0.1.0",24));state=text("正在检查本机记录状态…",17);root.addView(state);
  root.addView(text("独立系统诊断工具，不提供远控、播放或网络服务。记录系统错误、崩溃、内核及部分音频／HDMI 状态。首次需要本机 ROOT 授权；按需选择永久记住，临时授权到期后无法保证开机自动恢复。",15));
  button(root,"开始／恢复记录",()->{try{RootRuntime.enable(this);refresh();}catch(Exception e){show("未启动："+e.getMessage());}});
  button(root,"停止记录（保留已有日志）",()->{try{RootRuntime.disable(this);refresh();}catch(Exception e){show("未停止："+e.getMessage());}});
  button(root,"刷新状态",()->refresh());
  root.addView(text("启用后，系统启动完成时自动恢复；启动助手完成任务后退出，后台仅保留记录脚本和系统日志进程。卸载本 APP 前请先停止记录；ROOT 目录中的日志不会随卸载自动删除。",14));
  root.addView(text("最近三次启动自动轮换，按文件限制容量，预计上限约 75MiB。不录音、不保存屏幕、不自动上传；系统日志仍可能包含私人信息。\n\nAndroid 启动完成前的卡动画、内核崩溃和突然断电不保证能完整捕获。恢复后会尽力保存固件已有的 Recovery 等历史记录。\n\n日志目录："+RootRuntime.LOGS+"\n使用 PC 诊断导出工具导出，分享前请脱敏。",14));
  run(()->refresh());
 }
 void run(Runnable task){if(!jobs.isShutdown())jobs.execute(task);}
 void show(String message){ui.post(()->{if(alive)state.setText(message);});}
 void refresh(){try{String s=RootRuntime.status();boolean enabled=s.contains("enabled=1"),running=s.contains("running=1");getSharedPreferences("diagnostics",0).edit().putBoolean("enabled",enabled).apply();String size="";for(String line:s.split("\n"))if(line.contains(RootRuntime.LOGS))size="\n日志占用："+line.trim().split("\\s+")[0]+" KiB";show((enabled?"开机记录：已启用":"开机记录：已关闭")+"\n记录进程："+(running?"运行中":"未运行")+size+(enabled&&!running?"\n请点“开始／恢复记录”检查启动":""));}catch(Exception e){show("状态未读取："+e.getMessage());}}
 protected void onDestroy(){alive=false;ui.removeCallbacksAndMessages(null);jobs.shutdown();super.onDestroy();}
}
