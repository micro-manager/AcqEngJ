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
   public int compareTo(org.micromanager.acqj.main.ThreeTuple t) {
      if (!dev.equals(t.dev)) {
         return dev.compareTo(dev);
      } else {
         return prop.compareTo(prop);
      }
   }

}
