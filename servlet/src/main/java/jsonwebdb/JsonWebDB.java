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

package jsonwebdb;

import http.Admin;
import jsondb.Config;
import jsondb.JsonDB;
import jsondb.Response;
import http.HTTPConfig;
import http.AdminResponse;
import files.FileResponse;
import java.io.IOException;
import java.io.InputStream;
import multipart.Multipart;
import java.io.OutputStream;
import java.util.logging.Level;
import application.Application;
import http.AdminResponse.Header;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class JsonWebDB extends HttpServlet
{
   private static String JSONType = null;

   public void init() throws ServletException
   {
      try {start();}
      catch (Exception e) {throw new ServletException(e);}
   }

   public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
   {
      Application appl = null;

      try {appl = Config.application();}
      catch (Exception e) {throw new IOException(e);}

      try
      {
         if (appl != null && appl.intercept(request,response))
            return;
      }
      catch (Exception e)
      {
         throw new IOException(e);
      }

      try {jsonwebdb(appl,request,response);}
      catch (Exception e) {throw new ServletException(e);}
   }


   public static void start() throws Exception
   {
      String home = System.getenv("JsonWebDB_Home");
      String inst = System.getenv("JsonWebDB_Inst");
      String conf = System.getenv("JsonWebDB_Config");

      JsonDB.initialize(home,inst,conf,false);

      JSONType = Config.getMimeType(".json");
      if (JSONType == null) JSONType = "application/json";
   }


   public void jsonwebdb(Application appl, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
   {
      JsonDB jsondb = new JsonDB();
      String meth = request.getMethod();

      if (meth.equals("GET"))
      {
         try
         {
            FileResponse file = null;
            String path = getPath(request);

            if (path.startsWith(HTTPConfig.admin()))
            {
               admin(path,request,response);
               return;
            }

            String vpath = HTTPConfig.getVirtual(path);
            if (vpath != null) path = vpath;

            if (!jsondb.modified(path,request.getHeader("If-modified-since")))
            {
               response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
               return;
            }

            file = jsondb.get(path);

            if (!file.exists())
            {
               response.setStatus(HttpServletResponse.SC_NOT_FOUND);
               return;
            }

            response.setContentType(file.mimetype);
            response.setHeader("Last-Modified",file.gmt());
            if (file.gzip) response.setHeader("Content-Encoding","gzip");

            OutputStream out = response.getOutputStream();
            out.write(file.content);
            out.close();
            return;
         }
         catch (Throwable t)
         {
            Config.logger().log(Level.SEVERE,t.getMessage(),t);
            throw new IOException(t);
         }
      }

      if (meth.equals("POST"))
      {
         String ctype = request.getContentType();

         if (ctype.startsWith("multipart/form-data"))
         {
            if (appl != null)
            {
               InputStream in = request.getInputStream();
               byte[] content = in.readAllBytes(); in.close();
               Multipart upload = new Multipart(ctype,content);

               try {Config.application().upload(request,response,upload);}
               catch (Exception e) {throw new IOException(e);}
            }

            return;
         }

         try
         {
            String body = getBody(request);
            Response json = jsondb.execute(appl,body);

            response.setContentType(JSONType);
            OutputStream out = response.getOutputStream();
            out.write(json.toString().getBytes());
            out.close();
            return;
         }
         catch (Throwable t)
         {
            Config.logger().log(Level.SEVERE,t.getMessage(),t);
            throw new IOException(t);
         }
      }

      throw new ServletException("Method '"+meth+"' not supported");
   }


   public void admin(String path ,HttpServletRequest request, HttpServletResponse response) throws IOException
   {
      try
      {
         if (HTTPConfig.adminSSLRequired() && !request.isSecure())
         {
            AdminResponse rsp = Admin.noSSLMessage();
            OutputStream out = response.getOutputStream();
            out.write(rsp.page);
            out.close();
         }

         boolean auth = Admin.isAdminUser(request.getHeader("Authorization"));

         if (!auth)
         {
            AdminResponse rsp = Admin.getBasicAuthMessage();
            response.setStatus(rsp.code);

            for (int i = 0; i < rsp.headers.size(); i++)
            {
               Header header = rsp.headers.get(i);
               response.setHeader(header.name,header.value);
            }

            return;
         }

         AdminResponse rsp = Admin.process(path);
         OutputStream out = response.getOutputStream();

         for (int i = 0; i < rsp.headers.size(); i++)
         {
            Header header = rsp.headers.get(i);
            response.setHeader(header.name,header.value);
         }

         response.setStatus(rsp.code);
         out.write(rsp.page);
         out.close();
      }
      catch (Throwable t)
      {
         Config.logger().log(Level.SEVERE,t.getMessage(),t);
         throw new IOException(t);
      }
   }


   private String getPath(HttpServletRequest request)
   {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (path.length() <= 1) path = HTTPConfig.index();
      return(path);
   }


   private String getBody(HttpServletRequest request) throws IOException
   {
      InputStream in = request.getInputStream();
      byte[] bytes = in.readAllBytes(); in.close();
      return(new String(bytes));
   }
}