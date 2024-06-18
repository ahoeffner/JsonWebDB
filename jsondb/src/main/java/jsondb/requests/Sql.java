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

import jsondb.Session;
import jsondb.Config;
import jsondb.Response;
import utils.JSONOObject;
import database.Cursor;
import utils.Misc;
import sources.SQLSource;

import java.util.ArrayList;
import java.util.HashMap;
import database.BindValue;
import database.SQLPart;

import org.json.JSONArray;
import org.json.JSONObject;


public class Sql
{
   private final String sessid;
   private final String source;
   private final JSONObject definition;

   private static final String SOURCE = "source";
   private static final String INSERT = "insert";
   private static final String UPDATE = "update";
   private static final String DELETE = "delete";
   private static final String SELECT = "select";
   private static final String SESSION = "session";
   private static final String PAGESIZE = "page-size";
   private static final String SAVEPOINT = "savepoint";


   public Sql(JSONObject definition) throws Exception
   {
      this.definition = definition;
      source = definition.getString(SOURCE);
      sessid = definition.optString(SESSION);
   }


   public Response insert() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,INSERT);
      if (session == null) return(new Response(response));

      try
      {
         Forward fw = Forward.redirect(session,"Sql",definition);
         if (fw != null) return(new Response(fw.response()));
         return(insert(session));
      }
      finally
      {
         session.down();
      }
   }


   public Response insert(Session session) throws Exception
   {
      JSONObject response = new JSONOObject();

      SQLSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(exeupdate(INSERT,session,source));
   }


   public Response update() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,UPDATE);
      if (session == null) return(new Response(response));

      try
      {
         Forward fw = Forward.redirect(session,"Sql",definition);
         if (fw != null) return(new Response(fw.response()));
         return(update(session));
      }
      finally
      {
         session.down();
      }
   }


   public Response update(Session session) throws Exception
   {
      JSONObject response = new JSONOObject();

      SQLSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(exeupdate(UPDATE,session,source));
   }


   public Response delete() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,DELETE);
      if (session == null) return(new Response(response));

      try
      {
         Forward fw = Forward.redirect(session,"Sql",definition);
         if (fw != null) return(new Response(fw.response()));
         return(delete(session));
      }
      finally
      {
         session.down();
      }
   }


   public Response delete(Session session) throws Exception
   {
      JSONObject response = new JSONOObject();

      SQLSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(exeupdate(DELETE,session,source));
   }


   public Response select() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,SELECT);
      if (session == null) return(new Response(response));

      try
      {
         Forward fw = Forward.redirect(session,"Sql",definition);
         if (fw != null) return(new Response(fw.response()));
         return(select(session));
      }
      finally
      {
         session.down();
      }
   }


   public Response select(Session session) throws Exception
   {
      JSONObject response = new JSONOObject();

      SQLSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(select(session,source));
   }


   public Response select(Session session, SQLSource source) throws Exception
   {
      BindValue used = null;
      JSONObject response = new JSONOObject();
      JSONObject args = Utils.getMethod(definition,SELECT);
      HashMap<String,BindValue> values = Utils.getBindValues(args);

      SQLPart select = source.sql();
      boolean usecurs = source.cursor();

      for(BindValue def : select.bindValues())
      {
         used = values.get(def.name().toLowerCase());
         if (used != null) def.value(used.value());
      }

      select.bindByValue();

      boolean savepoint = Config.dbconfig().savepoint(false);
      if (args.has(SAVEPOINT)) savepoint = args.getBoolean(SAVEPOINT);

      Integer pagesize = Misc.get(args,PAGESIZE); if (pagesize == null) pagesize = 0;
      Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),savepoint,pagesize);

      JSONArray rows = new JSONArray();
      ArrayList<Object[]> table = cursor.fetch();

      if (!usecurs) cursor.close();

      else if (Config.conTimeout() <= 0)
         cursor.remove();

      response.put("success",true);
      response.put("session",sessid);
      response.put("method","select()");

      if (cursor.next())
      {
         response.put("more",true);
         response.put("cursor",cursor.guid());
      }

      if (cursor.primary())
         response.put("primary",true);

      response.put("rows",rows);
      for(Object[] row : table) rows.put(row);

      return(new Response(response));
   }


   public Response exeupdate(String method, Session session, SQLSource source) throws Exception
   {
      return(null);
   }
}