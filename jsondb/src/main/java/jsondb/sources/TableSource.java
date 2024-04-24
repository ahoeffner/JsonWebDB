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

package jsondb.sources;

import java.util.ArrayList;
import org.json.JSONObject;
import database.BindValue;


public class TableSource extends Source
{
   public final VPD vpd;
   public final String id;
   public final String query;
   public final String object;
   public final String sorting;
   public final String[] derived;
   public final String[] primarykey;

   public TableSource(JSONObject definition) throws Exception
   {
      String id = getString(definition,"id",true,true);
      String query = getString(definition,"query",false);
      String object = getString(definition,"object",true);
      String sorting = getString(definition,"sorting",false);
      String[] derived = getStringArray(definition,"derived",false);
      String[] primarykey = getStringArray(definition,"primary-key",false);

      VPD vpd = VPD.parse(definition);

      this.id = id;
      this.vpd = vpd;
      this.query = query;
      this.object = object;
      this.sorting = sorting;
      this.derived = derived;
      this.primarykey = primarykey;
   }


   public static class VPD
   {
      public final String filter;
      public final String[] apply;
      public final ArrayList<BindValue> bindValues;


      private static VPD parse(JSONObject def) throws Exception
      {
         if (!def.has("vpd")) return(null);

         def = def.getJSONObject("vpd");

         String filter = Source.getString(def,"where-clause",true);
         String[] applies = Source.getStringArray(def,"apply",true,true);

         return(new VPD(filter,applies));
      }

      private VPD(String filter, String[] apply)
      {
         SQL parsed = Source.parse(filter);

         this.apply = apply;
         this.filter = parsed.sql;
         this.bindValues = parsed.bindValues;
      }
   }
}
