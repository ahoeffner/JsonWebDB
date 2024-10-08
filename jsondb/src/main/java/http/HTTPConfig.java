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

import jsondb.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;


public class HTTPConfig
{
   private static final String PATH = "path";
   private static final String PAGE = "page";
   private static final String INDX = "index";
   private static final String AUTH = "admin";
   private static final String VIRT = "virtual";
   private static final String USER = "username";
   private static final String PASW = "password";
   private static final String APPL = "application";
   private static final String SSLR = "ssl-required";

   private static String usr = null;
   private static String pwd = null;
   private static String admin = null;
   private static String index = null;
   private static boolean sslreq = true;

   private static ArrayList<VirtualPath> virtual =
      new ArrayList<VirtualPath>();


   public static String username()
   {
      return(usr);
   }


   public static String password()
   {
      return(pwd);
   }


   public static void initialize()
   {
      JSONObject http = Config.get(APPL);
      JSONObject auth = Config.get(http,AUTH);

      HTTPConfig.usr = Config.get(auth,USER);
      HTTPConfig.pwd = Config.get(auth,PASW);

      HTTPConfig.admin = Config.get(auth,PATH);
      HTTPConfig.index = Config.get(http,INDX);

      HTTPConfig.sslreq = Config.get(auth,SSLR);

      if (!HTTPConfig.index.startsWith("/"))
         HTTPConfig.index = "/" + HTTPConfig.index;

      if (!HTTPConfig.admin.startsWith("/"))
         HTTPConfig.admin = "/" + HTTPConfig.admin;

      JSONArray arr = Config.get(http,VIRT);

      for (int i = 0; arr != null && i < arr.length(); i++)
      {
         JSONObject def = arr.getJSONObject(i);
         virtual.add(new VirtualPath(def.getString(PATH),def.getString(PAGE)));
      }
   }

   public static String admin()
   {
      return(admin);
   }

   public static String index()
   {
      return(index);
   }

   public static boolean adminSSLRequired()
   {
      return(sslreq);
   }

   public static String getVirtual(String path)
   {
      if (!path.startsWith("/"))
         path = "/"+path;

      for (int i = 0; i < virtual.size(); i++)
      {
         VirtualPath vp = virtual.get(i);
         if (path.startsWith(vp.path+"/")) return(vp.page);
         if (path.startsWith(vp.path+"?")) return(vp.page);
      }

      return(null);
   }


   private static class VirtualPath
   {
      public final String path;
      public final String page;

      private VirtualPath(String path, String page)
      {
         if (!page.startsWith("/")) page = "/" + page;

         path = path.toLowerCase();
         if (!path.endsWith("/")) path += "/";
         if (!path.startsWith("/")) path = "/" + path;

         this.path = path;
         this.page = page;
      }

      public String toString()
      {
         return(path+" -> "+page);
      }
   }
}