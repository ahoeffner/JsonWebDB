/*
MIT License

Copyright (c) 2024 Alex Høffner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package jsondb;

import state.State;
import java.util.Date;
import database.Cursor;
import java.util.HashMap;
import utils.JSONOObject;
import messages.Messages;
import database.SQLTypes;
import database.BindValue;
import org.json.JSONArray;
import java.util.ArrayList;
import org.json.JSONObject;
import utils.NameValuePair;
import database.JdbcInterface;
import state.StatePersistency;
import java.util.logging.Level;
import jsondb.Monitor.CloseAsap;
import database.definitions.AdvancedPool;
import state.StatePersistency.SessionInfo;
import database.JdbcInterface.UpdateResponse;
import state.StatePersistency.TransactionInfo;


public class Session
{
   private String inst;

   private final String guid;
   private final String user;

   private final boolean forward;
   private final boolean stateful;

   private final Object SYNC = new Object();

   private int clients = 0;
   private Date used = null;
   private Date trxused = null;
   private Date connused = null;
   private boolean forcewrt = false;
   private Primary primary = new Primary();

   private JdbcInterface rconn = null;
   private JdbcInterface wconn = null;

   private HashMap<String,BindValue> coninfo = null;
   private HashMap<String,BindValue> vpdinfo = null;

   private final static AdvancedPool pool = Config.pool();
   private final static int contmout = Config.conTimeout();
   private final static int latency = Config.dbconfig().latency();
   private final static boolean usesec = Config.pool().secondary();


   public static Session get(String guid, boolean internal) throws Exception
   {
      Session session = State.getSession(guid);
      SessionInfo info = StatePersistency.getSession(guid);

      if (info != null && session == null)
      {
         session = new Session(info);

         if (session != null)
         {
            session.vpdinfo = StatePersistency.getVPDInfo(guid);
            session.coninfo = StatePersistency.getClientInfo(guid);
         }

         if (!internal && info.owner && !info.online && session.hasTrx())
         {
            StatePersistency.removeTransaction(guid);
            throw new TransactionLost(Messages.get("TRANSACTION_LOST"));
         }

         if (info.online && !info.owner)
            Config.logger().info(Messages.get("NOT_SESSION_OWNER",guid,info.inst));

         if (!info.online)
         {
            Config.logger().info(Messages.get("SESSION_REINSTATED",guid));
            session.transfer();
         }

         State.addSession(session);
      }

      return(session);
   }


   public static Session create(String user, boolean stateful) throws Exception
   {
      String guid = StatePersistency.createSession(user,stateful);
      Session session = new Session(guid,user,stateful);
      State.addSession(session);
      return(session);
   }


   public static boolean remove(Session session)
   {
      if (State.removeSession(session.guid))
      {
         StatePersistency.removeSession(session.guid);
         return(true);
      }

      return(false);
   }


   private Session(SessionInfo info) throws Exception
   {
      this.guid = info.guid;
      this.user = info.user;
      this.inst = info.inst;

      this.used = new Date();

      this.stateful = info.stateful;
      this.forward = info.online && !info.owner;
   }


   private Session(String guid, String user, boolean stateful) throws Exception
   {
      this.guid = guid;
      this.user = user;

      this.forward = false;
      this.stateful = stateful;

      this.used = new Date();
      this.inst = Config.inst();
   }

   public Session up()
   {
      synchronized(SYNC)
      { clients++; }
      return(this);
   }

   public int clients()
   {
      synchronized(SYNC)
      {return(clients);}
   }

   public Session down()
   {
      synchronized(SYNC)
      { clients--; }

      try
      {
         if (contmout <= 0)
            this.release(-1);
      }
      catch (Throwable t)
      {
         Config.logger().log(Level.SEVERE,t.toString(),t);
      }

      return(this);
   }

   public String inst()
   {
      return(inst);
   }

   public String guid()
   {
      return(guid);
   }

   public String user()
   {
      return(user);
   }

   public boolean forward()
   {
      return(forward);
   }

   public boolean isStateful()
   {
      return(stateful);
   }


   public Date lastUsed()
   {
      synchronized(SYNC)
       {return(used);}
   }

   public Date lastUsedTrx()
   {
      synchronized(SYNC)
      {return(trxused);}
   }

   public Date lastUsedConn()
   {
      synchronized(SYNC)
      {return(connused);}
   }

   public HashMap<String,BindValue> getVPDInfo()
   {
      synchronized(SYNC)
      {return(vpdinfo);}
   }

   public HashMap<String,BindValue> getClientInfo()
   {
      synchronized(SYNC)
      {return(coninfo);}
   }

   public Session setVPDInfo(HashMap<String,BindValue> values) throws Exception
   {
      synchronized(SYNC)
      {
         if (this.vpdinfo == null)
         this.vpdinfo = new HashMap<String,BindValue>();

         this.vpdinfo.putAll(values);
         StatePersistency.setVPDInfo(guid,vpdinfo);

         return(this);
      }
   }

   public Session setClientInfo(HashMap<String,BindValue> values) throws Exception
   {
      synchronized(SYNC)
      {
         if (this.coninfo == null)
         this.coninfo = new HashMap<String,BindValue>();

         this.coninfo.putAll(values);
         StatePersistency.setClientInfo(guid,coninfo);

         if (this.rconn != null)
            this.rconn.setClientInfo(this.coninfo);

         if (this.wconn != null)
            this.wconn.setClientInfo(this.coninfo);

         return(this);
      }
   }

   public synchronized void transfer() throws Exception
   {
      boolean move = this.inst.equals(Config.inst());

      if (!move) Config.logger().info(Messages.get("SESSION_REINSTATED",guid));
      else Config.logger().info(Messages.get("TRANSFER_SESSION",guid,this.inst));

      StatePersistency.transferSession(guid,this.user,this.stateful);
      this.inst = Config.inst();
   }

   public synchronized boolean touch() throws Exception
   {
      synchronized(SYNC) {this.used = new Date();}
      boolean success = StatePersistency.touchSession(guid);
      return(success);
   }


   public synchronized boolean hasTrx() throws Exception
   {
      return(StatePersistency.getTransaction(guid) != null);
   }


   public synchronized TransactionInfo touchTrx() throws Exception
   {
      this.trxused = new Date();
      TransactionInfo info = StatePersistency.touchTransaction(guid,trxused);
      return(info);
   }


   public synchronized Cursor getCursor(String cursid) throws Exception
   {
      Cursor cursor = State.getCursor(this,cursid);

      if (cursor == null)
      {
         cursor = Cursor.load(this,cursid);
         if (cursor == null) return(null);

         JdbcInterface read = ensure(false,!cursor.primary());

         long time = System.nanoTime();
         read.executeQuery(cursor,false);
         cursor.excost(System.nanoTime()-time);

         cursor.primary(forcewrt);
         State.addCursor(cursor);

         time = System.nanoTime();
         cursor.position();
         cursor.ftccost(System.nanoTime()-time);
      }

      synchronized(SYNC)
      {
         used = new Date();
         connused = new Date();
      }

      return(cursor);
   }


   public synchronized void useSecondary(Cursor cursor) throws Exception
   {
      if (!cursor.primary())
         return;

      JdbcInterface read = ensure(false);

      if (!forcewrt)
      {
         long time = System.nanoTime();
         read.executeQuery(cursor,false);
         cursor.excost(System.nanoTime()-time);

         cursor.primary(false);

         time = System.nanoTime();
         cursor.position();
         cursor.ftccost(System.nanoTime()-time);
      }
   }


   public boolean isConnected()
   {
      synchronized(SYNC)
      {
         if (wconn != null && wconn.isConnected()) return(true);
         if (rconn != null && rconn.isConnected()) return(true);
         return(false);
      }
   }


   public synchronized boolean removeForeign() throws Exception
   {
      if (this.inst.equals(Config.inst()))
         return(false);

      if (wconn != null || rconn != null)
         return(false);

      return(State.removeSession(guid));
   }

   public synchronized boolean release(int idle) throws Exception
   {
      ArrayList<Cursor> cursors = State.getAllCursors(guid);

      long used = lastUsed().getTime();
      long curr = (new Date()).getTime();

      if (this.trxused != null)
         return(false);

      if (idle >= 0 && curr - used < idle)
         return(false);

      for (Cursor cursor : cursors)
         cursor.offline();

      if (wconn != null && wconn.isConnected())
      {
         wconn.clearClientInfo(coninfo);
         try {wconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
      }

      if (rconn != null && rconn.isConnected())
      {
         rconn.clearClientInfo(coninfo);
         try {rconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
      }

		// No reason to hold it in memory, anyone can pick it up

		if (!State.removeSession(guid))
      {
         Config.logger().warning(Messages.get("DISC_WITH_CLIENTS",guid,clients));
         return(false);
      }

      StatePersistency.releaseSession(this.guid,this.user,this.stateful);
      return(true);
   }


   public synchronized boolean releaseWrite() throws Exception
   {
      long now = (new Date()).getTime();

      if (now - this.primary.lastused <= latency)
         return(false);

      if (wconn == null || !wconn.isConnected())
         return(false);

      wconn.clearClientInfo(coninfo);
      try {wconn.disconnect();} catch (Exception e)
      {Config.logger().log(Level.SEVERE,e.toString(),e);}

      ArrayList<Cursor> cursors = State.getAllCursors(guid);

      for (Cursor cursor : cursors)
      {
         if (cursor.primary())
            cursor.offline();
      }

      CloseAsap.remove(this);
      return(true);
   }


   public synchronized boolean disconnect() throws Exception
   {
      ArrayList<Cursor> cursors = State.getAllCursors(guid);

      if (!State.removeSession(guid))
      {
         Config.logger().warning(Messages.get("DISC_WITH_CLIENTS",guid,clients));
         return(false);
      }

      for(Cursor curs : cursors)
         curs.release();

      if (wconn != null)
      {
         wconn.clearClientInfo(coninfo);
         try {wconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
         wconn = null;
      }

      if (rconn != null)
      {
         rconn.clearClientInfo(coninfo);
         try {rconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
         rconn = null;
      }

      boolean success = StatePersistency.removeSession(guid);

      return(success);
   }


   public synchronized boolean commit() throws Exception
   {
      Date now = new Date();
      StatePersistency.removeTransaction(this.guid);
      boolean success = StatePersistency.touchSession(guid);

      synchronized(SYNC)
      {
         used = now;
         connused = now;
         trxused = null;
         primary.endtrx();
      }

      if (wconn != null)
      {
         wconn.commit();
         return(success);
      }

      return(true);
   }


   public synchronized boolean rollback() throws Exception
   {
      Date now = new Date();
      StatePersistency.removeTransaction(this.guid);
      boolean success = StatePersistency.touchSession(guid);

      synchronized(SYNC)
      {
         used = now;
         connused = now;
         trxused = null;
         primary.endtrx();
      }

      if (wconn != null)
      {
         wconn.rollback();
         return(success);
      }

      return(true);
   }


   public synchronized boolean execute(String sql, boolean write, boolean savepoint) throws Exception
   {
      if (!stateful) savepoint = false;
      JdbcInterface conn = ensure(write);
      boolean success = conn.execute(sql,savepoint);

      if (write)
      {
         if (stateful) touchTrx();
         if (usesec) primary.dirty(stateful);
      }

      synchronized(SYNC)
      {
         used = new Date();
         connused = new Date();
      }

      return(success);
   }


   public synchronized JSONObject executeCall(String sql, ArrayList<BindValue> bindvalues, boolean write, boolean savepoint) throws Exception
   {
      if (!stateful) savepoint = false;
      JdbcInterface conn = ensure(write);
      JSONObject response = new JSONOObject();
      ArrayList<NameValuePair<Object>> resp = null;
      ArrayList<BindValue> outvals = new ArrayList<BindValue>();

      for(BindValue bv : bindvalues)
      {
         bv.validate();
         if (bv.out()) outvals.add(bv);
      }

      resp = conn.executeCall(sql,bindvalues,savepoint);

      if (write)
      {
         if (stateful) touchTrx();
         if (usesec) primary.dirty(stateful);
      }

      synchronized(SYNC)
      {
         used = new Date();
         connused = new Date();
      }

      int pos = 0;
      JSONArray returning = new JSONArray();

      for(NameValuePair<Object> nvp : resp)
      {
         JSONObject retval = new JSONObject();
         Integer sqltype = outvals.get(pos++).type();

         retval.put("name",nvp.name());
         retval.put("type",SQLTypes.getType(sqltype));
         retval.put("sqltype",sqltype);

         retval.put("value",nvp.value());
         returning.put(retval);
      }

      response.put("values",returning);

      return(response);
   }


   public synchronized Cursor executeQuery(String sql, ArrayList<BindValue> bindvalues) throws Exception
   {
      return(executeQuery(sql,bindvalues,true,false,0));
   }


   public synchronized Cursor executeQuery(String sql, ArrayList<BindValue> bindvalues, boolean savepoint, int pagesize) throws Exception
   {
      return(executeQuery(sql,bindvalues,false,savepoint,pagesize));
   }


   public synchronized Cursor executeQuery(String sql, ArrayList<BindValue> bindvalues, boolean forceread, boolean savepoint, int pagesize) throws Exception
   {
      if (!stateful) savepoint = false;
      JdbcInterface read = ensure(false,forceread);

      for(BindValue bv : bindvalues)
         bv.validate();

      Cursor cursor = Cursor.create(this,sql,bindvalues,pagesize);

      cursor.primary(forcewrt);
      long time = System.nanoTime();
      read.executeQuery(cursor,savepoint);
      cursor.excost(System.nanoTime()-time);

      synchronized(SYNC)
      {
         used = new Date();
         connused = new Date();
      }

      State.addCursor(cursor);
      return(cursor);
   }


   public synchronized JSONObject executeUpdate(String sql, ArrayList<BindValue> bindvalues, String[] returning, boolean savepoint) throws Exception
   {
      JSONObject response = null;
      if (!stateful) savepoint = false;
      JdbcInterface write = ensure(true);

      for(BindValue bv : bindvalues)
         bv.validate();

      UpdateResponse resp = write.executeUpdate(sql,bindvalues,returning,savepoint);

      response = new JSONOObject().put("affected",resp.affected);

      if (resp.affected > 0)
      {
         if (stateful) touchTrx();
         if (usesec) primary.dirty(stateful);
      }

      if (resp.returning != null)
      {
         JSONArray row = null;
         JSONArray rows = new JSONArray();

         for (int i = 0; i < resp.returning.size(); i++)
         {
            row = new JSONArray();
            Object[] values = resp.returning.get(i);
            for(Object value : values) row.put(value);
            rows.put(row);
         }

         response.put("rows",rows);
      }

      synchronized(SYNC)
      {
         used = new Date();
         connused = new Date();
      }

      return(response);
   }


   public boolean authenticate(String username, String password) throws Exception
   {
      if (username == null) return(false);
      if (password == null) return(false);
      return(pool.authenticate(username,password));
   }


   private synchronized JdbcInterface ensure(boolean write) throws Exception
   {
      return(ensure(write,false));
   }


   private synchronized JdbcInterface ensure(boolean write, boolean forceread) throws Exception
   {
      forcewrt = false;

      if (!usesec) write = true;
      else if (forceread) write = false;

      if (!forceread && usesec && primary.force(latency))
      {
         write = true;
         forcewrt = true;
      }

      if (write && wconn == null)
         wconn = JdbcInterface.getInstance(true);

      if (!write && rconn == null)
         rconn = JdbcInterface.getInstance(true);

      connused = new Date();

      if (write && wconn.isConnected())
         return(wconn);

      if (!write && rconn.isConnected())
         return(rconn);

      if (write)
      {
         wconn.connect(this.user,write,stateful);
         wconn.setClientInfo(coninfo);
         CloseAsap.add(this);
      }
      else
      {
         rconn.connect(this.user,write,stateful);
         rconn.setClientInfo(coninfo);
      }

      if (write) return(wconn);
      return(rconn);
   }


   public String toString()
   {
      boolean connected = false;

      if (wconn != null && wconn.isConnected()) connected = true;
      if (rconn != null && rconn.isConnected()) connected = true;

      int age = (int) ((new Date()).getTime() - used.getTime())/1000;

      return(this.guid+", age: "+age+"secs, conn: "+connected);
   }


   /**
    * If any updates is made on the primary database (autocommit or not)
    * All queries must be made against the primary database to ensure that
    * data is consistent.
    *
    * When data is committed (dirty = false), and the latency period has ended
    * we can resume to query the secondary database
    */
   private static class Primary
   {
      long lastused = 0;
      boolean dirty = false;

      void dirty(boolean stateful)
      {
         dirty = stateful;
         lastused = now();
      }

      void endtrx()
      {
         dirty = false;
         lastused = now();
      }

      boolean force(int latency)
      {
         if (!dirty && lastused > 0)
         {
            if ((now() - lastused) > latency)
               lastused = 0;
         }

         return(dirty || lastused > 0);
      }

      private long now()
      {
         return(new Date().getTime());
      }
   }


   public static class TransactionLost extends Exception
   {
      public TransactionLost(String message)
      {
         super(message);
      }
   }
}