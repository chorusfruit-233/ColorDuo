package io.github.yixing233.hyperduo.ui;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SliderWriteQueueTest {
 @Test public void coalescesAndFlushesFinalValue() {
  List<Runnable> jobs=new ArrayList<>();List<Integer> writes=new ArrayList<>();
  SliderWriteQueue q=new SliderWriteQueue(jobs::add,jobs::remove,writes::add);
  for(int i=0;i<100;i++)q.submit(i);
  assertEquals(1,jobs.size());assertTrue(writes.isEmpty());
  jobs.get(0).run();assertEquals(List.of(99),writes);assertTrue(jobs.isEmpty());
  q.submit(150);q.flush();q.flush();assertEquals(List.of(99,150),writes);assertFalse(q.hasPending());
 }
}
