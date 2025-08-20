package org.micromanager.acqj.main;

public class ThreeTuple implements Comparable<ThreeTuple> {

   final String dev;
   final String prop;
   final String val;

   public ThreeTuple(String d, String p, String v) {
      dev = d;
      prop = p;
      val = v;
   }

   public String[] toArray() {
      return new String[]{dev, prop, val};
   }

   @Override
   public int compareTo(ThreeTuple t) {
      if (!dev.equals(t.dev)) {
         int cmp = dev.compareTo(t.dev);
         if (cmp != 0) {
            return cmp;
         }
         cmp = prop.compareTo(t.prop);
         if (cmp != 0) {
            return cmp;
         }
         return val.compareTo(t.val);
      }
      return 0;
   }

}
