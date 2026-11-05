package io.github.yixing233.hyperduo.ui;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SliderWriteQueueTest {
 @Test public void separateControlsKeepTheirPendingWrites() {
  List<Runnable> jobs=new ArrayList<>();List<Integer> left=new ArrayList<>(),right=new ArrayList<>();
  SliderWriteQueue a=new SliderWriteQueue(jobs::add,jobs::remove,left::add);
  SliderWriteQueue b=new SliderWriteQueue(jobs::add,jobs::remove,right::add);
  a.submit(1);b.submit(2);a.submit(3);
  a.flush();assertEquals(List.of(3),left);assertTrue(right.isEmpty());assertTrue(b.hasPending());
  b.flush();assertEquals(List.of(2),right);assertTrue(jobs.isEmpty());
 }
 @Test public void coalescesAndFlushesFinalValue() {
  List<Runnable> jobs=new ArrayList<>();List<Integer> writes=new ArrayList<>();
  SliderWriteQueue q=new SliderWriteQueue(jobs::add,jobs::remove,writes::add);
  for(int i=0;i<100;i++)q.submit(i);
  assertEquals(1,jobs.size());assertTrue(writes.isEmpty());
  jobs.get(0).run();assertEquals(List.of(99),writes);assertTrue(jobs.isEmpty());
  q.submit(150);q.flush();q.flush();assertEquals(List.of(99,150),writes);assertFalse(q.hasPending());
 }
}
