package cn.couger.livingplayer;
import java.io.IOException;

/** Manual receiver endpoints are private IPv4 addresses, never URLs or ports. */
final class LanAddress {
 static String parse(String value)throws IOException{
  String[] parts=value.trim().split("\\.",-1);if(parts.length!=4)throw new IOException("请输入家庭网络 IPv4 地址，不含网址或端口");int[] n=new int[4];
  for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw new IOException("IP 地址格式不正确");n[i]=Integer.parseInt(parts[i]);if(n[i]>255)throw new IOException("IP 地址每段须在 0–255 之间");}
  if(!(n[0]==10||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==192&&n[1]==168))throw new IOException("仅支持家庭局域网地址");
  return n[0]+"."+n[1]+"."+n[2]+"."+n[3];
 }
}
