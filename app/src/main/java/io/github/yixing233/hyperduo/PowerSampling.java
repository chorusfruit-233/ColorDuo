/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;

/** One event source for demand-driven samplers; no wake lock or periodic power-state poll. */
final class PowerSampling {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS=new ArrayList<>();
    private static boolean registered,queued;
    static {ColorOsReload.watch(MAIN);}
    static synchronized void watch(Context context,Runnable listener) {
        LISTENERS.add(listener);
        if(registered)return;
        registered=true;
        TrioConfig.addListener(PowerSampling::changed);
        ColorOsReload.watch(PowerSampling::pause,PowerSampling::changed);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){changed();}};
        try {
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver,filter);
            ColorOsReload.watch(context,receiver,filter);
        } catch(RuntimeException ignored){ }
    }
    private static void pause(){
        synchronized(PowerSampling.class){queued=false;}
        MemoryTicker.pause();BatteryPresence.pause();
    }
    static void changed() {
        synchronized(PowerSampling.class){if(queued||ColorOsReload.retiring)return;queued=true;}
        MAIN.post(()->{
            List<Runnable> listeners;
            synchronized(PowerSampling.class){queued=false;listeners=new ArrayList<>(LISTENERS);}
            if(ColorOsReload.retiring)return;
            for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){ }
        });
    }
    static boolean active(Context context) {
        if(context==null||ColorOsReload.retiring||!TrioConfig.get().enabled)return false;
        try {
            PowerManager power=context.getSystemService(PowerManager.class);
            return power!=null&&power.isInteractive()&&!ColorOsHooks.isAodShowing();
        }catch(RuntimeException unavailable){return false;}
    }
}
