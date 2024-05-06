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
import database.BindValue;
import java.util.ArrayList;
import database.JdbcInterface;
import state.StatePersistency;
import java.util.logging.Level;
import database.definitions.AdvancedPool;
import state.StatePersistency.SessionInfo;
import state.StatePersistency.TransactionInfo;


public class Session
{
   private final int idle;
   private final String guid;
   private final String user;
   private final boolean stateful;
   private final AdvancedPool pool;

   private Date used = null;
   private Date trxused = null;
   private Date connused = null;
   private boolean inuse = false;
   private JdbcInterface rconn = null;
   private JdbcInterface wconn = null;

   public static Session get(String guid) throws Exception
   {
      Session session = State.getSession(guid);
      SessionInfo info = StatePersistency.getSession(guid);

      if (info != null && session == null)
      {
         boolean moved = !info.inst.equals(Config.inst());

         session = new Session(info.guid,info.user,info.stateful);
         State.addSession(session);

         String msg = "Reinstate session "+guid;
         if (moved) msg += ", previous running @"+info.inst;

         Config.logger().info(msg);
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
      return(State.removeSession(session.guid));
   }


   private Session(String guid, String user, boolean stateful) throws Exception
   {
      this.guid = guid;
      this.user = user;
      this.stateful = stateful;

      this.used = new Date();
      this.pool = Config.pool();
      this.idle = Config.conTimeout();
   }

   public String guid()
   {
      return(guid);
   }

   public String user()
   {
      return(user);
   }

   public boolean isStateful()
   {
      return(stateful);
   }

   public boolean inUse()
   {
      return(inuse);
   }

   public void inUse(boolean inuse)
   {
      this.inuse = inuse;
   }

   public synchronized Date lastUsed()
   {
      return(used);
   }

   public synchronized Date lastUsedTrx()
   {
      return(trxused);
   }

   public synchronized Date lastUsedConn()
   {
      return(connused);
   }


   public synchronized boolean touch() throws Exception
   {
      this.used = new Date();
      boolean success = StatePersistency.touchSession(guid);
      return(success);
   }


   public synchronized TransactionInfo touchTrx() throws Exception
   {
      this.trxused = new Date();
      TransactionInfo info = StatePersistency.touchTransaction(guid,trxused);
      return(info);
   }


   public Cursor getCursor(String cursid) throws Exception
   {
      Cursor cursor = State.getCursor(cursid);

      if (cursor == null)
      {
         cursor = Cursor.load(this,cursid);
         if (cursor == null) return(null);

         JdbcInterface read = ensure(false);
         read.executeQuery(cursor,false);

         State.addCursor(cursor);
         cursor.position();
      }

      return(cursor);
   }


   public synchronized boolean isConnected()
   {
      if (wconn != null && wconn.isConnected()) return(true);
      if (rconn != null && rconn.isConnected()) return(true);
      return(false);
   }


   public synchronized boolean release()
   {
      long used = lastUsed().getTime();
      long curr = (new Date()).getTime();

      if (this.trxused != null) return(false);
      if (curr - used < idle*1000) return(false);

      if (wconn != null && wconn.isConnected())
      {
         try {wconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
      }

      if (rconn != null && rconn.isConnected())
      {
         try {rconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
      }

      Cursor[] cursors = State.removeAllCursors(guid);

      for (int i = 0; i < cursors.length; i++)
      {
         if (cursors[i] != null)
            cursors[i].close(false);
      }

      trxused = null;
      connused = null;

      return(true);
   }


   public Session connect() throws Exception
   {
      touch();

      if (wconn == null)
         wconn = JdbcInterface.getInstance(true);

      if (pool.secondary())
      {
         if (rconn == null)
            rconn = JdbcInterface.getInstance(false);
      }

      return(this);
   }


   public boolean disconnect()
   {
      if (wconn != null)
      {
         try {wconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
         wconn = null;
      }

      if (rconn != null)
      {
         try {rconn.disconnect();} catch (Exception e)
         {Config.logger().log(Level.SEVERE,e.toString(),e);}
         rconn = null;
      }

      State.removeSession(guid);
      boolean success = StatePersistency.removeSession(guid);

      trxused = null;
      connused = null;

      return(success);
   }


   public boolean authenticate(String username, String password) throws Exception
   {
      if (username == null) return(false);
      if (password == null) return(false);
      return(pool.authenticate(username,password));
   }


   public boolean commit() throws Exception
   {
      StatePersistency.removeTransaction(this.guid);
      boolean success = StatePersistency.touchSession(guid);

      trxused = null;

      if (wconn != null)
      {
         wconn.commit();
         return(success);
      }

      return(true);
   }


   public boolean rollback() throws Exception
   {
      StatePersistency.removeTransaction(this.guid);
      boolean success = StatePersistency.touchSession(guid);

      trxused = null;

      if (wconn != null)
      {
         wconn.rollback();
         return(success);
      }

      return(true);
   }


   public Cursor executeQuery(String sql, ArrayList<BindValue> bindvalues, boolean savepoint, int pagesize) throws Exception
   {
      JdbcInterface read = ensure(false);

      for(BindValue bv : bindvalues)
         bv.validate();

      Cursor cursor = Cursor.create(this,sql,bindvalues,pagesize);

      read.executeQuery(cursor,savepoint);
      State.addCursor(cursor);

      return(cursor);
   }


   private synchronized JdbcInterface ensure(boolean write) throws Exception
   {
      if (!write && !pool.secondary())
         write = true;

      if (write && wconn == null)
         wconn = JdbcInterface.getInstance(true);

      if (!write && rconn == null)
         rconn = JdbcInterface.getInstance(true);

      connused = new Date();

      if (write && wconn.isConnected())
         return(wconn);

      if (!write && rconn.isConnected())
         return(rconn);

      if (write)  return(wconn.connect(this.user,write,stateful));
      else        return(rconn.connect(this.user,write,stateful));
   }


   public String toString()
   {
      boolean connected = false;

      if (wconn != null && wconn.isConnected()) connected = true;
      if (rconn != null && rconn.isConnected()) connected = true;

      int age = (int) ((new Date()).getTime() - used.getTime())/1000;

      return(this.guid+", age: "+age+"secs, conn: "+connected);
   }
}