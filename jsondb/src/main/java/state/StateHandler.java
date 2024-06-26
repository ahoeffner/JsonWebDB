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

package state;

import utils.Guid;
import java.io.File;
import jsondb.Config;
import messages.Messages;

import java.util.Date;
import utils.JSONOObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;


public class StateHandler
{
   private static long pid;

   private static String inst = null;
   private static String path = null;

   private static final String PID = "pid";
   private static final String SES = "ses";
   private static final String TRX = "trx";
   private static final String CUR = "cur";
   private static final String STATE = "state";


   public static void initialize() throws Exception
   {
      FileOutputStream pf = null;

      StateHandler.inst = Config.inst();
      StateHandler.path = Config.path(STATE);

      (new File(path)).mkdirs();

      StateHandler.pid = ProcessHandle.current().pid();

      pf = new FileOutputStream(pidFile(inst));
      pf.write((pid+"").getBytes()); pf.close();

      Thread shutdown = new Thread(() -> StateHandler.pidFile(inst).delete());
      Runtime.getRuntime().addShutdownHook(shutdown);
   }


   public static JSONObject list(String path) throws Exception
   {
      File root = new File(path);
      JSONOObject response = new JSONOObject();

      if (root.exists())
      {
         File[] state = root.listFiles();

         JSONArray processes = new JSONArray();
         response.put("processes",processes);

         for(File file : state)
         {
            if (file.getName().endsWith("."+PID))
            {
               String inst = file.getName();
               inst = inst.substring(0,inst.length()-PID.length()-1);

               FileInputStream in = new FileInputStream(file);
               String pid = new String(in.readAllBytes()); in.close();

               JSONOObject entry = new JSONOObject();
               entry.put("instance",inst);
               entry.put("process#",pid);
               processes.put(entry);
            }
         }

         JSONArray sessions = new JSONArray();
         response.put("sessions",sessions);

         for(File file : state)
         {
            if (!file.isDirectory())
               continue;

            JSONOObject entry = new JSONOObject();
            sessions.put(entry);

            File session = sesFile(root,file.getName());
            SessionInfo info = new SessionInfo(session);

            entry.put("session",info.guid);
            entry.put("accessed",info.age+" secs");
            entry.put("instance",info.inst);
            entry.put("username",info.user);

            /* Add cursors and transcactions
            File[] content = session.getParentFile().listFiles();

            for(File child : content)
            {
            }
            */
         }
      }

      return(response);
   }

   public static SessionInfo getSession(String session) throws Exception
   {
      File file = sesFile(session);
      if (!file.exists()) return(null);
      SessionInfo info = new SessionInfo(file);
      file.setLastModified(System.currentTimeMillis());
      return(info);
   }


   public static String createSession(String username, boolean dedicated) throws Exception
   {
      boolean done = false;
      String session = null;

      while (!done)
      {
         session = Guid.generate();
         FileOutputStream out = null;

         File file = sesFile(session);

         if (!file.exists())
         {
            done = true;
            sesPath(session).mkdirs();
            out = new FileOutputStream(file);
            out.write((username+" "+inst+" "+dedicated).getBytes()); out.close();
         }
      }

      return(session);
   }


   public static boolean removeSession(String session)
   {
      File file = sesFile(session);
      if (!file.exists()) return(false);

      file = file.getParentFile();

      File[] content = file.listFiles();
      for(File child : content) child.delete();

      file.delete();
      return(true);
   }


   public static boolean touchSession(String session)
   {
      File file = sesFile(session);
      if (!file.exists()) return(false);

      file.setLastModified((new Date()).getTime());
      return(true);
   }


   public static boolean createTransaction(String session, String inst) throws Exception
   {
      File file = trxFile(session);
      if (file.exists()) return(false);

      FileOutputStream out = new FileOutputStream(file);
      out.write((inst+" "+StateHandler.pid).getBytes()); out.close();

      return(true);
   }


   public static TransactionInfo touchTransaction(String session, Date start) throws Exception
   {
      TransactionInfo info = null;
      File file = trxFile(session);

      if (file.exists())
      {
         info = new TransactionInfo(file);
         file.setLastModified(start.getTime());
      }

      return(info);
   }


   public static TransactionInfo getTransaction(String session) throws Exception
   {
      TransactionInfo info = null;
      File file = trxFile(session);

      if (file.exists())
         info = new TransactionInfo(file);

      return(info);
   }


