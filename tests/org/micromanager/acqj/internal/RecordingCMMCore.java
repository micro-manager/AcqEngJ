package org.micromanager.acqj.internal;

import java.util.ArrayList;
import java.util.List;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.DoubleVector;

/**
 * A test double for {@link CMMCore} that records hardware commands instead of
 * talking to (non-existent) hardware. It does not load the MMCoreJ native
 * library: it extends {@code CMMCore} through the protected
 * {@code CMMCore(long, boolean)} constructor (which only stores the swig
 * pointer fields and makes no JNI call) and overrides every method the
 * acquisition engine touches during {@code prepareHardware}/{@code startZDrive}
 * and during sequencability checks. As long as no un-overridden, JNI-backed
 * method is invoked, no native library is needed, so this runs on a clean CI
 * machine.
 *
 * <p>The recorded {@link #commands} list captures the ordered stream of
 * hardware operations across a whole (simulated) acquisition, which tests can
 * assert against. Sequencability is configurable so tests can exercise both the
 * "software" path (one move per slice) and the hardware-sequenced path.
 */
public class RecordingCMMCore extends CMMCore {

   private final String focusDevice_;
   private final String xyStageDevice_;
   private final String slmDevice_;
   private final String cameraDevice_;
   private String autoFocusDevice_ = "Autofocus";

   // Configurable hardware capabilities (default: nothing is sequenceable, so
   // each event is executed individually -- the "software" path).
   private boolean stageSequenceable_ = false;
   private boolean xyStageSequenceable_ = false;
   private boolean exposureSequenceable_ = false;
   private int sequenceMaxLength_ = 1000;

   // Current focus position reported by getPosition() (used for channel offsets).
   private double currentZ_ = 0.0;

   /** Ordered record of every hardware command issued. */
   public final List<String> commands = new ArrayList<>();

   /** Structured record of every setPosition(device, pos) call, in order. */
   public final List<StagePositionCommand> setPositionCalls = new ArrayList<>();

   public static final class StagePositionCommand {
      public final String device;
      public final double position;

      StagePositionCommand(String device, double position) {
         this.device = device;
         this.position = position;
      }

      @Override
      public String toString() {
         return device + "=" + position;
      }
   }

   public RecordingCMMCore(String focusDevice, String xyStageDevice,
                           String slmDevice, String cameraDevice) {
      // (pointer = 0, memOwn = false) -> no native allocation, no JNI call.
      super(0, false);
      focusDevice_ = focusDevice;
      xyStageDevice_ = xyStageDevice;
      slmDevice_ = slmDevice;
      cameraDevice_ = cameraDevice;
   }

   // ----- test configuration --------------------------------------------------

   public RecordingCMMCore withStageSequenceable(boolean v) {
      stageSequenceable_ = v;
      return this;
   }

   public RecordingCMMCore withXYStageSequenceable(boolean v) {
      xyStageSequenceable_ = v;
      return this;
   }

   public RecordingCMMCore withExposureSequenceable(boolean v) {
      exposureSequenceable_ = v;
      return this;
   }

   public RecordingCMMCore withSequenceMaxLength(int v) {
      sequenceMaxLength_ = v;
      return this;
   }

   // ----- query helpers for assertions ---------------------------------------

   /** Positions (in call order) that were commanded for a given device. */
   public List<Double> positionsSetFor(String device) {
      List<Double> result = new ArrayList<>();
      for (StagePositionCommand c : setPositionCalls) {
         if (c.device.equals(device)) {
            result.add(c.position);
         }
      }
      return result;
   }

   /** Count of commands whose text starts with the given prefix. */
   public int countCommands(String prefix) {
      int n = 0;
      for (String c : commands) {
         if (c.startsWith(prefix)) {
            n++;
         }
      }
      return n;
   }

   public void clearRecords() {
      commands.clear();
      setPositionCalls.clear();
   }

   // ----- device identity -----------------------------------------------------

   @Override
   public String getFocusDevice() {
      return focusDevice_;
   }

   @Override
   public String getXYStageDevice() {
      return xyStageDevice_;
   }

   @Override
   public String getSLMDevice() {
      return slmDevice_;
   }

   @Override
   public String getCameraDevice() {
      return cameraDevice_;
   }

   @Override
   public String getAutoFocusDevice() {
      return autoFocusDevice_;
   }

   // ----- movement / config (recorded) ---------------------------------------

   @Override
   public void waitForDevice(String device) {
      // no-op: nothing to wait for
   }

   @Override
   public void setPosition(String device, double position) {
      setPositionCalls.add(new StagePositionCommand(device, position));
      commands.add("setPosition " + device + " " + position);
      if (device.equals(focusDevice_)) {
         currentZ_ = position;
      }
   }

   @Override
   public double getPosition() {
      return currentZ_;
   }

   @Override
   public double getPosition(String device) {
      return currentZ_;
   }

   @Override
   public void setXYPosition(String device, double x, double y) {
      commands.add("setXYPosition " + device + " " + x + " " + y);
   }

   @Override
   public void setExposure(double exposure) {
      commands.add("setExposure " + exposure);
   }

   @Override
   public void setConfig(String group, String config) {
      commands.add("setConfig " + group + " " + config);
   }

   @Override
   public void waitForConfig(String group, String config) {
      // no-op
   }

   @Override
   public Configuration getConfigData(String group, String config) {
      // Return an empty configuration: the engine only iterates over its
      // settings to compare/sequence individual properties. With no settings,
      // those loops are skipped (and we never touch native PropertySetting
      // objects), while channels still behave as non-property-sequenced.
      return new EmptyConfiguration();
   }

   /** A native-free {@link Configuration} that reports zero settings. */
   private static final class EmptyConfiguration extends Configuration {
      EmptyConfiguration() {
         super(0, false);
      }

      @Override
      public long size() {
         return 0;
      }
   }

   // ----- sequencing (recorded + configurable) -------------------------------

   @Override
   public boolean isStageSequenceable(String device) {
      return stageSequenceable_;
   }

   @Override
   public int getStageSequenceMaxLength(String device) {
      return sequenceMaxLength_;
   }

   @Override
   public void loadStageSequence(String device, DoubleVector positions) {
      commands.add("loadStageSequence " + device + " n=" + positions.size());
   }

   @Override
   public void startStageSequence(String device) {
      commands.add("startStageSequence " + device);
   }

   @Override
   public void stopStageSequence(String device) {
      // no-op (engine stops defensively before loading)
   }

   @Override
   public boolean isXYStageSequenceable(String device) {
      return xyStageSequenceable_;
   }

   @Override
   public int getXYStageSequenceMaxLength(String device) {
      return sequenceMaxLength_;
   }

   @Override
   public boolean isExposureSequenceable(String device) {
      return exposureSequenceable_;
   }

   @Override
   public int getExposureSequenceMaxLength(String device) {
      return sequenceMaxLength_;
   }

   @Override
   public boolean isPropertySequenceable(String device, String prop) {
      // Channels are never property-sequenced in these tests, which keeps the
      // engine away from getConfigData() (a native Configuration object).
      return false;
   }

   // ----- misc ----------------------------------------------------------------

   @Override
   public boolean isSequenceRunning() {
      return false;
   }

   @Override
   public void prepareSequenceAcquisition(String camera) {
      // no-op
   }

   @Override
   public void logMessage(String message) {
      // no-op
   }
}
