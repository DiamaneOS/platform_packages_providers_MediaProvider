/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import java.util.*;import java.util.concurrent.atomic.*;
public class MetadataRepairPassTest {
 @org.junit.Test public void databaseFailureStopsBeforeAdvancingFailedRow() {
  var rows=List.of(new MetadataRepairPass.Row(1,"first",null,0,1L,null,null,null),
    new MetadataRepairPass.Row(2,"next","app",0,2L,null,null,null));
  Store store=new Store(rows) {
   boolean fail=true;
   public void commit(MetadataRepairPass.Row row, MetadataRepairPass.Values values) {
    if(fail) { fail=false; throw new android.database.sqlite.SQLiteFullException(); }
    super.commit(row,values);
   }
  };
  MetadataRepairPass.Reader reader=(row,left)->{var v=new MetadataRepairPass.Values();v.width=7;return v;};
  MetadataRepairPass.run(store,reader,()->0L,()->false);
  org.junit.Assert.assertEquals("A database-wide failure advanced the cursor",-1,store.cursor);
  org.junit.Assert.assertEquals(0,store.commits);
  MetadataRepairPass.run(store,reader,()->0L,()->false);
  org.junit.Assert.assertEquals(2,store.commits);
  org.junit.Assert.assertEquals(2,store.cursor);
 }
 @org.junit.Test public void oneConstraintFailureStillAllowsTheFollowingRow() {
  var rows=List.of(new MetadataRepairPass.Row(1,"first",null,0,1L,null,null,null),
    new MetadataRepairPass.Row(2,"next","app",0,2L,null,null,null));
  Store store=new Store(rows) {
   public void commit(MetadataRepairPass.Row row, MetadataRepairPass.Values values) {
    if(row.id==1)throw new android.database.sqlite.SQLiteConstraintException();
    super.commit(row,values);
   }
  };
  MetadataRepairPass.run(store,(row,left)->{var v=new MetadataRepairPass.Values();v.width=7;return v;},()->0L,()->false);
  org.junit.Assert.assertEquals(1,store.commits);
  org.junit.Assert.assertEquals(2,store.cursor);
 }
 @org.junit.Test public void cancellationAndExpiredReadKeepTheLastSafeCursor() {
  var row=new MetadataRepairPass.Row(1,"first",null,0,1L,null,null,null);
  Store store=new Store(List.of(row));AtomicLong now=new AtomicLong();
  MetadataRepairPass.run(store,(r,left)->{now.set(6000);var v=new MetadataRepairPass.Values();v.width=1;return v;},now::get,()->false);
  org.junit.Assert.assertEquals(-1,store.cursor);
  org.junit.Assert.assertEquals(0,store.commits);
  AtomicBoolean cancelled=new AtomicBoolean();
  MetadataRepairPass.run(store,(r,left)->{cancelled.set(true);throw new java.io.IOException();},()->0L,cancelled::get);
  org.junit.Assert.assertEquals(-1,store.cursor);
 }
 static class Store implements MetadataRepairPass.Store {
  long cursor=-1;boolean writing;int commits;Map<Long,Long> generations=new HashMap<>();
  List<MetadataRepairPass.Row> rows;
  Store(List<MetadataRepairPass.Row> r){rows=r;for(var row:r)generations.put(row.id,row.generation);}
  public long cursor(){return cursor;}
  public List<MetadataRepairPass.Row> snapshot(long after,int limit){if(writing)throw new AssertionError();return rows.stream().filter(row->row.id>after).limit(limit).toList();}
  public void commit(MetadataRepairPass.Row row,MetadataRepairPass.Values values){writing=true;try{if(generations.get(row.id)==row.generation)commits++;}finally{writing=false;}}
  public void saveCursor(long after){cursor=after;}
 }
 @org.junit.Test public void sourceWorkAndWritesStaySeparate(){
  var row=new MetadataRepairPass.Row(1,"fixture","app",10,7L,3,null,null);
  Store stale=new Store(List.of(row));AtomicLong now=new AtomicLong();
  MetadataRepairPass.run(stale,(r,left)->{if(stale.writing)throw new AssertionError("file/VM under write transaction");stale.generations.put(r.id,8L);var v=new MetadataRepairPass.Values();v.width=2;v.height=3;return v;},now::get,()->false);
  if(stale.commits!=0||stale.cursor!=1)throw new AssertionError("generation or durable cursor");
  Store transientIo=new Store(List.of(row));
  MetadataRepairPass.run(transientIo,(r,left)->{throw new java.io.IOException("temporary source failure");},now::get,()->false);
  if(transientIo.commits!=0)throw new AssertionError("I/O became a format verdict");
  Store independent=new Store(List.of(row));
  MetadataRepairPass.run(independent,(r,left)->{var v=new MetadataRepairPass.Values();v.width=2;v.height=3;return v;},now::get,()->false);
  if(independent.commits!=1)throw new AssertionError("known format prevented size repair");
  List<MetadataRepairPass.Row> rows=new ArrayList<>();for(int i=0;i<32;i++)rows.add(new MetadataRepairPass.Row(i+1,"fixture","app",0,0L,null,null,null));
  Store bounded=new Store(rows);AtomicInteger read=new AtomicInteger();
  MetadataRepairPass.run(bounded,(r,left)->{if(left>500)throw new AssertionError("row budget");read.incrementAndGet();now.addAndGet(700);return new MetadataRepairPass.Values();},now::get,()->false);
  if(read.get()>8||bounded.cursor!=read.get())throw new AssertionError("whole pass deadline or cursor");
  try {
   MetadataInput input=new MetadataInput(new java.io.InputStream(){public int read() throws java.io.IOException{throw new java.io.IOException("temporary read");}});
   try {input.read();}catch(java.io.IOException swallowed){}
   try {input.check();throw new AssertionError("swallowed source failure became metadata");}catch(java.io.IOException expected){}
  } catch(Exception e){throw new AssertionError(e);}
  Store badRow = new Store(List.of(row, new MetadataRepairPass.Row(2,"next","app",0,1L,null,null,null))) {
   public void commit(MetadataRepairPass.Row r, MetadataRepairPass.Values v) {
    if (r.id == 1) throw new IllegalArgumentException("one bad row");
    super.commit(r, v);
   }
  };
  MetadataRepairPass.run(badRow, (r,left) -> {var v=new MetadataRepairPass.Values();v.width=7;return v;}, () -> 0L, () -> false);
  if (badRow.commits != 1 || badRow.cursor != 2) throw new AssertionError("bad row stopped pass or cursor");
  System.out.println("PASS source/commit separation, generation race, transient I/O, independent size and whole-pass deadline");
 }
}
