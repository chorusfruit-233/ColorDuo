/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** Execute palette isolation, migration, persistent snapshots and real rendered colours. */
public final class ReviewChargeRegressionTest {
    private static int checks;
    private static void check(boolean ok,String message) {
        checks++;if(!ok) throw new AssertionError(message);
    }

    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        int commits;
        public boolean contains(String key) { return values.containsKey(key); }
        public boolean getBoolean(String key,boolean fallback) { return (Boolean)values.getOrDefault(key,fallback); }
        public int getInt(String key,int fallback) { return (Integer)values.getOrDefault(key,fallback); }
        public String getString(String key,String fallback) { return (String)values.getOrDefault(key,fallback); }
        public Editor edit() { return new Editor() {
            final Map<String,Object> pending=new HashMap<>();
            public Editor putBoolean(String key,boolean value) { pending.put(key,value);return this; }
            public Editor putInt(String key,int value) { pending.put(key,value);return this; }
            public Editor putString(String key,String value) { pending.put(key,value);return this; }
            public boolean commit() { commits++;values.putAll(pending);return true; }
        }; }
    }

    private static void migrationAndTransport() {
        String[] ringKeys={Prefs.KEY_COLOR_CHARGING_ON_DARK,Prefs.KEY_COLOR_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_QUICK_CHARGING_ON_DARK,Prefs.KEY_COLOR_QUICK_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_FLASH_CHARGING_ON_DARK,Prefs.KEY_COLOR_FLASH_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_SUPER_CHARGING_ON_DARK,Prefs.KEY_COLOR_SUPER_CHARGING_ON_LIGHT};
        String[] boltKeys={Prefs.KEY_COLOR_BOLT_CHARGING_ON_DARK,Prefs.KEY_COLOR_BOLT_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_BOLT_QUICK_CHARGING_ON_DARK,Prefs.KEY_COLOR_BOLT_QUICK_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_BOLT_FLASH_CHARGING_ON_DARK,Prefs.KEY_COLOR_BOLT_FLASH_CHARGING_ON_LIGHT,
                Prefs.KEY_COLOR_BOLT_SUPER_CHARGING_ON_DARK,Prefs.KEY_COLOR_BOLT_SUPER_CHARGING_ON_LIGHT};
        Memory old=new Memory();
        for(int i=0;i<ringKeys.length;i++) old.values.put(ringKeys[i],0xff110020+i);
        old.values.put(boltKeys[0],0xff3040f0);
        check(TrioSettings.migrateBoltColors(old),"palette migration commits successfully");
        for(int i=0;i<boltKeys.length;i++) check(old.getInt(boltKeys[i],0)==(i==0?0xff3040f0:0xff110020+i),
                "migration preserves each existing palette and copies only missing bolt colours");
        old.values.put(ringKeys[4],0xfff00000);
        check(TrioSettings.migrateBoltColors(old) && old.commits==1,"migration freezes inherited colours exactly once");
        check(old.getInt(boltKeys[4],0)==0xff110024,"editing the ring after upgrade cannot change an unedited bolt colour");

        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            TrioSettings settings=TrioSettings.from(old,1f,style);
            settings.chargeFlow=true;settings.chargeFlowDirection=1;settings.chargeFlowSpeed=321;
            settings.chargeFlowWidth=44;settings.chargeFlowGradient=12;
            settings.flashChargingOnDark=0xffcc1122;settings.boltFlashChargingOnDark=0xff1234dd;
            check(settings.equals(settings.copy()),"isolated palettes survive copies");
            check(settings.equals(TrioSettings.fromBundle(settings.toBundle())),"isolated palettes survive full broadcasts");
            Memory remote=new Memory();SharedPreferences.Editor editor=remote.edit();
            settings.writeTo(editor);editor.commit();
            var wire=settings.toBundle();
            check(remote.values.keySet().equals(wire.keySet()),"framework replay includes every broadcast key");
            for(String key:wire.keySet()) check(java.util.Objects.equals(remote.values.get(key),wire.get(key)),
                    "persistent remote value matches the live broadcast: "+key);
            TrioSettings restarted=TrioSettings.from(remote,1f,style);
            check(restarted.equals(settings),"a fresh SystemUI snapshot retains flow parameters and every independent colour");
            for(String key:boltKeys) {
                TrioSettings changed=settings.copy();
                var update=changed.toBundle();update.putInt(key,0xff102030);
                changed=TrioSettings.fromBundle(update);
                TrioSettings live=settings.copy();
                check(live.applyKeyFrom(changed,key),"each independent colour has a remote callback");
                check(live.equals(changed),"one-key callback changes only the requested bolt colour");
            }
        }
    }

    private static final class Frame extends Canvas implements AutoCloseable {
        final BufferedImage image;final Graphics2D graphics;final Path bolt;
        int boltPaint;
        Frame() throws Exception { this(new BufferedImage(480,400,BufferedImage.TYPE_INT_ARGB)); }
        private Frame(BufferedImage image) throws Exception { this(image,image.createGraphics()); }
        private Frame(BufferedImage image,Graphics2D graphics) throws Exception {
            super(graphics);this.image=image;this.graphics=graphics;
            var field=TrioRenderer.class.getDeclaredField("BOLT");field.setAccessible(true);bolt=(Path)field.get(null);
        }
        @Override public void drawPath(Path path,Paint paint) {
            if(path==bolt) boltPaint=paint.getColor();
            super.drawPath(path,paint);
        }
        int pixels(int color) { int count=0;for(int y=0;y<image.getHeight();y++)
            for(int x=0;x<image.getWidth();x++) if(image.getRGB(x,y)==color) count++;return count; }
        public void close() { graphics.dispose(); }
    }

    private static void renderColours() throws Exception {
        for(int shape=0;shape<3;shape++) for(int fg:new int[]{0xffffffff,0xff000000})
        for(boolean flow:new boolean[]{false,true}) {
            TrioSettings settings=TrioSettings.defaults();settings.roleColors=true;settings.chargeFlow=flow;
            settings.trioStyle=shape==2?Prefs.STYLE_CAPSULE:Prefs.STYLE_RING;settings.splitMode=shape==1?1:0;
            settings.ringZones=Prefs.decodeLayout("4|1|7|6");settings.showBolt=true;
            settings.chargingShowsValue=false;settings.showMobile=settings.showSim1=true;
            settings.signalMode=Prefs.SIGNAL_IN_RING;
            TrioState state=new TrioState();state.level=66;state.charging=true;state.chargingSpeed=ChargeSpeed.FLASH;
            state.useTint=true;state.tintColor=fg;state.wifiPresent=true;state.wifiLevel=3;
            state.slotLevels=new int[]{4,-1};state.mobileLevel=4;state.sims=1;
            int ring=0xffb21237,bolt=0xff087fdd;
            for(int change=0;change<3;change++) {
                if(change==1) ring=0xfffa9801;
                if(change==2) bolt=0xffa51fcc;
                settings.flashChargingOnDark=settings.flashChargingOnLight=ring;
                settings.boltFlashChargingOnDark=settings.boltFlashChargingOnLight=bolt;
                try(Frame frame=new Frame()) {
                    TrioRenderer.drawState(frame,480,400,state,settings);
                    check(frame.boltPaint==bolt && frame.pixels(bolt)>0,"actual bolt pixels use only the bolt palette");
                    check(frame.pixels(ring)>0,"actual charged outline uses only the ring palette, with or without flow");
                }
            }
        }
    }

    private static void nativeFallbackAndLegacy() {
        ColorOsChargeSpeed sampler=new ColorOsChargeSpeed();
        check(sampler.nativeSample(com.oplus.fixture.NativeChargingFactory.sample()).speed==ChargeSpeed.SUPER,
                "native getters remain readable on non-public model classes");
        TrioSettings settings=TrioSettings.defaults();settings.roleColors=false;
        for(ChargeSpeed speed:ChargeSpeed.values()) for(int fg:new int[]{0xffffffff,0xff000000}) {
            TrioAppearance a=TrioAppearance.of(settings);
            check(TrioRenderer.roleColor(a,50,true,false,false,fg,speed)==fg,"disabled state colours leave the ring in the foreground colour");
            check(TrioRenderer.boltColor(a,speed,fg,false)==(speed==ChargeSpeed.NORMAL?fg:TrioGeometry.QUICK_CHARGE),
                    "all fast charging tiers preserve the legacy bolt colour when state colours are disabled");
        }
    }

    public static void main(String[] args) throws Exception {
        migrationAndTransport();nativeFallbackAndLegacy();renderColours();
        System.out.println("PR review: isolated colours, migration, persistent replay and native fallback: "+checks+" checks passed");
    }
}
