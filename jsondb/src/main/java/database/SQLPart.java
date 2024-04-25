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

package database;

import java.util.ArrayList;


public class SQLPart
{
   private String sql;
   private ArrayList<BindValue> bindvalues;


   public SQLPart(StringBuffer sql, ArrayList<BindValue> bindValues)
   {
      this(new String(sql),bindValues);
   }


   public SQLPart(String sql)
   {
      this.sql = sql;
      this.bindvalues = null;
   }


   public SQLPart(String sql, ArrayList<BindValue> bindValues)
   {
      this.sql = sql;
      this.bindvalues = bindValues;
   }

   public String sql()
   {
      return(sql);
   }

   public SQLPart sql(String sql)
   {
      this.sql = sql;
      return(this);
   }


   public ArrayList<BindValue> bindValues()
   {
      return(bindvalues);
   }


   public SQLPart append(SQLPart next)
   {
      this.sql += " "+next.sql;
      if (bindvalues == null) bindvalues = next.bindvalues;
      else if (next.bindvalues != null) bindvalues.addAll(next.bindvalues);
      return(this);
   }


   public SQLPart bind(String name, Object value)
   {
      for(BindValue bv : this.bindvalues)
      {
         if (bv.name().equalsIgnoreCase(name))
            bv.value(value);
      }

      return(this);
   }


   public SQLPart bind(String name, String type, Object value)
   {
      for(BindValue bv : this.bindvalues)
      {
         if (bv.name().equalsIgnoreCase(name))
            bv.type(type).value(value);
      }

      return(this);
   }


   public SQLPart bindByValue()
   {
      int delta = 0;
      String sql = this.sql;

      ArrayList<BindValue> bindvalues =
         new ArrayList<BindValue>();

      for(BindValue bv : this.bindvalues)
      {
         if (!bv.ampersand())
         {
            bindvalues.add(bv);
            continue;
         }

         int pe = bv.end() + delta;
         int pb = bv.start() + delta;

         String value = bv.value()+"";

         String se = sql.substring(pe);
         String sb = sql.substring(0,pb);

         sql = sb + value + se;
         delta += (value.length() - bv.len() - 1);
      }

      return(new SQLPart(sql,bindvalues));
   }


   public SQLPart clone()
   {
      return(new SQLPart(sql,bindvalues));
   }
}

