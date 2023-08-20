/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.acqj.api;

import java.util.Iterator;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.main.AcquisitionEvent;

/**
 * General interface for acquisitions
 *
 * @author henrypinkard
 */
public interface AcquisitionAPI {

   int EVENT_GENERATION_HOOK = 0;

   // This hook runs before changes to the hardware (corresponding to the instructions in the
   // event) are made
   int BEFORE_HARDWARE_HOOK = 1;

   // This hook runs after changes to the hardware took place, but before camera exposure
   // (either a snap or a sequence) is started
   int AFTER_HARDWARE_HOOK = 2;

   // Hook runs after the camera sequence acquisition has started. This can be used for
   // external triggering of the camera
   int AFTER_CAMERA_HOOK = 3;

   // Hook runs after the camera exposure ended (when possible, before readout of the camera
   // and availability of the images in memory).
   int AFTER_EXPOSURE_HOOK = 4;

   /**
    * Call to ready acquisition to start receiving acquisiton events. No more hooks
    * or image processors should be added after this has been called
    *
    * This method is no longer needed, because it is called automatically when
    * the first call to submitEventIterator is made
    */
   @Deprecated
   public void start();

   /**
    * Add a AcqNotificationListener to receive asynchronous notifications about the acquisition
    */
   public void addAcqNotificationListener(AcqNotificationListener listener);

   /**
    * Block until acquisition finished and all resources complete.
    */
   public void waitForCompletion();

   /**
    * Signal the acquisition to complete once all events are done
    *
    */
   public void finish();

   /**
    * returns true if all acquisition events have been processed and
    * hardware control is complete. This does not, however signal that
    * all images are finished writing to disk (if DataSink is not null).
    * This occurs after events are finished, and the DataSink should
    * provide that information directly
    *
    * @return
    */
   public boolean areEventsFinished();

   /**
    * Cancel any pending events and shutdown
    */
   public void abort();

   /**
    * Abort, and provide an exception that is the reason for the abort. This
    * is useful for passing exceptions across threads
    * @param e
    */
   public void abort(Exception e);

   /**
    * Has abort been called?
    */
   public boolean isAbortRequested();

   /**
    * return if acquisition is paused (i.e. not acquiring new data but not
    * finished)
    *
    * @return
    */
   public boolean isPaused();

   /**
    * return if acquisition is started by the first call to submitEventIterator
    */
   public boolean isStarted();

   /**
    * Pause or unpause
    */
   public void setPaused(boolean pause);

   /**
    * Get the summary metadata for this acquisition
    *
    * @return
    */
   public JSONObject getSummaryMetadata();

   /**
    * Returns true once any data has been acquired
    *
    * @return
    */
   public boolean anythingAcquired();

   /**
    * Add a Consumer that will receive the metadata JSONObject for each image
    * that is generated and modify it as needed. Metadata can also be modified
    * through an ImageProcessor. This method is more lightweight--it should not
    * be doing an significant computation, unlike Image Processors, which run
//    * /on their own dedicated Thread()
    */
   public void addImageMetadataProcessor(Consumer<JSONObject> modifier);

   /**
    * Add an image processor for modifying images before saving or diverting
    * them to some other purpose
    *
    * @param p
    */
   public void addImageProcessor(TaggedImageProcessor p);

   /**
    * Add a an arbitrary piece of code (i.e. a hook) to be executed at a
    * specific time in the acquisition loop
    *
    * @param hook Code to run
    * @param type BEFORE_HARDWARE_HOOK, AFTER_HARDWARE_HOOK, AFTER_SAVE_HOOK
    */
   public void addHook(AcquisitionHook hook, int type);

   /**
    * Submit a list of acquisition events for acquisition. Acquisition engine
    * will automatically optimize over this list (i.e. implement hardware sequencing).
    *
    * If start() has not already been called on the acquisition, it will automatically
    * be called here
    * @param evt 
    */
   public Future submitEventIterator(Iterator<AcquisitionEvent> evt);

   /**
    * Get the DataSink used by this acquisition. This could be null (since)
    * ImageProcessors allow data to be intercepted and diverted
    * @return
    */
   public AcqEngJDataSink getDataSink();

   /**
    * Should debug logging be printed
    */
   public boolean isDebugMode();

   /**
    * Throws an exception if an exception occured on a thread managed by the acquisiton
    */
   public void checkForExceptions() throws Exception;

   /**
    * Activate debug logging
    */
   public void setDebugMode(boolean debug);
}
