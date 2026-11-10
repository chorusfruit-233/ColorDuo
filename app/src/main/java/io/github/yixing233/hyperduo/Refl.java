/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small reflection helper. Never throws: all failures degrade to null / default. */
final class Refl {

    // Bounded, loader-aware keys. Cache metadata only, never receivers or invocation results.
    private static final int CLASS_LIMIT=64, MEMBER_LIMIT=128;
    private static final LinkedHashMap<Class<?>,Members> CACHE=new LinkedHashMap<>(16,.75f,true);
    private static final Class<?>[] NO_ARGS=new Class<?>[0];
    private static final class Members {
        final Map<String,Field> fields=new LinkedHashMap<>();
        final Map<String,Method> noArgs=new LinkedHashMap<>();
        final Map<MethodKey,Method> methods=new LinkedHashMap<>();
    }
    private static final class MethodKey {
        final String name;final Class<?>[] signature;final int mode;
        MethodKey(String name,Class<?>[] signature,int mode) {
            this.name=name;this.signature=signature.clone();this.mode=mode;
        }
        @Override public int hashCode(){return 31*(31*name.hashCode()+mode)+Arrays.hashCode(signature);}
        @Override public boolean equals(Object value){
            if(!(value instanceof MethodKey))return false;
            MethodKey key=(MethodKey)value;
            return mode==key.mode&&name.equals(key.name)&&Arrays.equals(signature,key.signature);
        }
    }
    private static Members members(Class<?> type) {
        Members found=CACHE.get(type);
        if(found==null) {
            if(CACHE.size()>=CLASS_LIMIT)CACHE.remove(CACHE.keySet().iterator().next());
            found=new Members();CACHE.put(type,found);
        }
        return found;
    }
    private static <K,V> void remember(Map<K,V> map,K key,V value) {
        if(map.size()>=MEMBER_LIMIT)map.remove(map.keySet().iterator().next());
        map.put(key,value);
    }
    static void clearCaches(){synchronized(CACHE){CACHE.clear();}}

    // mode: declared-only, inherited declared, public (including interfaces).
    private static Method cachedMethod(Class<?> type,String name,Class<?>[] signature,int mode) {
        if(type==null||name==null)return null;
        if(signature==null)signature=NO_ARGS;
        synchronized(CACHE) {
            Members cache=members(type);
            boolean simple=mode==2&&signature.length==0;
            MethodKey key=simple?null:new MethodKey(name,signature,mode);
            if(simple?cache.noArgs.containsKey(name):cache.methods.containsKey(key))
                return simple?cache.noArgs.get(name):cache.methods.get(key);
            Method found=null;boolean stable=true;
            for(Class<?> owner=type;owner!=null;owner=mode==1?owner.getSuperclass():null) {
                try {
                    found=mode==2?owner.getMethod(name,signature):owner.getDeclaredMethod(name,signature);
                    found.setAccessible(true);break;
                } catch(NoSuchMethodException missing) { /* Stable absence can be cached. */ }
                catch(Throwable unavailable){found=null;stable=false;}
            }
            if(found!=null||stable) {
                if(simple)remember(cache.noArgs,name,found);else remember(cache.methods,key,found);
            }
            return found;
        }
    }

    private Refl() {
    }

    static Class<?> cls(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Field field(Class<?> c,String name) {
        if(c==null||name==null)return null;
        synchronized(CACHE) {
            Members cache=members(c);
            if(cache.fields.containsKey(name))return cache.fields.get(name);
            Field found=null;boolean stable=true;
            for(Class<?> owner=c;owner!=null;owner=owner.getSuperclass()) {
                try {found=owner.getDeclaredField(name);found.setAccessible(true);break;}
                catch(NoSuchFieldException missing){ }
                catch(Throwable unavailable){found=null;stable=false;}
            }
            if(found!=null||stable)remember(cache.fields,name,found);
            return found;
        }
    }

    static Method method(Class<?> c,String name,Class<?>... sig) {
        return cachedMethod(c,name,sig,0);
    }

    /**
     * Like {@link #method}, but walks up the superclasses.
     *
     * <p>{@code getDeclaredMethod} only sees what the class itself declares, so a
     * hook on a framework callback the class merely inherits - {@code onLayout}
     * and {@code onMeasure} being the two this module needs - resolves to null
     * and installs as a silent no-op. Walking the chain is what makes those hooks
     * work; the alternative, guessing which superclass declares it, is exactly
     * the mistake that kept a fix from running on device.
     */
    static Method methodDeep(Class<?> c,String name,Class<?>... sig) {
        return cachedMethod(c,name,sig,1);
    }

    static Object get(Field f, Object o) {
        if (f == null || o == null) {
            return null;
        }
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static int getInt(Field f, Object o, int def) {
        Object v = get(f, o);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    static float getFloat(Field f, Object o, float def) {
        Object v = get(f, o);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    static boolean getBool(Field f, Object o, boolean def) {
        Object v = get(f, o);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    static void set(Field f, Object o, Object v) {
        if (f == null || o == null) {
            return;
        }
        try {
            f.set(o, v);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Invokes a method by name with an explicit signature, or returns null. */
    static Object callArgs(Object o,String name,Class<?>[] sig,Object[] args) {
        if(o==null)return null;
        return invokeArgs(cachedMethod(o.getClass(),name,sig,2),o,args);
    }

    /** Invokes an already-resolved no-arg method, or returns null. */
    static Object invoke(Method m, Object o) {
        if (m == null || o == null) {
            return null;
        }
        try {
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes an already-resolved method with arguments, or returns null. */
    static Object invokeArgs(Method m, Object o, Object[] args) {
        if (m == null || o == null) {
            return null;
        }
        try {
            return m.invoke(o, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes a public no-arg method by name, or returns null. */
    static Object callByName(Object o,String name) {
        if(o==null)return null;
        return invoke(cachedMethod(o.getClass(),name,NO_ARGS,2),o);
    }
}
