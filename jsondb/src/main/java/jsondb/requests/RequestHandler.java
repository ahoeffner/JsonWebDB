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

package jsondb.requests;

import utils.Misc;
import jsondb.Config;
import jsondb.Response;
import messages.Messages;
import utils.JSONOObject;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.logging.Level;
import java.lang.reflect.Method;


public class RequestHandler
{
   private static final HashMap<String,Class<?>> classes =
      new HashMap<String,Class<?>>()
      {{
         put("table",Table.class);
         put("call",Function.class);
         put("cursor",Cursor.class);
         put("session",Session.class);
         put("sql",SQLStatement.class);
      }};


   public static Response handle(JSONObject request)
   {
      return(handle(request,null));
   }


   public static Response handle(JSONObject request, Integer methno)
   {
      String invk = null;

      String[] methods = new String[0];
      String names[] = JSONObject.getNames(request);
      JSONObject payload = request.getJSONObject(names[0]);

      try
      {
         methods = Misc.getStringArray(payload,"invoke",true);
      }
      catch (Exception e)
      {
         String msg = Messages.get("MISSING_INVOKE");
         Exception ex = new Exception(msg);

         Response response = new Response().exception(ex);
         Config.logger().log(Level.WARNING,msg,ex);

         return(response);
      }

      if (methno == null && methods.length == 1)
         methno = 0;

      if (methno == null && methods.length > 1)
      {
         JSONArray steps = new JSONArray();
         JSONOObject response = new JSONOObject();

         response.put("steps",steps);
         Response merged = new Response(response);

         for (int i = 0; i < methods.length; i++)
         {
            Response resp = handle(request,i);
            resp.put(1,"method",methods[i]);

            steps.put(resp.payload());
            merged.exception(resp.exception());

            if (!resp.payload().getBoolean("success"))
               return(merged);
         }

         return(merged);
      }

      if (names != null && names.length == 1)
      {
         try
         {
            invk = methods[methno];

            if (invk.indexOf("()") > 0)
               invk = invk.substring(0,invk.length()-2);

            Object dbrq = getInstance(names[0],payload);
            Method method = dbrq.getClass().getMethod(invk);
            return((Response) method.invoke(dbrq));
         }
         catch (Throwable t)
         {
            if (t.getCause() != null) t = t.getCause();
            Config.logger().log(Level.WARNING,t.toString(),t);

            Response response = new Response().exception(t);
            response.exception(t).put("method",invk+"()");

            return(response);
         }
      }
      else
      {
         Exception t = new Exception(Messages.get("INVALID_REQUEST",request.toString(2)));

         Response response = new Response().exception(t);
         response.exception(t).put("method",invk+"()");

         return(response);
      }
   }


   private static Object getInstance(String name, JSONObject definition) throws Exception
   {
      name = name.toLowerCase();
      Class<?> clazz = classes.get(name);
      if (clazz == null) throw new Exception(Messages.get("UNKNOWN_REQUEST_TYPE",name));
      return(clazz.getConstructor(JSONObject.class).newInstance(definition));
   }
}