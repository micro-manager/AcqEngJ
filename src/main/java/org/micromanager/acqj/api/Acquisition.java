///////////////////////////////////////////////////////////////////////////////
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package org.micromanager.acqj.api;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.xystage.PixelStageTranslator;
import org.micromanager.acqj.internal.acqengj.Engine;

/**
 * Abstract class that manages a generic acquisition. Subclassed into specific
 * types of acquisition. Minimal set of assumptions that mirror those in the
 * core. For example, assumes one Z stage, one XY stage, one channel group, etc
 */
public class Acquisition implements AcquisitionInterface {

   private static final int IMAGE_QUEUE_SIZE = 30;

   public static final int EVENT_GENERATION_HOOK = 0;
   public static final int BEFORE_HARDWARE_HOOK = 1;
   public static final int AFTER_HARDWARE_HOOK = 2;
   public static final int AFTER_CAMERA_HOOK = 3;

   protected String xyStage_, zStage_, slm_;
   protected boolean zStageHasLimits_ = false;
   protected double zStageLowerLimit_, zStageUpperLimit_;
   protected volatile boolean eventsFinished_;
   protected volatile boolean abortRequested_ = false;
   private volatile boolean aborted_ = false;
   public final boolean initialAutoshutterState_;
   private JSONObject summaryMetadata_;
   private long startTime_ms_ = -1;
   private volatile boolean paused_ = false;
   private CopyOnWriteArrayList<String> channelNames_ = new CopyOnWriteArrayList<String>();
   private PixelStageTranslator pixelStageTranslator_;
   protected DataSink dataSink_;
   protected CMMCore core_;
   private CopyOnWriteArrayList<AcquisitionHook> eventGenerationHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> beforeHardwareHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> afterHardwareHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> afterCameraHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<TaggedImageProcessor> imageProcessors_ = new CopyOnWriteArrayList<TaggedImageProcessor>();
   private LinkedBlockingDeque<TaggedImage> firstDequeue_
           = new LinkedBlockingDeque<TaggedImage>(IMAGE_QUEUE_SIZE);
   private ConcurrentHashMap<TaggedImageProcessor, LinkedBlockingDeque<TaggedImage>> processorOutputQueues_
           = new ConcurrentHashMap<TaggedImageProcessor, LinkedBlockingDeque<TaggedImage>>();

   /**
    * After calling this constructor, call initialize then start
    *
    */
   public Acquisition(DataSink sink) {
      core_ = Engine.getCore();
      initialAutoshutterState_ = core_.getAutoShutter();
      dataSink_ = sink;
   }

   /**
    * Don't delete, called by python side
    */
   public DataSink getDataSink() {
      return dataSink_;
   }
   
   public boolean isAbortRequested() {
      return abortRequested_;
   }

   public void abort() {
      abortRequested_ = true;
      if (aborted_) {
         return;
      }
      aborted_ = true;
      if (this.isPaused()) {
         this.togglePaused();
      }
      Engine.getInstance().finishAcquisition(this);
   }

   public void addToSummaryMetadata(JSONObject summaryMetadata) {
      //This can be overriden by subclasses to add additional metadata
   }

   public void addToImageMetadata(JSONObject tags) {
      //This can be overriden by subclasses to add additional metadata
   }

   public void submitEventIterator(Iterator<AcquisitionEvent> evt) {
      Engine.getInstance().submitEventIterator(evt, this);
   }

