package cn.couger.livingplayer;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.text.SimpleDateFormat;
import java.util.*;

// Minimal X.509 certificate builder: the private key is generated on the receiver.
final class CertificateMaker {
 static byte[] join(byte[]... parts) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); for(byte[] p:parts)b.write(p); return b.toByteArray(); }
 static byte[] der(int tag,byte[] data) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); b.write(tag); if(data.length<128)b.write(data.length); else { int n=data.length,k=0; while(n>0){k++;n>>>=8;} b.write(128|k); for(int i=k-1;i>=0;i--)b.write(data.length>>>(8*i)); } b.write(data);return b.toByteArray(); }
 static byte[] seq(byte[]... p)throws IOException{return der(0x30,join(p));}
 static byte[] integer(BigInteger n)throws IOException{return der(2,n.toByteArray());}
 static byte[] time(Date d)throws IOException {SimpleDateFormat f=new SimpleDateFormat("yyMMddHHmmss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return der(23,f.format(d).getBytes("US-ASCII"));}
 static KeyStore create(char[] password)throws Exception {
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair kp=g.generateKeyPair();
  byte[] algo=seq(der(6,new byte[]{0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,1,1,11}),der(5,new byte[0]));
  byte[] name=seq(der(0x31,seq(der(6,new byte[]{0x55,4,3}),der(12,"Living Player".getBytes("UTF-8")))));
  byte[] tbs=seq(der(0xa0,integer(BigInteger.valueOf(2))),integer(new BigInteger(120,new SecureRandom())),algo,name,seq(time(new Date(System.currentTimeMillis()-86400000L)),time(new Date(System.currentTimeMillis()+10L*365*86400000L))),name,kp.getPublic().getEncoded());
  Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(kp.getPrivate());sig.update(tbs);
  byte[] encoded=seq(tbs,algo,der(3,join(new byte[]{0},sig.sign())));
  X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));cert.verify(kp.getPublic());
  KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,password);store.setKeyEntry("device",kp.getPrivate(),password,new java.security.cert.Certificate[]{cert});return store;
 }
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
}
