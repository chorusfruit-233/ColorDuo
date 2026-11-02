package io.github.yixing233.hyperduo;
public class ChargeSpeedTest {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("charge check "+checks);}
 public static class Native { public boolean getSuperVoocCharging(){return true;} public boolean getFastCharging(){return true;} }
 public static void main(String[] args) {
  ChargeSpeed[] expected={ChargeSpeed.NORMAL,ChargeSpeed.FLASH,ChargeSpeed.SUPER,ChargeSpeed.QUICK,ChargeSpeed.QUICK,ChargeSpeed.QUICK,ChargeSpeed.FLASH,ChargeSpeed.QUICK};
  for(int i=0;i<8;i++)check(ColorOsChargeSpeed.speed(i)==expected[i]);
  check(ColorOsChargeSpeed.speed(255)==null);
  String[] value={"2"};int[] reads={0};
  ColorOsChargeSpeed sampler=new ColorOsChargeSpeed(path->{check(path.endsWith("/common/protocol_type"));reads[0]++;return value[0];});
  check(sampler.sample(true,1,0).speed==ChargeSpeed.SUPER);
  value[0]="3";check(sampler.sample(true,1,500).speed==ChargeSpeed.SUPER);check(reads[0]==1);
  check(sampler.sample(true,1,1000).speed==ChargeSpeed.QUICK);
  check(sampler.sample(false,0,1100).speed==null);
  check(sampler.sample(true,1,1200).speed==ChargeSpeed.QUICK);
  value[0]="malformed";check(sampler.sample(true,1,2200).speed==null);
  check(sampler.nativeSample(new Native()).speed==ChargeSpeed.SUPER);
  TrioSettings s=TrioSettings.defaults();s.roleColors=true;s.pausedGrayAll=false;
  for(ChargeSpeed speed:ChargeSpeed.values())for(int fg:new int[]{0xffffffff,0xff000000}){
   TrioAppearance a=TrioAppearance.of(s);
   check(TrioRenderer.roleColor(a,1,true,true,true,fg,speed)==TrioRenderer.chargingColor(a,speed,fg));
   check(TrioRenderer.roleColor(a,100,true,false,false,fg,speed)==TrioRenderer.chargingColor(a,speed,fg));
   check(TrioRenderer.boltColor(a,speed,fg,true)==fg);
  }
  s.pausedGrayAll=true;check(TrioRenderer.boltColor(TrioAppearance.of(s),ChargeSpeed.SUPER,0xffffffff,true)==DuoPolicy.pausedColor(0xffffffff));
  s.superChargingOnDark=0xff123456;s.flashChargingOnLight=0xff654321;
  check(s.equals(s.copy()));check(s.equals(TrioSettings.fromBundle(s.toBundle())));
  check(DuoPolicy.charge(2,1,Boolean.TRUE)==DuoPolicy.Charge.PAUSED);
  System.out.println("Charging protocol, caching, native fallback, pause policy and colour checks passed: "+checks);
 }
}
