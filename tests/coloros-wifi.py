#!/usr/bin/env python3
"""Exercise production Wi-Fi observation and routing against independent framework doubles."""
from pathlib import Path
import argparse
import subprocess
import tempfile

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/java/io/github/yixing233/hyperduo'
parser = argparse.ArgumentParser()
parser.add_argument('--route-source', type=Path, default=SOURCE / 'InternetRoute.java')
parser.add_argument('--presence-source', type=Path, default=SOURCE / 'WifiPresence.java')
options = parser.parse_args()
FILES = {
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
'android/os/Handler.java': '''package android.os; public class Handler {
 public Handler(Looper l){} public void post(Runnable r){r.run();}
 public void postDelayed(Runnable r,long d){} public void removeCallbacks(Runnable r){} }''',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION {public static int SDK_INT=37;} }',
'android/content/Intent.java': 'package android.content; public class Intent {}',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public void addAction(String action){} }',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Context.java': '''package android.content; public class Context {
 public static final int RECEIVER_EXPORTED=2;
 public final android.net.ConnectivityManager connectivity=new android.net.ConnectivityManager();
 public final android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();
 public <T>T getSystemService(Class<T> type){return type.cast(type==android.net.ConnectivityManager.class?connectivity:wifi);}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){} public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){} }''',
'android/net/Network.java': 'package android.net; public record Network(int id) {}',
'android/net/NetworkInfo.java': 'package android.net; public record NetworkInfo(boolean connected) {public boolean isConnected(){return connected;} }',
'android/net/NetworkRequest.java': '''package android.net; public class NetworkRequest {
 public static class Builder {public Builder addTransportType(int t){return this;} public NetworkRequest build(){return new NetworkRequest();}} }''',
'android/net/NetworkCapabilities.java': '''package android.net; import java.util.*; public class NetworkCapabilities {
 public static final int TRANSPORT_WIFI=1,TRANSPORT_CELLULAR=0,TRANSPORT_BLUETOOTH=2,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4;
 public final Set<Integer> transports=new HashSet<>(); public Object info; public List<Network> underlying;public boolean hideTypes;
 public NetworkCapabilities(int... ts){for(int t:ts)transports.add(t);}
 public boolean hasTransport(int t){return transports.contains(t);} public Object getTransportInfo(){return info;}
 public int[] getTransportTypes(){if(hideTypes)throw new SecurityException();return transports.stream().mapToInt(Integer::intValue).toArray();}
 public List<Network> getUnderlyingNetworks(){return underlying;} }''',
'android/net/ConnectivityManager.java': '''package android.net; import java.util.*; public class ConnectivityManager {
 public static final int TYPE_WIFI=1; public Network active; public boolean deny;
 public final Map<Network,NetworkCapabilities> networks=new LinkedHashMap<>();
 public final List<NetworkCallback> callbacks=new ArrayList<>(); public final List<NetworkCallback> defaults=new ArrayList<>();
 public Network getActiveNetwork(){if(deny)throw new SecurityException();return active;}
 public Network[] getAllNetworks(){return networks.keySet().toArray(new Network[0]);}
 public NetworkCapabilities getNetworkCapabilities(Network n){return networks.get(n);}
 public NetworkInfo getNetworkInfo(int type){return null;}
 public void registerDefaultNetworkCallback(NetworkCallback c){defaults.add(c);}
 public void registerNetworkCallback(NetworkRequest r,NetworkCallback c){callbacks.add(c);}
 public void unregisterNetworkCallback(NetworkCallback c){defaults.remove(c);callbacks.remove(c);}
 public static class NetworkCallback {public void onAvailable(Network n){} public void onLost(Network n){}
 public void onCapabilitiesChanged(Network n,NetworkCapabilities c){} } }''',
