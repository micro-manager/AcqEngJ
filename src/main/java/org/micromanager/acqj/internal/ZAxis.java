package org.micromanager.acqj.internal;

public class ZAxis {

   public final double zOrigin_um_, zStep_um_; //
   public final String name_;
   public int exploreLowerZIndexLimit_, exploreUpperZIndexLimit_;
   public int lowestExploredZIndex_, highestExploredZIndex_;

   public ZAxis(String name, double zorigin, double zStep) {
      name_ = name;
      zOrigin_um_ = zorigin;
      zStep_um_ = zStep;
   }

   public ZAxis(String name, double zorigin, double zStep, int exploreLowerLimit, int exploreUpperLimit,
                int lowestExploredZIndex, int highestExploredZIndex) {
      name_ = name;
      zOrigin_um_ = zorigin;
      zStep_um_ = zStep;
      exploreLowerZIndexLimit_ = exploreLowerLimit;
      exploreUpperZIndexLimit_ = exploreUpperLimit;
      lowestExploredZIndex_ = (int) Math.round((exploreLowerZIndexLimit_ - zOrigin_um_) / zStep_um_);
      highestExploredZIndex_ = (int) Math.round((exploreUpperZIndexLimit_ - zOrigin_um_) / zStep_um_);
   }

   public ZAxis copy() {
      return new ZAxis(name_, zOrigin_um_, zStep_um_, exploreLowerZIndexLimit_, exploreUpperZIndexLimit_,
              lowestExploredZIndex_, highestExploredZIndex_);
   }


}
