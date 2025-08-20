package org.micromanager.acqj.internal;

public class ZAxis {

   public final double zOriginUm_; //
   public final double zStepUm_; //
   public final String name_;
   public int exploreLowerZIndexLimit_;
   public int exploreUpperZIndexLimit_;
   public int lowestExploredZIndex_;
   public int highestExploredZIndex_;

   public ZAxis(String name, double zorigin, double zStep) {
      name_ = name;
      zOriginUm_ = zorigin;
      zStepUm_ = zStep;
   }

   public ZAxis(String name, double zorigin, double zStep, int exploreLowerLimit,
                int exploreUpperLimit,
                int lowestExploredZIndex, int highestExploredZIndex) {
      name_ = name;
      zOriginUm_ = zorigin;
      zStepUm_ = zStep;
      exploreLowerZIndexLimit_ = exploreLowerLimit;
      exploreUpperZIndexLimit_ = exploreUpperLimit;
      lowestExploredZIndex_ = (int) Math.round((exploreLowerZIndexLimit_ - zOriginUm_) / zStepUm_);
      highestExploredZIndex_ = (int) Math.round((exploreUpperZIndexLimit_ - zOriginUm_) / zStepUm_);
   }

   public ZAxis copy() {
      return new ZAxis(name_, zOriginUm_, zStepUm_, exploreLowerZIndexLimit_,
            exploreUpperZIndexLimit_, lowestExploredZIndex_, highestExploredZIndex_);
   }


}
