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
import jsondb.Session;
import sources.Source;
import database.Cursor;
import sources.Sources;
import jsondb.Response;
import database.Column;
import database.SQLPart;
import database.SQLTypes;
import database.DataType;
import utils.JSONOObject;
import java.util.HashMap;
import messages.Messages;
import database.BindValue;
import org.json.JSONArray;
import sources.TableSource;
import utils.NameValuePair;
import java.util.ArrayList;
import org.json.JSONObject;
import filters.WhereClause;
import filters.WhereClause.Context;
import sources.TableSource.AccessType;


public class Table
{
   private final String sessid;
   private final String source;
   private final JSONObject definition;

   private static final String ORDER = "order";
   private static final String VALUE = "value";
   private static final String SOURCE = "source";
   private static final String COLUMN = "column";
   private static final String CURSOR = "cursor";
   private static final String HEADING = "heading";
   private static final String SELECT = "select()";
   private static final String COLUMNS = "columns";
   private static final String FILTERS = "filters";
   private static final String SESSION = "session";
   private static final String PAGESIZE = "page-size";
   private static final String SAVEPOINT = "savepoint";
   private static final String FORUPDATE = "for-update";
   private static final String ASSERTIONS = "assertions";


   public Table(JSONObject definition) throws Exception
   {
      this.definition = definition;
      source = definition.getString(SOURCE);
      sessid = definition.getString(SESSION);
   }


