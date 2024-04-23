/*
  MIT License

  Copyright © 2023 Alex Høffner

  Permission is hereby granted, free of charge, to any person obtaining a copy of this software
  and associated documentation files (the “Software”), to deal in the Software without
  restriction, including without limitation the rights to use, copy, modify, merge, publish,
  distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
  Software is furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all copies or
  substantial portions of the Software.

  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
  BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package jsondb;

import java.util.Date;
import java.util.logging.Level;
import jsondb.state.StateHandler;


public class Monitor extends Thread
{
   private static final int MAXINT = 60000;


   public static void monitor() throws Exception
   {
      (new Monitor()).start();
   }

   private Monitor()
   {
      this.setDaemon(true);
      this.setName(this.getClass().getName());
   }

   public void run()
   {
      int contmout = Config.conTimeout() * 1000;
      int trxtmout = Config.trxTimeout() * 1000;
      int sestmout = Config.sesTimeout() * 1000;

      int interval = Math.min(contmout,sestmout);
      interval = sestmout > MAXINT*2 ? MAXINT : (int) (3.0/4*sestmout);

      Config.logger().info(this.getClass().getSimpleName()+" running every "+interval/1000+" secs");

      while (true)
      {
         try
         {
            long now = (new Date()).getTime();
            StateHandler.cleanout(now,sestmout);

            Thread.sleep(interval);
            cleanout(now,sestmout,contmout,trxtmout);
         }
         catch (Throwable t)
         {
            Config.logger().log(Level.SEVERE,t.toString(),t);
         }
      }
   }

   private void cleanout(long now, int sestmout, int contmout, int trxtmout) throws Exception
   {
      for(Session session : Session.getAll())
      {
         Date lastUsed = session.lastUsed();
         Date lastTrxUsed = session.lastUsedTrx();
         Date lastConnUsed = session.lastUsedConn();

         boolean ses = sestmout > 0;
         boolean trx = lastTrxUsed != null && trxtmout > 0;
         boolean con = session.isConnected() && contmout > 0;

         if (trx && (now - lastTrxUsed.getTime() > trxtmout))
         {
            Config.logger().info(session.getGuid()+" rollback");
            session.rollback();
         }

         if (con && (now - lastConnUsed.getTime() > contmout))
         {
            Config.logger().info(session.getGuid()+" release connection");
            session.release();
         }

         if (ses && (now - lastUsed.getTime() > sestmout))
         {
            Config.logger().info(session.getGuid()+" disconnect");
            session.disconnect();
         }
      }
   }
}
