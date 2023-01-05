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
package org.micromanager.acqj.main;

import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.function.Consumer;

import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.api.AcquisitionHook;
import org.micromanager.acqj.api.DataSink;
import org.micromanager.acqj.api.TaggedImageProcessor;
import org.micromanager.acqj.internal.Engine;

/**
 * This is the main class for using AcqEngJ. AcquisitionAPI defines its public API.
 * A full usage example can be found in example/FullExample.java
 */
public class Acquisition implements AcquisitionAPI {

   private static final int IMAGE_QUEUE_SIZE = 30;

   protected String xyStage_, zStage_, slm_;
   protected volatile boolean eventsFinished_;
   protected volatile boolean abortRequested_ = false;
   private JSONObject summaryMetadata_;
   private long startTime_ms_ = -1;
   private volatile boolean paused_ = false;
   protected DataSink dataSink_;
   private Consumer<JSONObject> summaryMDAdder_;
   final public CMMCore core_;
   private CopyOnWriteArrayList<AcquisitionHook> eventGenerationHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> beforeHardwareHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> afterHardwareHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<AcquisitionHook> afterCameraHooks_ = new CopyOnWriteArrayList<AcquisitionHook>();
   private CopyOnWriteArrayList<TaggedImageProcessor> imageProcessors_ = new CopyOnWriteArrayList<TaggedImageProcessor>();
   protected LinkedBlockingDeque<TaggedImage> firstDequeue_
           = new LinkedBlockingDeque<TaggedImage>(IMAGE_QUEUE_SIZE);
   private ConcurrentHashMap<TaggedImageProcessor, LinkedBlockingDeque<TaggedImage>> processorOutputQueues_
           = new ConcurrentHashMap<TaggedImageProcessor, LinkedBlockingDeque<TaggedImage>>();
   public boolean debugMode_ = false;
   private ThreadPoolExecutor savingExecutor_ = null;
   private Exception abortException_ = null;
   private Consumer<JSONObject> imageMetadataProcessor_;
   private volatile boolean started_ = false;

   /**
    * Primary constructor for creating Acquisitons. If DataSink is null, then a
    * TaggedImageProcessor that diverts the images to custom downstream processing
    * must be implemented.
    *
    * After this constructor returns, the Acquisiton will be ready to be given instructions
    * by calling submitEventIterator. However, if any acquisition hooks or image processors
    * are desired, they should be added before the first call of submitEventIterator
    *
    */
   public Acquisition(DataSink sink) {
      this(sink, null);
   }

   /**
    * Version of the constructor that accepts a function that can modify SummaryMetadata as needed
    */
   public Acquisition(DataSink sink, Consumer<JSONObject> summaryMDAdder) {
      core_ = Engine.getCore();
      summaryMDAdder_ = summaryMDAdder;
      dataSink_ = sink;
      initialize();
   }


   /**
    * Don't delete, called by python side
    */
   public DataSink getDataSink() {
      return dataSink_;
   }

   /**
    * Don't delete, called by python side
    */
   public void setDebugMode(boolean debug) {debugMode_ = debug; }

   public boolean isDebugMode() {return debugMode_;}

   public boolean isAbortRequested() {
      return abortRequested_;
   }

   /**
    * Auto abort caused by an exception during acquisition
    * @param e
    */
   public void abort(Exception e) {
      abortException_ = e;
      abort();
   }

   // Pycromanager calls this
   public void checkForExceptions() throws Exception {
      if (abortException_ != null) {
         throw abortException_;
      }
   }

   public void abort() {
      if (abortRequested_) {
         return;
      }
      abortRequested_ = true;
      if (this.isPaused()) {
         this.setPaused(false);
      }
      Engine.getInstance().finishAcquisition(this);
   }

   private void addToSummaryMetadata(JSONObject summaryMetadata) {
      if (summaryMDAdder_ != null) {
         summaryMDAdder_.accept(summaryMetadata);
      }
   }

   public void addToImageMetadata(JSONObject tags) {
      if (imageMetadataProcessor_ != null) {
         imageMetadataProcessor_.accept(tags);
      }
   }

   @Override
   public Future submitEventIterator(Iterator<AcquisitionEvent> evt) {
      return Engine.getInstance().submitEventIterator(evt);
   }

   private void startSavingExecutor() {
      savingExecutor_ = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
              (Runnable r) -> new Thread(r, "Acquisition image processing and saving thread"));

