package io.github.yixing233.hyperduo;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class ReflCacheTest {
    public interface Named { default String inherited(){return "interface";} }
    public static class Parent { private int hidden=7; private String local(){return "parent";} }
    public static class Child extends Parent implements Named {
        public String value(){return "live";}
        public String overload(int value){return "int";}
        public String overload(String value){return "string";}
        public String failure(){throw new IllegalStateException();}
    }
    @Test public void modesSignaturesAndMissingMembersKeepTheirSemantics() {
        Refl.clearCaches();Child child=new Child();
        assertNull(Refl.method(Child.class,"local"));
        assertEquals("parent",Refl.invoke(Refl.methodDeep(Child.class,"local"),child));
        assertEquals("interface",Refl.callByName(child,"inherited"));
        assertEquals("int",Refl.callArgs(child,"overload",new Class<?>[]{int.class},new Object[]{3}));
        assertEquals("string",Refl.callArgs(child,"overload",new Class<?>[]{String.class},new Object[]{"3"}));
        assertEquals(7,Refl.getInt(Refl.field(Child.class,"hidden"),child,-1));
        assertNull(Refl.field(Child.class,"missing"));assertNull(Refl.field(Child.class,"missing"));
        assertNull(Refl.callByName(child,"missing"));assertNull(Refl.callByName(child,"failure"));
        assertNull(Refl.callByName(null,"value"));
    }
    @Test public void lookupReuseNeverCachesTheReceiverOrReturnedValue() {
        class Counter {int count; public int next(){return ++count;}}
        Counter first=new Counter(),second=new Counter();
        assertEquals(1,Refl.callByName(first,"next"));assertEquals(2,Refl.callByName(first,"next"));
        assertEquals(1,Refl.callByName(second,"next"));
        Method method=Refl.method(Child.class,"value");assertSame(method,Refl.method(Child.class,"value"));
        Field field=Refl.field(Child.class,"hidden");assertSame(field,Refl.field(Child.class,"hidden"));
        Refl.clearCaches();assertEquals("live",Refl.callByName(new Child(),"value"));
    }
    @Test public void metadataCacheDoesNotGrowWithArbitraryMissingNames()throws Exception {
        Refl.clearCaches();Child child=new Child();
        for(int i=0;i<400;i++)Refl.callByName(child,"absent"+i);
        Field cacheField=Refl.class.getDeclaredField("CACHE");cacheField.setAccessible(true);
        Map<?,?> classes=(Map<?,?>)cacheField.get(null);assertTrue(classes.size()<=64);
        Object members=classes.get(Child.class);Field noArgs=members.getClass().getDeclaredField("noArgs");noArgs.setAccessible(true);
        assertTrue(((Map<?,?>)noArgs.get(members)).size()<=128);
    }
}
