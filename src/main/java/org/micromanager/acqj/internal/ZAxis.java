package org.micromanager.acqj.internal;

public class ZAxis {

   public final double zOrigin_um_; //
   public final double zStep_um_; //
   public final String name_;
   public int exploreLowerZIndexLimit_;
   public int exploreUpperZIndexLimit_;
   public int lowestExploredZIndex_;
   public int highestExploredZIndex_;

   public ZAxis(String name, double zOrigin, double zStep) {
      name_ = name;
      zOrigin_um_ = zOrigin;
      zStep_um_ = zStep;
   }

   public ZAxis(String name, double zOrigin, double zStep, int exploreLowerLimit,
                int exploreUpperLimit,
                int lowestExploredZIndex, int highestExploredZIndex) {
      name_ = name;
      zOrigin_um_ = zOrigin;
      zStep_um_ = zStep;
      exploreLowerZIndexLimit_ = exploreLowerLimit;
      exploreUpperZIndexLimit_ = exploreUpperLimit;
      lowestExploredZIndex_ = (int) Math.round((exploreLowerZIndexLimit_ - zOrigin_um_) / zStep_um_);
      highestExploredZIndex_ = (int) Math.round((exploreUpperZIndexLimit_ - zOrigin_um_) / zStep_um_);
   }

   public ZAxis copy() {
      return new ZAxis(name_, zOrigin_um_, zStep_um_, exploreLowerZIndexLimit_,
            exploreUpperZIndexLimit_, lowestExploredZIndex_, highestExploredZIndex_);
   }


}
