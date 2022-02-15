/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.acqj.api;

import java.util.Iterator;
import java.util.concurrent.Future;

import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.util.xytiling.PixelStageTranslator;

/**
 * General interface for acquisitions
 *
 * @author henrypinkard
 */
public interface AcquisitionAPI {

   /**
    * Commence acquisition or prepare it to receive externally generated events
    * as applicable
    */
   public void start();

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
    * returns true if all data that will be collected has been collected
    *
    * @return
    */
   public boolean areEventsFinished();

   /**
    * return if acquisition is paused (i.e. not acquiring new data but not
    * finished)
    *
    * @return
    */
   public boolean isPaused();

   /**
    * Pause or unpause
    */
   public void togglePaused();

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
    * @param evt 
    */
   public Future submitEventIterator(Iterator<AcquisitionEvent> evt);

   /**
    * Get the PixelStageTranslator, which maps the coordinate space of pixels to xy coordinates of the stage.
    * Only exists if XY tiling features are enabled
    * @return
    */
   public PixelStageTranslator getPixelStageTranslator();


   }
