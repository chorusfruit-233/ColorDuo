package io.github.yixing233.hyperduo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Render the production scan over independent static battery-fill masks. */
public class ChargeFlowTest {
 static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static final int GREEN=0xff34c759;
 static final class Frame extends Canvas {
  final BufferedImage image;double angles,weight;
  Frame(){this(new BufferedImage(640,480,BufferedImage.TYPE_INT_ARGB));}
  Frame(BufferedImage image){super(image.createGraphics());this.image=image;translate(120,40);scale(2,2);}
  @Override public void drawArc(RectF r,float start,float sweep,boolean center,Paint p){angles+=(start+sweep/2)*Math.abs(sweep);weight+=Math.abs(sweep);super.drawArc(r,start,sweep,center,p);}
  double position(){return angles/weight;}
 }
 static Frame flow(TrioSettings s,int level,float phase){Frame f=new Frame();TrioRenderer.drawChargeFlow(f,level,GREEN,TrioAppearance.of(s),phase,s.trioStyle==Prefs.STYLE_CAPSULE?8:0,s.trioStyle==Prefs.STYLE_CAPSULE?8:0,360,90);return f;}
 static Frame mask(TrioSettings s,int level)throws Exception {
  Frame f=new Frame();TrioAppearance a=TrioAppearance.of(s);
  Field field=TrioRenderer.class.getDeclaredField("STROKE");field.setAccessible(true);Paint p=(Paint)field.get(null);p.setColor(GREEN);p.setStrokeCap(Paint.Cap.BUTT);p.setStrokeWidth(a.capsule?a.ringStroke*TrioGeometry.CAP_STROKE_RATIO:a.ringStroke);
  if(a.capsule){Method m=TrioRenderer.class.getDeclaredMethod("drawCapsuleFill",Canvas.class,float.class,float.class,float.class,float.class);m.setAccessible(true);m.invoke(null,f,a.ringStroke*TrioGeometry.CAP_STROKE_RATIO,Math.min(1,level/100f),8f,8f);}
  else {Method m=TrioRenderer.class.getDeclaredMethod("drawBattery",Canvas.class,int.class,int.class,int.class,TrioAppearance.class,float.class,float.class,float.class,float.class);m.setAccessible(true);m.invoke(null,f,level,GREEN,0,a,0f,0f,360f,90f);}
  return f;
 }
 static int ink(Frame f){int n=0;for(int y=0;y<480;y++)for(int x=0;x<640;x++)if((f.image.getRGB(x,y)>>>24)>16)n++;return n;}
 static void confined(Frame moving,Frame reference){
  for(int y=0;y<480;y++)for(int x=0;x<640;x++)if((moving.image.getRGB(x,y)>>>24)>32){
   boolean found=false;for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(x+dx>=0&&x+dx<640&&y+dy>=0&&y+dy<480&&(reference.image.getRGB(x+dx,y+dy)>>>24)>0)found=true;
   check(found,"scan escaped charged outline at "+x+","+y);
  }
 }
 public static void main(String[] args)throws Exception {
  TrioSettings s=TrioSettings.defaults();s.enabled=true;s.trackAlpha=0;s.chargeFlow=true;s.pausedGrayAll=false;
  check(!TrioSettings.defaults().chargeFlow,"existing users keep a static outline by default");
  check(TrioRenderer.chargeFlowActive(s,66,true,false),"charging schedules frames");
  check(!TrioRenderer.chargeFlowActive(s,66,true,true),"pause stops frames");
  check(!TrioRenderer.chargeFlowActive(s,66,false,false),"unplug stops frames");
  check(!TrioRenderer.chargeFlowActive(s,0,true,false),"empty battery stops frames");
  s.enabled=false;check(!TrioRenderer.chargeFlowActive(s,66,true,false),"disabled module stops frames");s.enabled=true;
  s.chargeFlow=false;check(!TrioRenderer.chargeFlowActive(s,66,true,false),"switch stops frames");s.chargeFlow=true;
  for(int direction=0;direction<2;direction++){
   s.chargeFlowDirection=direction;check(s.equals(s.copy()),"copy retains settings");check(s.equals(TrioSettings.fromBundle(s.toBundle())),"bundle roundtrip retains settings");
   TrioSettings next=TrioSettings.defaults();next.applyKeyFrom(s,Prefs.KEY_CHARGE_FLOW);next.applyKeyFrom(s,Prefs.KEY_CHARGE_FLOW_DIRECTION);check(next.chargeFlow&&next.chargeFlowDirection==direction,"individual remote callbacks retain fields");
  }
  check(TrioRenderer.chargeFlowPhase(0)==TrioRenderer.chargeFlowPhase(1_600_000_000L),"phase loops");
  check(TrioRenderer.chargeFlowPhase(-1)>=0,"clock origin cannot make a negative phase");
  s.trioStyle=Prefs.STYLE_RING;s.splitMode=0;s.chargeFlowDirection=0;
  Frame first=flow(s,66,.25f),last=flow(s,66,.65f);check(first.position()<last.position(),"clockwise follows increasing angles");
  s.chargeFlowDirection=1;first=flow(s,66,.25f);last=flow(s,66,.65f);check(first.position()>last.position(),"counterclockwise follows decreasing angles");
  TrioSettings custom=s.copy();custom.chargeFlowSpeed=321;custom.chargeFlowWidth=44;custom.chargeFlowGradient=12;
  check(custom.equals(custom.copy()),"custom parameters survive copy");
  check(custom.equals(TrioSettings.fromBundle(custom.toBundle())),"custom parameters survive snapshot transport");
  TrioSettings keys=TrioSettings.defaults();
  for(String key:new String[]{Prefs.KEY_CHARGE_FLOW_SPEED,Prefs.KEY_CHARGE_FLOW_WIDTH,Prefs.KEY_CHARGE_FLOW_GRADIENT})check(keys.applyKeyFrom(custom,key),"remote key recognized");
  check(keys.chargeFlowSpeed==321&&keys.chargeFlowWidth==44&&keys.chargeFlowGradient==12,"remote callbacks keep each custom parameter");
  android.os.Bundle invalid=custom.toBundle();invalid.putInt(Prefs.KEY_CHARGE_FLOW_SPEED,-10);invalid.putInt(Prefs.KEY_CHARGE_FLOW_WIDTH,999);invalid.putInt(Prefs.KEY_CHARGE_FLOW_GRADIENT,999);
  TrioSettings bounded=TrioSettings.fromBundle(invalid);
  check(bounded.chargeFlowSpeed==10&&bounded.chargeFlowWidth==100&&bounded.chargeFlowGradient==50,"snapshot inputs are bounded");
  float normal=TrioRenderer.chargeFlowPhase(100_000_000L,100);
  check(Math.abs(TrioRenderer.chargeFlowPhase(100_000_000L,400)-normal*4)<.00001,"four times speed advances four times as far");
  check(Math.abs(TrioRenderer.chargeFlowPhase(100_000_000L,10)-normal/10)<.00001,"one tenth speed advances one tenth as far");
  check(TrioRenderer.chargeFlowPhase(400_000_000L,400)==0,"fastest speed loops at its own period");
  check(TrioRenderer.chargeFlowPhase(0,0)==0,"invalid direct speed cannot divide by zero");
  check(TrioRenderer.chargeFlowGlow(.01f,0)==.96f,"zero gradient has a hard edge");
  check(TrioRenderer.chargeFlowGlow(.3f,25)>.95f,"short gradients leave a solid core");
  check(TrioRenderer.chargeFlowGlow(.3f,50)<.8f,"full gradients keep feathering into the band");
  check(Math.abs(TrioRenderer.chargeFlowGlow(.2f,40)-TrioRenderer.chargeFlowGlow(.8f,40))<.00001,"both ends have equal fade width");
  s.chargeFlowWidth=5;int narrow=ink(flow(s,66,.5f));s.chargeFlowWidth=30;
  check(ink(flow(s,66,.5f))>narrow*2,"total width changes real painted length");s.chargeFlowWidth=18;
  Path output=Paths.get("build/flow-reference");Files.createDirectories(output);
  for(int shape=0;shape<3;shape++){
   s.trioStyle=shape==2?Prefs.STYLE_CAPSULE:Prefs.STYLE_RING;s.splitMode=shape==1?1:0;
   for(int direction=0;direction<2;direction++){
    s.chargeFlowDirection=direction;
    for(int level:new int[]{1,10,66,100}){
     Frame reference=mask(s,level);
     for(float phase:new float[]{0,.2f,.5f,.8f,.999f})confined(flow(s,level,phase),reference);
    }
    Frame preview=mask(s,66);TrioRenderer.drawChargeFlow(preview,66,GREEN,TrioAppearance.of(s),.35f,shape==2?8:0,shape==2?8:0,360,90);
    check(ink(flow(s,66,.35f))>0,"each outline and direction paints a scan");
    ImageIO.write(preview.image,"png",output.resolve("shape-"+shape+"-direction-"+direction+".png").toFile());
   }
  }
  for(int shape=0;shape<3;shape++){
   s.trioStyle=shape==2?Prefs.STYLE_CAPSULE:Prefs.STYLE_RING;s.splitMode=shape==1?1:0;
   for(int profile=0;profile<3;profile++){
    s.chargeFlowWidth=new int[]{1,100,40}[profile];s.chargeFlowGradient=new int[]{0,50,20}[profile];
    for(int direction=0;direction<2;direction++){
     s.chargeFlowDirection=direction;confined(flow(s,66,.5f),mask(s,66));
    }
    s.chargeFlowDirection=0;Frame preview=mask(s,66);
    TrioRenderer.drawChargeFlow(preview,66,GREEN,TrioAppearance.of(s),.5f,shape==2?8:0,shape==2?8:0,360,90);
    ImageIO.write(preview.image,"png",output.resolve("custom-shape-"+shape+"-profile-"+profile+".png").toFile());
   }
  }
  check(ink(flow(s,66,-1))==0,"inactive phase has no overlay");
  check(ink(flow(s,0,.5f))==0,"zero charge has no overlay");
  System.out.println("Charging flow rendering, confinement, direction, lifecycle gate and settings: "+checks+" checks passed");
 }
}