   @Override
   public void start() {
      if (dataSink_ != null) {
         ThreadPoolExecutor savingAndProcessingExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
                 (Runnable r) -> new Thread(r, "Acquisition image processing and saving thread"));

         savingAndProcessingExecutor.submit(() -> {
            try {
               while (true) {
                  boolean storageFinished;
                  //System.out.println("Image queue size: " + firstDequeue_.size());
                  if (imageProcessors_.isEmpty()) {
                     TaggedImage img = firstDequeue_.takeFirst();
                     storageFinished = saveImage(img);
                  } else {
                     LinkedBlockingDeque<TaggedImage> dequeue = processorOutputQueues_.get(
                             imageProcessors_.get(imageProcessors_.size() - 1));
                     TaggedImage img = dequeue.takeFirst();
                     storageFinished = saveImage(img);
                  }
                  if (storageFinished) {
                     savingAndProcessingExecutor.shutdown();
                     for (TaggedImageProcessor p : imageProcessors_) {
                        p.close();
                     }
                     return;
                  }
               }
            } catch (InterruptedException e) {
               //this should never happen
            } catch (Exception ex) {
               System.err.println(ex);
               ex.printStackTrace();
            }
         });
      }
   }

   @Override
   public void addImageProcessor(TaggedImageProcessor p) {
      imageProcessors_.add(p);
      processorOutputQueues_.put(p, new LinkedBlockingDeque<TaggedImage>(IMAGE_QUEUE_SIZE));

      if (imageProcessors_.size() == 1) {
         p.setDequeues(firstDequeue_, processorOutputQueues_.get(p));
      } else {
         p.setDequeues(processorOutputQueues_.get(imageProcessors_.size() - 2),
                 processorOutputQueues_.get(imageProcessors_.size() - 1));
      }
   }

   @Override
   public void addHook(AcquisitionHook h, int type) {
      if (type == EVENT_GENERATION_HOOK) {
         eventGenerationHooks_.add(h);
      } else if (type == BEFORE_HARDWARE_HOOK) {
         beforeHardwareHooks_.add(h);
      } else if (type == AFTER_HARDWARE_HOOK) {
         afterHardwareHooks_.add(h);
      } else if (type == AFTER_CAMERA_HOOK) {
         afterCameraHooks_.add(h);
      }
   }

   @Override
   /**
    * Block until everything finished
    */
   public void waitForCompletion() {
      try {
         //wait for event generation to shut down
         while (!eventsFinished_) {
            Thread.sleep(5);
         }
      } catch (InterruptedException ex) {
         throw new RuntimeException(ex);
      }
   }

   /**
    * Generic version with no XY tiling
    */
   protected void initialize() {
      initialize(null, null);
   }

   /**
    * 1) Get the names or core devices to be used in acquistion 2) Create
    * Summary metadata 3) Initialize data sink
    */
   protected void initialize(Integer overlapX, Integer overlapY) {
      xyStage_ = core_.getXYStageDevice();
      zStage_ = core_.getFocusDevice();
      slm_ = core_.getSLMDevice();
      //"postion" is not generic name...and as of right now there is now way of getting generic z positions
      //from a z deviec in MM, but the following code works for some devices
      String positionName = "Position";
      try {
         if (core_.getFocusDevice() != null && core_.getFocusDevice().length() > 0) {
            if (core_.hasProperty(zStage_, positionName)) {
               zStageHasLimits_ = core_.hasPropertyLimits(zStage_, positionName);
               if (zStageHasLimits_) {
                  zStageLowerLimit_ = core_.getPropertyLowerLimit(zStage_, positionName);
                  zStageUpperLimit_ = core_.getPropertyUpperLimit(zStage_, positionName);
               }
            }
         }
      } catch (Exception ex) {
         throw new RuntimeException("Problem communicating with core to get Z stage limits");
      }
      JSONObject summaryMetadata = AcqEngMetadata.makeSummaryMD(this);

      //Optional additional summary metadata if doing tiling in XY
      if (overlapX != null && overlapY != null) {
         AcqEngMetadata.setPixelOverlapX(summaryMetadata, overlapX);
         AcqEngMetadata.setPixelOverlapY(summaryMetadata, overlapY);
         if (AcqEngMetadata.getAffineTransformString(summaryMetadata).equals("Undefined")) {
            throw new RuntimeException("Cannot run acquisition with XY tiling without first defining" +
                    "affine transform between camera and stage. Check pixel size calibration");
         }
         pixelStageTranslator_ = new PixelStageTranslator(AcqEngMetadata.getAffineTransform(summaryMetadata), xyStage_,
                 (int) core_.getImageWidth(), (int) core_.getImageHeight(), overlapX, overlapY);
      }

      addToSummaryMetadata(summaryMetadata);

      try {
         //keep local copy for viewer
         summaryMetadata_ = new JSONObject(summaryMetadata.toString());
      } catch (JSONException ex) {
         System.err.print("Couldn't copy summaary metadata");
         ex.printStackTrace();
      }
      if (dataSink_ != null) {
         //It could be null if not using savign and viewing and diverting with custom processor
         dataSink_.initialize(this, summaryMetadata);
      }
   }

   /**
    * Called by acquisition engine to save an image, shoudn't return until it as
    * been written to disk
    */
   private synchronized boolean saveImage(TaggedImage image) {
      if (image.tags == null && image.pix == null) {
         dataSink_.finished();
         eventsFinished_ = true; //should have already been done, but just in case
         return true;
      } else {
         //Now that all data processors have run, the channel index can be inferred
         //based on what channels show up at runtime
         String channelName = AcqEngMetadata.getChannelName(image.tags);
         if (!channelNames_.contains(channelName)) {
            channelNames_.add(channelName);
         }
         AcqEngMetadata.setAxisPosition(image.tags, AcqEngMetadata.CHANNEL_AXIS,
                 channelNames_.indexOf(channelName));
         //this method doesnt return until all images have been written to disk
         dataSink_.putImage(image);
         return false;
      }
   }

   public PixelStageTranslator getPixelStageTranslator() {
      return pixelStageTranslator_;
   }

   public String getXYStageName() {
      return xyStage_;
   }

   public String getZStageName() {
      return zStage_;
   }

   public String getSLMName() {
      return slm_;
   }

   public long getStartTime_ms() {
      return startTime_ms_;
   }

   public void setStartTime_ms(long time) {
      startTime_ms_ = time;
   }

   public boolean isPaused() {
      return paused_;
   }

   public synchronized void togglePaused() {
      paused_ = !paused_;
   }

   public JSONObject getSummaryMetadata() {
      return summaryMetadata_;
   }

   public boolean anythingAcquired() {
      return dataSink_ == null ? true : dataSink_.anythingAcquired();
   }

   public Iterable<AcquisitionHook> getEventGenerationHooks() {
      return eventGenerationHooks_;
   }

   public Iterable<AcquisitionHook> getBeforeHardwareHooks() {
      return beforeHardwareHooks_;
   }

   public Iterable<AcquisitionHook> getAfterHardwareHooks() { return afterHardwareHooks_; }

   public Iterable<AcquisitionHook> getAfterCameraHooks() { return afterCameraHooks_; }

   public void addToOutput(TaggedImage ti) throws InterruptedException {
      firstDequeue_.putLast(ti);
   }

   public void finish() {
      Engine.getInstance().finishAcquisition(this);
   }

   public void eventsFinished() {
      eventsFinished_ = true;
      //TODO: could add more restoration of inital settings at begginning of acquisition
      core_.setAutoShutter(initialAutoshutterState_);
   }

   public boolean isFinished() {
      return eventsFinished_;
   }
}
