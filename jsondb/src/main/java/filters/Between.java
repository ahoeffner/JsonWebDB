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

import messages.Messages;
import database.DataType;
import database.BindValue;
import java.util.ArrayList;
import org.json.JSONObject;
import filters.definitions.Filter;
import filters.WhereClause.Context;


public class Between extends Filter
{
   public Between(Context context, JSONObject definition)
   {
      super(context,definition);
   }

   @Override
   public String sql()
   {
      return(column+" between ? and ?");
   }

   @Override
   public ArrayList<BindValue> bindvalues() throws Exception
   {
      if (bindvalues.size() == 0)
      {
         BindValue bv1 = new BindValue(column);
         BindValue bv2 = new BindValue(column);

			if (values.length != 2)
				throw new Exception(Messages.get("WRONG_NUMBER_OF_BINDVALUES","Between"));

         bindvalues.add(bv1.value(values[0]));
         bindvalues.add(bv2.value(values[1]));

         String name = column.toLowerCase();
         DataType coldef = context.datatypes.get(name);

         if (coldef != null)
         {
            bv1.type(coldef.sqlid);
            bv2.type(coldef.sqlid);
         }
      }

      return(bindvalues);
   }
}