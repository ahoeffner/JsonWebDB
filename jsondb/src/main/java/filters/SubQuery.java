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

package filters;

import database.SQLPart;
import java.util.HashMap;
import database.DataType;
import database.BindValue;
import java.util.ArrayList;
import org.json.JSONObject;
import jsondb.requests.Table;
import filters.definitions.Filter;


public class SubQuery extends Filter
{
   private final SQLPart subq;

   public SubQuery(HashMap<String,DataType> datatypes, JSONObject definition) throws Exception
   {
      super(datatypes,definition);
      String table = Table.class.getSimpleName();
      subq = Table.getSubQuery(definition.getJSONObject(table));
   }

   @Override
   public String sql()
   {
      if (column != null)
         columns = new String[] {column};

      String sql = "(";

      for (int i = 0; i < columns.length; i++)
      {
         if (i == 0) sql += columns[i];
         else sql += ", " + columns[i];
      }

      sql += ") in ";

      return(sql);
   }

   @Override
   public ArrayList<BindValue> bindvalues()
   {
      return(new ArrayList<BindValue>());
   }
}