'android/net/wifi/SupplicantState.java': 'package android.net.wifi; public enum SupplicantState {COMPLETED,DISCONNECTED}',
'android/net/wifi/WifiInfo.java': '''package android.net.wifi; public class WifiInfo {
 public int standard,frequency,rssi=-60; public Boolean primary; public SupplicantState state=SupplicantState.COMPLETED;
 public WifiInfo(int s,int f,Boolean p){standard=s;frequency=f;primary=p;}
 public int getWifiStandard(){return standard;} public int getFrequency(){return frequency;}
 public int getRssi(){return rssi;} public Boolean isPrimary(){return primary;}
 public SupplicantState getSupplicantState(){return state;} }''',
'android/net/wifi/WifiManager.java': '''package android.net.wifi; public class WifiManager {
 public static final String WIFI_STATE_CHANGED_ACTION="wifi",NETWORK_STATE_CHANGED_ACTION="network",RSSI_CHANGED_ACTION="rssi";
 public boolean enabled=true; public WifiInfo info; public boolean isWifiEnabled(){return enabled;}
 public WifiInfo getConnectionInfo(){return info;} public int calculateSignalLevel(int rssi){return calculateSignalLevel(rssi,5);}
 public static int calculateSignalLevel(int rssi,int count){return rssi>=-65?4:rssi>=-75?2:0;} }''',
'io/github/yixing233/hyperduo/TrioState.java': '''package io.github.yixing233.hyperduo; class TrioState {
 static int sWifiLevel=-1; static String sWifiStandard=""; static boolean present;
 static boolean setWifiPresent(boolean next){boolean changed=present!=next;present=next;return changed;} }''',
'io/github/yixing233/hyperduo/TrioHooks.java': '''package io.github.yixing233.hyperduo; class TrioHooks {static int invalidations;static void invalidateHosts(){invalidations++;} }''',
'io/github/yixing233/hyperduo/TrioConfig.java': '''package io.github.yixing233.hyperduo; class TrioConfig {
 static class Appearance {boolean typeHideOnWifi;} static final Appearance a=new Appearance();static Appearance appearance(){return a;} }''',
'io/github/yixing233/hyperduo/ColorOsReload.java': '''package io.github.yixing233.hyperduo; import android.net.*;import android.content.*;import android.os.*;
 class ColorOsReload {static boolean retiring;static void watch(Handler h){} static void watch(Runnable close,Runnable open){}
 static void watch(Context c,BroadcastReceiver r,IntentFilter f){}static void watch(ConnectivityManager c,ConnectivityManager.NetworkCallback cb,NetworkRequest r){} }''',