   public static boolean removeTransaction(String session) throws Exception
   {
      File file = trxFile(session);
      if (file.exists()) return(false);
      file.delete();
      return(true);
   }


   public static boolean createCursor(String sessid, String cursid, byte[] bytes) throws Exception
   {
      File file = curFile(sessid,cursid);
      if (file.exists()) return(false);

      FileOutputStream out = new FileOutputStream(file);
      out.write(bytes); out.close();

      return(true);
   }


   public static byte[] getCursor(String sessid, String cursid) throws Exception
   {
      File file = curFile(sessid,cursid);
      if (!file.exists()) return(null);

      FileInputStream in = new FileInputStream(file);
      byte[] bytes = in.readAllBytes(); in.close();

      return(bytes);
   }


   public static boolean removeCursor(String sessid, String cursid) throws Exception
   {
      File file = curFile(sessid,cursid);
      if (!file.exists()) return(false);
      file.delete();
      return(true);
   }


   public static byte[] peekCursor(String sessid, String cursid, int len) throws Exception
   {
      File file = curFile(sessid,cursid);
      if (!file.exists()) return(null);

      byte[] bytes = new byte[len];
      FileInputStream in = new FileInputStream(file);
      int read = in.read(bytes); in.close();

      if (read != len)
         throw new Exception(Messages.get("FILE_CORRUPTION",len,file.toString()));

      return(bytes);
   }


   public static boolean updateCursor(String sessid, String cursid, byte[] cpos, byte[] pgsz) throws Exception
   {
      File file = curFile(sessid,cursid);
      if (!file.exists()) return(false);

      byte[] bytes = new byte[cpos.length+pgsz.length];
      System.arraycopy(cpos,0,bytes,0,cpos.length);
      System.arraycopy(pgsz,0,bytes,8,pgsz.length);

      RandomAccessFile raf = new RandomAccessFile(file,"rw");
      raf.write(bytes); raf.close();
      
      return(true);
   }


   private static File pidFile(String inst)
   {
      return(new File(Config.path(STATE,inst+"."+PID)));
   }


   private static File curFile(String session, String cursor)
   {
      return(new File(Config.path(STATE,session,cursor+"."+CUR)));
   }


   private static File trxFile(String session)
   {
      return(new File(Config.path(STATE,session+"."+TRX)));
   }


   private static File sesFile(String session)
   {
      return(new File(Config.path(STATE,session,session+"."+SES)));
   }


   private static File sesFile(File parent, String session)
   {
      String path = parent.getPath()+File.separator+session;
      return(new File(path+File.separator+session+"."+SES));
   }


   private static File sesPath(String session)
   {
      return(new File(Config.path(STATE,session)));
   }


   public static void cleanout(long now, int timeout)
   {
      File root = new File(StateHandler.path);

      if (root.exists())
      {
         for(File file : root.listFiles())
         {
            if (!file.isDirectory())
               continue;

            File session = sesFile(file.getName());

            if (now - session.lastModified() > timeout)
            {
               File folder = session.getParentFile();

               File[] content = folder.listFiles();
               for(File child : content) child.delete();

               session.getParentFile().delete();
            }
         }
      }
   }


   public static class SessionInfo
   {
      public final long age;
      public final String guid;
      public final String user;
      public final String inst;
      public final boolean dedicated;

      private SessionInfo(File file) throws Exception
      {
         String guid = file.getName();
         long now = (new Date()).getTime();

         this.age = (int) (now - file.lastModified())/1000;
         this.guid = guid.substring(0,guid.length()-SES.length()-1);

         FileInputStream in = new FileInputStream(file);
         String content = new String(in.readAllBytes());
         in.close();

         String[] args = content.split(" ");

         this.user = args[0];
         this.inst = args[1];

         this.dedicated = Boolean.parseBoolean(args[2]);
      }
   }


   public static class TransactionInfo
   {
      public final long age;
      public final long pid;
      public final String guid;
      public final String user;
      public final String inst;

      private TransactionInfo(File file) throws Exception
      {
         String guid = file.getName();
         long now = (new Date()).getTime();

         this.age = (int) (now - file.lastModified())/1000;
         this.guid = guid.substring(0,guid.length()-SES.length()-1);

         FileInputStream in = new FileInputStream(file);
         String content = new String(in.readAllBytes());
         in.close();

         String[] args = content.split(" ");

         this.user = args[0];
         this.inst = args[1];

         this.pid = Long.parseLong(args[2]);
      }
   }
}