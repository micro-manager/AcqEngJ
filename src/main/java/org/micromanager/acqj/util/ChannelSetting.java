package org.micromanager.acqj.util;

/**
 * Convenience class that encapsulates a single channel setting.
 *
 * @author henrypinkard
 */
public class ChannelSetting {
   public String group_;
   public String config_;
   public double exposure_;
   public double offset_;
   public boolean use_;

   public ChannelSetting(String group, String config,
                         double exposure, double offset, boolean use) {
      group_ = group;
      config_ = config;
      exposure_ = exposure;
      offset_ = offset;
      use_ = use;
   }

   public ChannelSetting(String group, String config,
                         double exposure, double offset) {
      this(group, config, exposure, offset, true);
   }
}