   public Response describe() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,"describe()");
      if (session == null) return(new Response(response));

      try {return(describe(session));}
      finally { session.down(); }
   }


   public Response describe(Session session) throws Exception
   {
      JSONObject response = new JSONOObject();

      TableSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(describe(session,source));
   }


   public Response describe(Session session, TableSource source) throws Exception
   {
      JSONObject response = new JSONOObject();

      response.put("success",true);

      if (!source.described())
      {
         // We only need to describe it once
         synchronized(source)
         {
            if (!source.described())
            {
               getQRYColumns(session,source);

               if (source.queryBased() && source.hasBaseObject())
                  getBASEColumns(session,source);

               if (source.primarykey.size() == 0)
                  getPrimaryKey(session,source);
            }
         }
      }

      JSONArray rows = new JSONArray();
      ArrayList<DataType> columns = source.getColumns(true);

      if (source.order != null)
         response.put("order",source.order);

      if (source.primarykey.size() > 0)
         response.put("primary-key",source.primarykey);

      for (int i = 0; i < columns.size(); i++)
         rows.put(((Column) columns.get(i)).toJSONObject());

      response.put("rows",rows);
      return(new Response(response));
   }


   public Response select() throws Exception
   {
      JSONObject response = new JSONOObject();

      Session session = Utils.getSession(response,sessid,"select()");
      if (session == null) return(new Response(response));

      try
      {
         Forward fw = Forward.redirect(session,"Table",definition);
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

      TableSource source = Utils.getSource(response,this.source);
      if (source == null) return(new Response(response));

      return(select(session,source));
   }


   public Response select(Session session, TableSource source) throws Exception
   {
      JSONObject response = new JSONOObject();

      AccessType limit = source.getAccessLimit("select");
      if (limit == AccessType.denied) throw new Exception(Messages.get("ACCESS_DENIED"));

      if (!source.described())
         this.describe(session,source);

      HashMap<String,BindValue> bindvalues =
         Utils.getBindValues(definition);

      JSONObject args = definition.getJSONObject(SELECT);

      Boolean heading = Misc.get(args,HEADING);
      if (heading == null) heading = false;

      Boolean usecurs = Misc.get(args,CURSOR);
      if (usecurs == null) usecurs = true;

      Boolean lock = Misc.get(args,FORUPDATE);
      if (lock == null) lock = false;

      String order = Misc.getString(args,ORDER,false);

      String[] columns = Misc.getJSONList(args,COLUMNS,String.class);
      String stmt = "select "+Utils.getColumnList(columns);

      SQLPart select = new SQLPart(stmt);
      select.append(source.from(bindvalues));

      Context context = new Context(session,source,true);

      WhereClause whcl = new WhereClause(context,args);
      WhereClause asrt = getAssertClause(context,args);

      if (limit == AccessType.ifwhereclause && !whcl.exists())
         throw new Exception(Messages.get("NO_WHERE_CLAUSE"));

      if (limit == AccessType.byprimarykey && !whcl.usesPrimaryKey(source.primarykey))
         throw new Exception(Messages.get("WHERE_PRIMARY_KEY",Messages.flatten(source.primarykey)));

      SQLPart wh = whcl.asSQL();
      if (asrt != null) wh = whcl.append(asrt);

      select.append(wh);

      if (source.vpd != null && source.vpd.appliesTo("select"))
      {
         SQLPart vpdflt = source.vpd.bind(bindvalues);
         if (whcl.exists()) select.append("\nand",vpdflt);
         else select.append("\nwhere",vpdflt);
      }

      if (order != null) select.append("\norder by "+order);
      if (lock && session.isStateful()) select.append("\nfor update");

      boolean savepoint = Config.dbconfig().savepoint(false);
      if (args.has(SAVEPOINT)) savepoint = args.getBoolean(SAVEPOINT);

      Integer pagesize = Misc.get(args,PAGESIZE); if (pagesize == null) pagesize = 0;
      Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),savepoint,pagesize);

      JSONArray rows = new JSONArray();
      ArrayList<Object[]> table = cursor.fetch();

      ArrayList<Column> sellist = null;

      if (heading)
      {
         sellist = cursor.describe();

         columns = new String[sellist.size()];
         for (int i = 0; i < columns.length; i++)
            columns[i] = sellist.get(i).name;
      }

      if (!usecurs) cursor.close();

      else if (Config.conTimeout() <= 0)
         cursor.remove();

      response.put("success",true);
      if (heading) response.put("columns",columns);

      if (cursor.next())
      {
         response.put("more",true);
         response.put("cursor",cursor.guid());
      }

      response.put("rows",rows);
      for(Object[] row : table) rows.put(row);

      if (rows.length() == 0 && asrt != null)
      {
         String cols = null;
         ArrayList<NameValuePair<Object>> asserts = getAssertions(args);

         for (int i = 0; i < asserts.size(); i++)
         {
            if (i == 0) cols = asserts.get(i).name();
            else cols += "," + asserts.get(i).name();
         }

         select = new SQLPart("select "+cols);
         select.append(source.from(bindvalues));
         select.append(whcl.asSQL());

         response.put("assertions",getAssertResponse(session,select,asserts));
      }

      return(new Response(response));
   }


   public static SQLPart getSubQuery(JSONObject def) throws Exception
   {
      String srcid = def.getString(SOURCE);
      TableSource source = Sources.get(srcid);

      if (source == null)
         throw new Exception(Messages.get("UNKNOWN_SOURCE",srcid));

      AccessType limit = source.getAccessLimit("select");
      if (limit == AccessType.denied) throw new Exception(Messages.get("ACCESS_DENIED"));

      HashMap<String,BindValue> bindvalues =
         Utils.getBindValues(def);

      JSONObject args = def.getJSONObject(SELECT);

      return(null);
   }


   private WhereClause getAssertClause(Context context, JSONObject def) throws Exception
   {
      if (!def.has(ASSERTIONS))
         return(null);

      JSONArray filterdef = new JSONArray();
      ArrayList<NameValuePair<Object>> asserts = getAssertions(def);
      JSONObject assertflts = new JSONObject().put(FILTERS,filterdef);

      for (int i = 0; i < asserts.size(); i++)
      {
         JSONObject filter = new JSONObject();
         NameValuePair<Object> asscol = asserts.get(i);

         filter.put("column",asscol.name());
         filter.put("filter","=");
         filter.put("value",asscol.value());

         filterdef.put(filter);
      }

      return(new WhereClause(context,assertflts));
   }


   private JSONOObject getAssertResponse(Session session, SQLPart select, ArrayList<NameValuePair<Object>> asserts) throws Exception
   {
      JSONOObject assertion = new JSONOObject();

      Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),false,1);
      ArrayList<Object[]> table = cursor.fetch();
      cursor.close();

      if (table.size() == 0)
      {
         assertion.put("success",false);
         assertion.put("record","deleted");
      }
      else
      {
         assertion.put("success",false);
         assertion.put("record","modified");

         JSONArray violations = new JSONArray();
         assertion.put("violations",violations);
         Object[] actual = table.get(0);

         for (int i = 0; i < asserts.size(); i++)
         {
            if (!asserts.get(i).value().equals(actual[i]))
            {
               JSONOObject faulted = new JSONOObject();
               faulted.put("column",asserts.get(i).name());
               faulted.put("expected",asserts.get(i).value());
               faulted.put("actual",actual[i]);
               violations.put(faulted);
            }
         }
      }

      return(assertion);
   }


   private ArrayList<NameValuePair<Object>> getAssertions(JSONObject def)
   {
      JSONArray asserts = def.getJSONArray(ASSERTIONS);
      ArrayList<NameValuePair<Object>> list = new ArrayList<NameValuePair<Object>>();

      for (int i = 0; i < asserts.length(); i++)
      {
         JSONObject ass = asserts.getJSONObject(i);

         Object value = ass.get(VALUE);
         String column = ass.getString(COLUMN);

         list.add(new NameValuePair<Object>(column,value));
      }

      return(list);
   }


   private void getQRYColumns(Session session, TableSource source) throws Exception
   {
      HashMap<String,BindValue> bindvalues =
         Utils.getBindValues(definition);

      String stmt = "select *";
      SQLPart select = new SQLPart(stmt);
      select.append(source.from(bindvalues));
      select.snippet(select.snippet()+" where 1 = 2");

      Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),false,0);
      source.setColumns(true,cursor.describe());
      cursor.close();
   }


   private void getBASEColumns(Session session, TableSource source) throws Exception
   {
      String stmt = "select * from";
      SQLPart select = new SQLPart(stmt);
      select.append(source.object);
      select.snippet(select.snippet()+" where 1 = 2");

      Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),false,0);
      source.setColumns(false,cursor.describe());
      cursor.close();
   }


   private void getPrimaryKey(Session session, TableSource source) throws Exception
   {
      String pksrc = "PrimaryKey";
      JSONOObject response = new JSONOObject();
      TableSource pkeysrc = Utils.getSource(response,pksrc);

      if (pkeysrc != null)
      {
         Integer type = SQLTypes.guessType(source.object);

         HashMap<String,BindValue> tabbinding = new HashMap<String,BindValue>()
         {{put("table",new BindValue("table").value(source.object).type(type));}};

         String stmt = "select *";
         SQLPart select = new SQLPart(stmt);
         select.append(pkeysrc.from(tabbinding));

         Cursor cursor = session.executeQuery(select.snippet(),select.bindValues(),false,0);

         ArrayList<String> pkey = new ArrayList<String>();

         while (cursor.next())
         {
            ArrayList<Object[]> table = cursor.fetch();
            pkey.add((String) table.get(0)[0]);
         }

         cursor.close();
         source.setPrimaryKey(pkey);
      }
   }
}