      savingExecutor_.submit(() -> {
         try {
            while (true) {
               if (debugMode_) {
                  core_.logMessage("Image queue size: " + firstDequeue_.size());
               }
               TaggedImage img;
               // Grab the image, either directly from the queue to acqEng added it to,
               // or from the output of the last image processor
               if (imageProcessors_.isEmpty()) {
                  if (debugMode_) {
                     core_.logMessage("waiting for image to save" );
                  }
                  img = firstDequeue_.takeFirst();
                  if (debugMode_) {
                     core_.logMessage("got image to save" );
                  }
                  saveImage(img);
                  if (debugMode_) {
                     core_.logMessage("image saved; finished = " +
                             (img.pix == null && img.tags == null));
                  }
               } else {
                  // get the last processor
                  LinkedBlockingDeque<TaggedImage> dequeue = processorOutputQueues_.get(
                          imageProcessors_.get(imageProcessors_.size() - 1));
                  img = dequeue.takeFirst();
                  if (dataSink_ != null) {
                     if (debugMode_) {
                        core_.logMessage("Saving image");
                     }
                     saveImage(img);
                     if (debugMode_) {
                        core_.logMessage("Finished saving image");
                     }
                  }
               }
               if (img.pix == null && img.tags == null) {
                  // Last image because acquisition is shutting down.
                  // Storage and image processors will also recieve this
                  // signal and shut down on their own
                  savingExecutor_.shutdown();
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

   @Override
   public void addImageProcessor(TaggedImageProcessor p) {
      if (started_) {
         throw new RuntimeException("Cannot add processor after acquisiton started");
      }
      imageProcessors_.add(p);
      processorOutputQueues_.put(p, new LinkedBlockingDeque<TaggedImage>(IMAGE_QUEUE_SIZE));

      if (imageProcessors_.size() == 1) {
         p.setAcqAndDequeues(this, firstDequeue_, processorOutputQueues_.get(p));
      } else {
         p.setAcqAndDequeues(this, processorOutputQueues_.get(imageProcessors_.size() - 2),
                 processorOutputQueues_.get(imageProcessors_.size() - 1));
      }
   }

   @Override
   public void addHook(AcquisitionHook h, int type) {
      if (started_) {
         throw new RuntimeException("Cannot add hook after acquisiton started");
      }
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
         // wait for event generation to shut down
         while (!eventsFinished_) {
            Thread.sleep(5);
         }
         // Waiting for saving to finish and all resources to complete
         if (savingExecutor_ != null) {
            while (!savingExecutor_.isTerminated()) {
               Thread.sleep(5);
            }
         }
      } catch (InterruptedException ex) {
         throw new RuntimeException(ex);
      }
   }

   /**
    * 1) Get the names of core devices to be used in acquisition.
    * 2) Create Summary metadata.
    * 3) Initialize data sink.
    */
   protected void initialize() {
      JSONObject summaryMetadata = AcqEngMetadata.makeSummaryMD(this);
      addToSummaryMetadata(summaryMetadata);

      try {
         // Make a local in copy in case something else modifies it
         summaryMetadata_ = new JSONObject(summaryMetadata.toString());
      } catch (JSONException ex) {
         System.err.print("Couldn't copy summaary metadata");
         ex.printStackTrace();
      }
      if (dataSink_ != null) {
         //It could be null if not using saving and viewing and diverting with custom processor
         dataSink_.initialize(this, summaryMetadata);
      }
   }

   public void  start() {
      if (dataSink_ != null) {
         startSavingExecutor();
      }
      started_ = true;
   }


   /**
    * Called by acquisition engine to save an image
    */
   private void saveImage(TaggedImage image) {
      if (image.tags == null && image.pix == null) {
         dataSink_.finish();
         eventsFinished_ = true; //should have already been done, but just in case
      } else {
         //this method doesn't return until all images have been written to disk
         dataSink_.putImage(image);
      }
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

   public synchronized void setPaused(boolean pause) {
      paused_ = pause;
   }

   public JSONObject getSummaryMetadata() {
      return summaryMetadata_;
   }

   public boolean anythingAcquired() {
      return dataSink_ == null ? true : dataSink_.anythingAcquired();
   }

   @Override
   public void addImageMetadataProcessor(Consumer<JSONObject> processor) {
      if (imageMetadataProcessor_ == null) {
         imageMetadataProcessor_ = processor;
      } else {
         throw new RuntimeException("Multiple metadata processors not supported");
      }
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
   }

   public boolean areEventsFinished() {
      return eventsFinished_;
   }
}
