package cn.couger.livingplayer;

import org.json.*;
import android.util.Base64;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

final class DeviceClient {
 static final class AuthorizationException extends IOException {AuthorizationException(){super("授权已失效，请在受控端允许后重新配对");}}
 final Set<HttpsURLConnection> pending=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<HttpsURLConnection,Boolean>());
 void cancelPending(){for(HttpsURLConnection c:pending)c.disconnect();}
 volatile String host;final String token,pin;final SSLSocketFactory factory;
 DeviceClient(String code)throws Exception{
  if(!code.trim().startsWith("LP1:"))throw new IOException("请粘贴受控端提供的连接码");
  String[] p=new String(Base64.decode(code.trim().substring(4),Base64.URL_SAFE|Base64.NO_WRAP),"UTF-8").split("\\|");
  if(p.length!=3||!p[1].matches("[0-9a-f]{48}")||!p[2].matches("[0-9a-f]{64}"))throw new IOException("连接码不完整");host=p[0];token=p[1];pin=p[2];
  TrustManager[] trust={new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{String fp=CertificateMaker.hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()));if(!MessageDigest.isEqual(fp.getBytes("US-ASCII"),pin.getBytes("US-ASCII")))throw new CertificateException("设备证书不匹配");c[0].checkValidity();}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}}}};
  SSLContext context=SSLContext.getInstance("TLS");context.init(null,trust,new SecureRandom());factory=context.getSocketFactory();
 }
 String code(){try{return "LP1:"+Base64.encodeToString((host+"|"+token+"|"+pin).getBytes("UTF-8"),Base64.URL_SAFE|Base64.NO_WRAP);}catch(Exception e){return "";}}
 static String code(String host,String token,String pin)throws Exception{return "LP1:"+Base64.encodeToString((host+"|"+token+"|"+pin).getBytes("UTF-8"),Base64.URL_SAFE|Base64.NO_WRAP);}
 HttpsURLConnection connection(String route)throws Exception{
  if(!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw new IOException("需要局域网 IPv4 地址");
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+host+":8766"+route).openConnection();c.setSSLSocketFactory(factory);
  // Pinning above authenticates the exact receiver certificate; its CN is not an IP.
  c.setHostnameVerifier(new HostnameVerifier(){public boolean verify(String h,SSLSession s){try{String fp=CertificateMaker.hex(MessageDigest.getInstance("SHA-256").digest(s.getPeerCertificates()[0].getEncoded()));return fp.equals(pin);}catch(Exception e){return false;}}});
  c.setConnectTimeout(4000);c.setReadTimeout(6000);c.setRequestProperty("Authorization","Bearer "+token);return c;
 }
 JSONObject request(String route,JSONObject payload)throws Exception{
  HttpsURLConnection c=connection(route);pending.add(c);try{
   if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] bytes=payload.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
   int status=c.getResponseCode();if(status==401)throw new AuthorizationException();if(status!=200){String error="设备返回 "+status;InputStream in=c.getErrorStream();if(in!=null)try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while(out.size()<8192&&(n=in.read(b))>0)out.write(b,0,n);error=new JSONObject(out.toString("UTF-8")).optString("error",error);}catch(Exception ignored){}finally{in.close();}throw new IOException(error);}
   try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0){if(out.size()>65536)throw new IOException("response too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
  }finally{pending.remove(c);c.disconnect();}
 }
 boolean rediscover(){try(DatagramSocket socket=new DatagramSocket()){
  socket.setBroadcast(true);socket.setSoTimeout(1000);byte[] q="LIVING_PLAYER_DISCOVER_1".getBytes("UTF-8");
  Set<String> broadcasts=new HashSet<String>();broadcasts.add("255.255.255.255");Enumeration<NetworkInterface> list=NetworkInterface.getNetworkInterfaces();while(list.hasMoreElements())for(InterfaceAddress a:list.nextElement().getInterfaceAddresses())if(a.getBroadcast()!=null)broadcasts.add(a.getBroadcast().getHostAddress());
  for(String ip:broadcasts)socket.send(new DatagramPacket(q,q.length,InetAddress.getByName(ip),8767));
  long end=System.currentTimeMillis()+2500;while(System.currentTimeMillis()<end){try{DatagramPacket p=new DatagramPacket(new byte[512],512);socket.receive(p);JSONObject r=new JSONObject(new String(p.getData(),0,p.getLength(),"UTF-8"));if(pin.equals(r.optString("fingerprint"))){try{String next=LanAddress.parse(p.getAddress().getHostAddress());DeviceClient candidate=new DeviceClient(code(next,token,pin));candidate.request("/status",null);host=next;return true;}catch(Exception ignored){}}}catch(SocketTimeoutException ignored){}}
 }catch(Exception ignored){}return false;}
 static java.util.List<JSONObject> discover(){java.util.List<JSONObject> found=new ArrayList<JSONObject>();Set<String> seen=new HashSet<String>();try(DatagramSocket socket=new DatagramSocket()){
  socket.setBroadcast(true);socket.setSoTimeout(600);byte[] q="LIVING_PLAYER_DISCOVER_1".getBytes("UTF-8");Set<String> broadcasts=new HashSet<String>();broadcasts.add("255.255.255.255");Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements())for(InterfaceAddress a:ns.nextElement().getInterfaceAddresses())if(a.getBroadcast()!=null)broadcasts.add(a.getBroadcast().getHostAddress());
  for(String ip:broadcasts)try{socket.send(new DatagramPacket(q,q.length,InetAddress.getByName(ip),8767));}catch(Exception ignored){}
  long end=System.currentTimeMillis()+2200;while(System.currentTimeMillis()<end){try{DatagramPacket p=new DatagramPacket(new byte[1024],1024);socket.receive(p);JSONObject r=new JSONObject(new String(p.getData(),0,p.getLength(),"UTF-8"));String fp=r.optString("fingerprint"),ip=p.getAddress().getHostAddress();if(fp.matches("[0-9a-f]{64}")&&seen.add(ip)){r.put("host",ip);found.add(r);}}catch(SocketTimeoutException ignored){}}
 }catch(Exception ignored){}return found;}
 static String wifiAddress(){try{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements()){NetworkInterface n=ns.nextElement();if(!n.getName().startsWith("wlan"))continue;Enumeration<InetAddress> ips=n.getInetAddresses();while(ips.hasMoreElements()){InetAddress ip=ips.nextElement();if(ip instanceof Inet4Address&&!ip.isLoopbackAddress())return ip.getHostAddress();}}}catch(Exception ignored){}return "127.0.0.1";}
}