'io/github/yixing233/hyperduo/WifiObservationTest.java': r'''
package io.github.yixing233.hyperduo;
import android.content.*;import android.net.*;import android.net.wifi.*;import java.util.*;
public class WifiObservationTest {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void refresh(Context c)throws Exception {var m=WifiPresence.class.getDeclaredMethod("refresh",Context.class);m.setAccessible(true);m.invoke(null,c);}
 static NetworkCapabilities wifi(WifiInfo info){var c=new NetworkCapabilities(1);c.info=info;return c;}
 public static void main(String[] args)throws Exception {
  Context c=new Context();var cm=c.connectivity;var wm=c.wifi;
  Network main=new Network(1),secondary=new Network(2),mobile=new Network(3),vpn=new Network(4),unknown=new Network(5);
  WifiInfo first=new WifiInfo(6,6115,true),second=new WifiInfo(5,5180,false);second.rssi=-85;
  cm.networks.put(secondary,wifi(second));cm.networks.put(main,wifi(first));cm.active=main;wm.info=first;
  WifiPresence.start(c);
  check(TrioState.sWifiLevel==4&&Boolean.TRUE.equals(WifiPresence.connectedSnapshot()),"primary link provides strength and presence");
  check("6E".equals(TrioState.sWifiStandard),"secondary Wi-Fi cannot replace the primary standard");
  int before=TrioHooks.invalidations;first.standard=8;refresh(c);
  check("7".equals(TrioState.sWifiStandard)&&TrioHooks.invalidations>before,"standard-only changes repaint without RSSI changes");
  cm.networks.remove(secondary);cm.callbacks.get(0).onLost(secondary);
  check("7".equals(TrioState.sWifiStandard)&&TrioState.present,"losing secondary Wi-Fi preserves primary metadata");
  cm.networks.put(mobile,new NetworkCapabilities(0));cm.active=mobile;TrioConfig.a.typeHideOnWifi=true;
  cm.defaults.get(0).onCapabilitiesChanged(mobile,cm.networks.get(mobile));
  check(InternetRoute.current()==InternetRoute.Path.MOBILE&&!WifiPresence.forDisplay(true),"default mobile route suppresses associated Wi-Fi display");
  check(TrioState.present&&"7".equals(TrioState.sWifiStandard),"display policy does not mutate physical link metadata");
  var tunnel=new NetworkCapabilities(4);tunnel.underlying=List.of(main);cm.networks.put(vpn,tunnel);cm.active=vpn;refresh(c);
  check(InternetRoute.current()==InternetRoute.Path.WIFI&&WifiPresence.forDisplay(true),"VPN with one Wi-Fi underlay retains Wi-Fi");
  tunnel.underlying=List.of(main,mobile);refresh(c);
  check(InternetRoute.current()==InternetRoute.Path.UNKNOWN&&WifiPresence.forDisplay(true),"multipath VPN keeps association fallback");
  tunnel.underlying=List.of(mobile,unknown);refresh(c);
  check(InternetRoute.current()==InternetRoute.Path.UNKNOWN&&WifiPresence.forDisplay(true),"unreadable underlay must not be guessed to be mobile");
  cm.deny=true;refresh(c);check(InternetRoute.current()==InternetRoute.Path.UNKNOWN,"query denial retains unknown route");cm.deny=false;
  Network usb=new Network(6);cm.networks.put(usb,new NetworkCapabilities(8));cm.active=usb;refresh(c);
  check(InternetRoute.current()==InternetRoute.Path.OTHER&&!WifiPresence.forDisplay(true),"USB and other default transports are not mistaken for Wi-Fi");
  var wired=new NetworkCapabilities(3);wired.hideTypes=true;cm.networks.put(usb,wired);refresh(c);
  check(InternetRoute.current()==InternetRoute.Path.OTHER,"public transport fallback survives hidden API restrictions");
  cm.networks.clear();cm.networks.put(main,wifi(first));cm.active=main;wm.info=null;refresh(c);
  check("7".equals(TrioState.sWifiStandard),"primary capabilities recover a missing WifiManager snapshot");
  first.primary=null;refresh(c);check("7".equals(TrioState.sWifiStandard),"one unambiguous Wi-Fi supports older primary APIs");
  cm.networks.put(secondary,wifi(new WifiInfo(4,2412,null)));refresh(c);
  check("".equals(TrioState.sWifiStandard),"unknown primary among multiple links never invents a standard");
  first.primary=true;wm.info=first;first.standard=4;refresh(c);check("4".equals(TrioState.sWifiStandard),"Wi-Fi 4 is displayed");
  first.standard=5;refresh(c);check("5".equals(TrioState.sWifiStandard),"Wi-Fi 5 is displayed");
  first.standard=0;refresh(c);check("".equals(TrioState.sWifiStandard),"unknown standard clears the previous generation");
  first.standard=6;first.frequency=5180;refresh(c);check("6".equals(TrioState.sWifiStandard),"5 GHz 11ax is Wi-Fi 6");
  wm.enabled=false;refresh(c);check(!TrioState.present&&TrioState.sWifiLevel==-1&&"".equals(TrioState.sWifiStandard),"radio off clears presence strength and standard");
  wm.enabled=true;cm.networks.clear();first.state=SupplicantState.DISCONNECTED;refresh(c);
  check(!TrioState.present&&"".equals(TrioState.sWifiStandard),"disconnect leaves no stale badge");
  System.out.println("Wi-Fi observation and routing: "+checks+" checks passed");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='colorduo-wifi-') as directory:
    root = Path(directory)
    sources = []
    for name, contents in FILES.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        sources.append(str(path))
    sources += [str(options.presence_source), str(options.route_source)]
    sources += [str(SOURCE / name) for name in ('WifiStandard.java', 'Refl.java')]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(root), *sources], check=True)
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(root),
                    'io.github.yixing233.hyperduo.WifiObservationTest'], check=True)
