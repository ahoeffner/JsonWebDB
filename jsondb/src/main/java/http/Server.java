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

package http;

import utils.Misc;
import java.net.URL;
import java.io.File;
import jsondb.Config;
import jsondb.JsonDB;
import org.json.JSONObject;
import java.security.KeyStore;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import javax.net.ssl.SSLContext;
import java.security.SecureRandom;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;


public class Server
{
   private static final String SECTION = "embedded";
   private static final String SECURITY = "security";
   private static final String KEYSTORE = "keystore";

   private static final String PORT = "port";
   private static final String SSLPORT = "ssl";

   private static final String QUEUE = "queue-length";
   private static final String THREADS = "worker-threads";

   private static final String TYPE = "type";
   private static final String STORE = "file";
   private static final String PASSWORD = "password";

   private static int port;
   private static int sslport;

   private static int queue;
   private static int threads;

   private static String store;
   private static String storetype;

   public static void main(String[] args) throws Exception
   {
      String config = null;

      int arg = 0;
      int len = args.length;

      while (arg < len)
      {
         if (args[arg].equals("-c") || args[arg].equals("--config"))
         {
            if (args.length > arg)
            {
               len -= 2;
               config = args[arg+1];

               for (int j = arg; j < args.length; j++)
                  args[j] = j < args.length - 2 ? args[j+2] : null;
            }
            else
            {
               throw new Exception("--config requires name as argument");
            }

            continue;
         }

         arg++;
      }

      if (len != 2)
         syntax();

      String cmd = args[0];
      String inst = args[1];
      String root = findAppHome();
      JsonDB.initialize(root,inst,config,true);

      switch (cmd)
      {
         case "stop": stop();
            break;

         case "start": start();
            break;

         case "status": status();
            break;

         default: syntax();
            break;
      }
   }


   private static void syntax()
   {
      System.out.println();
      System.out.println();
      System.out.println("usage:    jsonwebdb [options] start|stop|status <instance>");
      System.out.println("options:      -c | --config <configuration>");
      System.out.println();
      System.exit(-1);
   }


   private static void start() throws Exception
   {
      InetSocketAddress addr = null;

      String password = loadServerConfig();
      SSLContext ctx = createSSLContext(password);

      if (port > 0)
      {
         addr = new InetSocketAddress(port);
         HttpServer defsrv = HttpServer.create(addr,queue);
         defsrv.setExecutor(Executors.newFixedThreadPool(threads));
         defsrv.createContext("/",new Handler());
         defsrv.start();
      }

      if (sslport > 0)
      {
         addr = new InetSocketAddress(sslport);
         HttpsServer secsrv = HttpsServer.create(addr,queue);
         secsrv.setHttpsConfigurator(new HttpsConfigurator(ctx));
         secsrv.setExecutor(Executors.newFixedThreadPool(threads));
         secsrv.createContext("/",new Handler());
         secsrv.start();
      }
   }


   private static void stop()
   {
      String server = Config.endp();
      String url = Misc.url(server,HTTPConfig.admin(),"stop");

      try
      {
         Client client = new Client(url);
         client.setAuthorizationHeader();

         String resp = new String(client.get());
         System.out.println(resp);
      }
      catch (Exception e)
      {
         System.err.println("'"+Config.inst()+"' is not running");
         System.exit(-1);
      }
   }


   private static void status()
   {
      String server = Config.endp();
      String url = Misc.url(server,HTTPConfig.admin(),"status");

      try
      {
         Client client = new Client(url);
         client.setAuthorizationHeader();

         String resp = new String(client.get());
         System.out.println(resp);
      }
      catch (Exception e)
      {
         System.err.println("Failed to get status for server: "+e.toString());
      }
   }


   private static SSLContext createSSLContext(String password) throws Exception
   {
      String path = Config.path(SECURITY,store);

      KeyStore ks = KeyStore.getInstance(storetype);
      FileInputStream in = new FileInputStream(path);
      ks.load(in,password.toCharArray()); in.close();

      String kalg = KeyManagerFactory.getDefaultAlgorithm();
      KeyManagerFactory kfac = KeyManagerFactory.getInstance(kalg);
      kfac.init(ks,password.toCharArray());

      String talg = TrustManagerFactory.getDefaultAlgorithm();
      TrustManagerFactory tfac = TrustManagerFactory.getInstance(talg);
      tfac.init(ks);

      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kfac.getKeyManagers(),tfac.getTrustManagers(),new SecureRandom());

      return(ctx);
   }


   private static String loadServerConfig()
   {
      JSONObject conf = Config.get(SECTION);
      JSONObject instance = Config.instance();

      port = Config.get(instance,PORT);
      sslport = Config.get(instance,SSLPORT);

      queue = Config.get(instance,QUEUE);
      threads = Config.get(instance,THREADS);

      JSONObject ssl = Config.get(conf,KEYSTORE);

      store = Config.get(ssl,STORE);
      storetype = Config.get(ssl,TYPE);
      String password = Config.get(ssl,PASSWORD);

      return(password);
   }


   public static String findAppHome()
   {
      Object obj = new Object() { };

      String cname = obj.getClass().getEnclosingClass().getName();
      cname = "/" + cname.replace('.','/') + ".class";

      URL url = obj.getClass().getResource(cname);
      String path = url.getPath();

      if (url.getProtocol().equals("jar") || url.getProtocol().equals("code-source"))
      {
         path = path.substring(5); // get rid of "file:"
         path = path.substring(0,path.indexOf("!")); // get rid of "!class"
         path = path.substring(0,path.lastIndexOf("/")); // get rid of jarname
      }

      int pos = 0;
      File folder = null;
      HasConfigFolder check = new HasConfigFolder();

      while(pos >= 0)
      {
         folder = new File(path);

         if (folder.isDirectory() && folder.listFiles(check).length == 1)
            break;

         pos = path.lastIndexOf("/");
         path = path.substring(0,pos);
      }

      return(path);
   }


   private static class HasConfigFolder implements FilenameFilter
   {
      @Override
      public boolean accept(File dir, String name)
         {return(name.equals("config"));}
   }
}