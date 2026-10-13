package cn.couger.livingplayer;

/** Linux key names, as reported by getevent. No restriction to spare function keys. */
final class KeyBinding {
 static boolean accepts(String key){return key!=null&&key.matches("KEY_[A-Z0-9_]{1,64}");}
}
