package cn.couger.livingplayer;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector transport icons; navigation swipes deliberately stay text buttons. */
final class ControlIcon extends Drawable {
 final String kind; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 ControlIcon(String k,int color){kind=k;paint.setColor(color);}
 public void draw(Canvas c){Rect b=getBounds();c.save();c.translate(b.left,b.top);c.scale(b.width()/32f,b.height()/32f);
  if(kind.equals("pause")){c.drawRect(7,5,13,27,paint);c.drawRect(20,5,26,27,paint);}
  else{boolean previous=kind.equals("previous");if(previous){c.translate(32,0);c.scale(-1,1);}Path p=new Path();p.moveTo(8,5);p.lineTo(8,27);p.lineTo(25,16);p.close();c.drawPath(p,paint);if(previous||kind.equals("next"))c.drawRect(26,5,29,27,paint);}c.restore();
 }
 public void setAlpha(int a){paint.setAlpha(a);invalidateSelf();}
 public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();}
 public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
