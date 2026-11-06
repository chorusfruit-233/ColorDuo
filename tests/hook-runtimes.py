#!/usr/bin/env python3
"""Execute both production API adapters with faithful before/after and interceptor fixtures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
files = {
'de/robv/android/xposed/XC_MethodHook.java': r'''
package de.robv.android.xposed;
import java.util.*;import java.lang.reflect.*;
public class XC_MethodHook {
 public static class MethodHookParam {
  public Object thisObject;public Object[] args;public Member method;Object result;Throwable throwable;boolean early;
  Map<String,Object> extras=new HashMap<>();
  public void setObjectExtra(String k,Object v){extras.put(k,v);}public Object getObjectExtra(String k){return extras.get(k);}
  public Object getResult(){return result;}public Throwable getThrowable(){return throwable;}
  public void setResult(Object r){result=r;throwable=null;early=true;}
  public void setThrowable(Throwable t){throwable=t;result=null;early=true;}
 }
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
}
''',
'de/robv/android/xposed/XposedBridge.java': r'''
package de.robv.android.xposed;
import java.util.*;import java.lang.reflect.*;
public class XposedBridge {
 public static int logged;public static final Map<Member,List<XC_MethodHook>> hooks=new HashMap<>();
 public static void log(String s){logged++;}public static int getXposedVersion(){return 93;}
 public static Object hookMethod(Member m,XC_MethodHook h){hooks.computeIfAbsent(m,k->new ArrayList<>()).add(h);return null;}
 public interface Original{Object run(Object[] args)throws Throwable;}
 public static Object invoke(Member method,Object receiver,Object[] args,Original original)throws Throwable{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=method;p.thisObject=receiver;p.args=args;
  List<XC_MethodHook> list=hooks.getOrDefault(method,List.of());int entered=0;
  for(XC_MethodHook hook:list){hook.beforeHookedMethod(p);entered++;if(p.early)break;}
  if(!p.early)try{p.result=original.run(p.args);}catch(Throwable t){p.throwable=t;}
  while(entered>0)list.get(--entered).afterHookedMethod(p);
  if(p.throwable!=null)throw p.throwable;return p.result;
 }
 public static Object invokeOriginalMethod(Member m,Object o,Object[] args){throw new AssertionError("bypassed other modules");}
}
''',
'io/github/libxposed/api/XposedModule.java': r'''
package io.github.libxposed.api;
import android.content.SharedPreferences;import java.lang.reflect.*;import java.util.*;
public class XposedModule {
 public static int logged;
 public interface Hooker{Object intercept(Chain chain)throws Throwable;}
 public static class Chain {
  final Object receiver;final Object[] args;final Original original;
  Chain(Object receiver,Object[] args,Original original){this.receiver=receiver;this.args=args;this.original=original;}
  public Object getThisObject(){return receiver;}public Object getArg(int i){return args[i];}
  public List<Object> getArgs(){return Arrays.asList(args);}
  public Object proceed()throws Throwable{return original.run(args);}
  public Object proceed(Object[] next)throws Throwable{return original.run(next);}
 }
 public interface Original{Object run(Object[] args)throws Throwable;}
 private final Map<Executable,Hooker> hooks=new HashMap<>();
 public final class Builder {
  final Executable method;Builder(Executable method){this.method=method;}
  public Builder setId(String id){return this;}public void intercept(Hooker hook){hooks.put(method,hook);}
 }
 public Builder hook(Executable method){return new Builder(method);}
 public Object invoke(Executable method,Object receiver,Object[] args,Original original)throws Throwable{
  Hooker hook=hooks.get(method);return hook==null?original.run(args):hook.intercept(new Chain(receiver,args,original));
 }
 public SharedPreferences getRemotePreferences(String n){return null;}
 public int getApiVersion(){return 102;}public void log(int p,String t,String s){logged++;}
}
''',
'io/github/yixing233/hyperduo/RuntimeTest.java': r'''
package io.github.yixing233.hyperduo;
import java.lang.reflect.*;import java.util.*;
import io.github.libxposed.api.XposedModule;
import de.robv.android.xposed.*;
public class RuntimeTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static class Target {boolean ready;public Target(){}public Object read(Object value){return value;}}
 interface Original {Object run(Object[] args)throws Throwable;}
 static class Fixture {
  final boolean legacy;final XposedModule modern=new XposedModule();final HookRuntime runtime;
  Fixture(boolean legacy){this.legacy=legacy;XposedBridge.hooks.clear();runtime=legacy?new LegacyRuntime():new ModernRuntime(modern);}
  Object invoke(Executable m,Object target,Original original,Object...args)throws Throwable{
   return legacy?XposedBridge.invoke(m,target,args,original::run):modern.invoke(m,target,args,original::run);
  }
 }
 public static void main(String[] args)throws Throwable{
  Method method=Target.class.getMethod("read",Object.class);Target target=new Target();
  for(boolean legacy:new boolean[]{false,true}){
   Fixture f=new Fixture(legacy);List<String> order=new ArrayList<>();
   f.runtime.hook(method,"ordering",call->{order.add("our-before");call.args[0]="rewritten";call.data="private";},call->{
    check(call.data.equals("private"),"per-invocation state survives");order.add("our-after");
    check(call.result.equals("returned"),"downstream result reaches after callback");});
   if(legacy)XposedBridge.hookMethod(method,new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){order.add("other-before");check(p.args[0].equals("rewritten"),"argument rewrite reaches other modules");}
    protected void afterHookedMethod(MethodHookParam p){order.add("other-after");}
   });
   Object result=f.invoke(method,target,a->{
    if(!legacy){order.add("other-before");check(a[0].equals("rewritten"),"argument rewrite reaches next interceptor");}
    order.add("native");if(!legacy)order.add("other-after");return "returned";
   },"old");
   check(result.equals("returned")&&order.equals(List.of("our-before","other-before","native","other-after","our-after")),"original executes exactly once with other modules intact");
   f=new Fixture(legacy);int[] original={0},after={0};
   f.runtime.hook(method,"override",c->c.returnValue(null),c->after[0]++);
   check(f.invoke(method,target,a->{original[0]++;return "wrong";},"input")==null,"intentional null return skips native method");
   check(original[0]==0&&after[0]==1,"skip still receives one after callback");
   f=new Fixture(legacy);Throwable nativeError=new IllegalStateException("native");int[] cleaned={0};
   f.runtime.hook(method,"exception",c->c.data="restore",c->{check(c.throwable==nativeError,"after sees original failure");cleaned[0]++;throw new IllegalArgumentException("cleanup");});
   try{f.invoke(method,target,a->{throw nativeError;},"input");throw new AssertionError("swallowed failure");}
   catch(Throwable error){check(error==nativeError&&cleaned[0]==1,"cleanup failure cannot replace native exception");}
   f=new Fixture(legacy);int[] calls={0};
   f.runtime.hook(method,"bad-before",c->{throw new IllegalStateException("callback");},c->calls[0]++);
   check(f.invoke(method,target,a->{calls[0]++;return 7;},"input").equals(7)&&calls[0]==2,"bad callback leaves original and after running");
   f=new Fixture(legacy);Fixture nested=f;List<Integer> depths=new ArrayList<>();
   f.runtime.hook(method,"nested",c->c.data=c.args[0],c->depths.add((Integer)c.data));
   f.invoke(method,target,a->{nested.invoke(method,target,b->null,2);return null;},1);
   check(depths.equals(List.of(2,1)),"nested calls never overwrite outer frame");
   f=new Fixture(legacy);Constructor<Target> constructor=Target.class.getConstructor();int[] constructors={0};
   f.runtime.hook(constructor,"constructor",null,c->check(((Target)c.receiver).ready,"constructor observation runs after initialization"));
   f.invoke(constructor,target,a->{constructors[0]++;target.ready=true;return null;});
   check(constructors[0]==1,"constructor is not invoked a second time");
   check(f.runtime.getApiVersion()==(legacy?93:102),"actual framework API is reported");
  }
  System.out.println("Modern/legacy hook adapters passed: "+checks+" checks");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='colorduo-runtimes-') as tmp:
    tmp=Path(tmp)
    for name,content in files.items():
        path=tmp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    sources=[str(p) for p in tmp.rglob('*.java')]
    sources += [str(ROOT/'tests/shims/android/content/SharedPreferences.java')]
    for flavor,name in [('main','HookRuntime'),('modern','ModernRuntime'),('legacy','LegacyRuntime')]:
        sources.append(str(ROOT/f'app/src/{flavor}/java/io/github/yixing233/hyperduo/{name}.java'))
    subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),*sources],check=True)
    subprocess.run(['java','-cp',str(tmp),'io.github.yixing233.hyperduo.RuntimeTest'],check=True)
