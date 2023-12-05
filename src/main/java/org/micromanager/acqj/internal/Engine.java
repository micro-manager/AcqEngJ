///////////////////////////////////////////////////////////////////////////////
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com
//
// COPYRIGHT:    University of California, Berkeley, 2018
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
package org.micromanager.acqj.internal;

import mmcorej.org.json.JSONException;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.main.AcqNotification;
import org.micromanager.acqj.main.Acquisition;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.api.AcquisitionHook;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.lang.Double;
import java.util.concurrent.TimeoutException;

import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.DoubleVector;
import mmcorej.PropertySetting;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.micromanager.acqj.main.AcqEngMetadata;

public class Engine {

   private static final int HARDWARE_ERROR_RETRIES = 6;
   private static final int DELAY_BETWEEN_RETRIES_MS = 5;
   private static CMMCore core_;
   private static Engine singleton_ = null;
   private AcquisitionEvent lastEvent_ = null;
   //A queue that holds multiple acquisition events which are in the process of being merged into a single, hardware-triggered event
   private LinkedList<AcquisitionEvent> sequencedEvents_ = new LinkedList<AcquisitionEvent>();
   //Thread on which the generation of acquisition events occurs
   private static ExecutorService eventGeneratorExecutor_;
   //Thread on which all communication with hardware occurs
   private static ExecutorService acqExecutor_;


   public Engine(CMMCore core) {
      if (singleton_ == null) {
         singleton_ = this;
         core_ = core;
         acqExecutor_ = Executors.newSingleThreadExecutor(r -> {
            return new Thread(r, "Acquisition Engine Thread");
         });
         eventGeneratorExecutor_ = Executors
                 .newSingleThreadExecutor((Runnable r) -> new Thread(r, "Acq Eng event generator"));
      }
   }



   public static CMMCore getCore() {
      return core_;
   }

   public static Engine getInstance() {
      return singleton_;
   }

   /**
    * No more data to be collected for this acquisition. Execute a finishing event so everything shuts down properly
    *
    * Note that this is not the only route to finishing an acquisition, but merely a convenient way
    * of submitting an AcquisitionFinishedEvent
    * @param acq the acquisition to be finished
    * @return
    */
   public Future<Future> finishAcquisition(Acquisition acq) {
      return eventGeneratorExecutor_.submit(() -> {
         Future f = acqExecutor_.submit(() -> {
            try {
               if (acq.isDebugMode()) {
                  core_.logMessage("recieved acquisition finished signal");
               }
               //clear any pending events, but since submitEventIterator occurs on the
               //same thread, events will only be cleared in the case of an abort
               sequencedEvents_.clear();
               if (acq.isDebugMode()) {
                  core_.logMessage("creating acquisition finished event");
               }
               executeAcquisitionEvent(AcquisitionEvent.createAcquisitionFinishedEvent(acq));
               acq.blockUntilEventsFinished(null);
            } catch (InterruptedException ex) {
               throw new RuntimeException(ex);
            }
         });
         return f;
      });
   }

   /**
    * Submit a stream of events which will get lazily processed and merged into multi-image hardware sequenced events
    * as needed. Block until all events executed
    *
    * @param eventIterator Iterator of acquisition events that contains instructions of what to acquire
    * @return a Future that can be gotten when the event iteration is finished,
    */
   public Future submitEventIterator(Iterator<AcquisitionEvent> eventIterator) {
      return eventGeneratorExecutor_.submit(() -> {
         try {
            AcquisitionAPI acq = null;
            while (eventIterator.hasNext()) {
               AcquisitionEvent event = eventIterator.next();
               // Some iterators can return null, they still may have more events, but want to
               // skip this one.
               if (event == null) {
                  continue;
               }
               acq = event.acquisition_;
               if (acq.isDebugMode()) {
                  core_.logMessage("got event: " + event.toString()  );
               }

               for (AcquisitionHook h : event.acquisition_.getEventGenerationHooks()) {
                  event = h.run(event);
                  if (event == null) {
                     return; //The hook cancelled this event
                  }
               }

               //Wait here is acquisition is paused
               while (event.acquisition_.isPaused()) {
                  try {
                     Thread.sleep(5);
                  } catch (InterruptedException ex) {
                     throw new RuntimeException(ex);
                  }
               }
               try {
                  if (acq.isAbortRequested()) {
                     if (acq.isDebugMode()) {
                        core_.logMessage("acquisition aborted" );
                     }
                     return;
                  }
                  Future imageAcquiredFuture = processAcquisitionEvent(event);
                  imageAcquiredFuture.get();
               } catch (InterruptedException ex) {
                  //cancelled
                  return;
               } catch (ExecutionException ex) {
                  //some problem with acuisition, abort and propagate exception
                  ex.printStackTrace();
                  acq.abort(ex);
                  throw new RuntimeException(ex);
               }
            }
            try {
               //Make all events get executed from this iterator
               Future lastImageFuture = processAcquisitionEvent(AcquisitionEvent.createAcquisitionSequenceEndEvent(acq));
               lastImageFuture.get();
            } catch (InterruptedException ex) {
               //cancelled
               return;
            } catch (ExecutionException ex) {
               //some problem with acuisition, propagate exception
               ex.printStackTrace();
               throw new RuntimeException(ex);
            }

         } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
         }
      });
   }

   public void checkForDefaultDevices(AcquisitionEvent event) {
      final String xyStage = core_.getXYStageDevice();
      final String zStage = core_.getFocusDevice();
      if (event.getZPosition() != null && (zStage == null || zStage.equals(""))) {
         throw new RuntimeException("Event requires a z position, but no Core-Focus device is set");
      }
     if (event.getXPosition() != null && (xyStage == null || xyStage.equals(""))) {
          throw new RuntimeException("Event requires an x position, but no Core-XYStage device is set");
     }
   }

   /**
    * Check if this event is compatible with hardware sequencing with any previous events that have been built up
    * in a queue. If it is, merge it into the sequence. Calling this function might result in an event/sequence being
    * dispatched to the hardware, or it might not depending on the conditions. In order to make sure events don't
    * wait around in the queue forever, the function that calls this is required to eventually pass in a
    * SequenceEnd event, which will flush the queue
    *
    * @return eith null, if nothing dispatched, or a Future which can be gotten once the image/sequence fully
    * acquired and images retrieved for subsequent processing/saving
    */
   private Future processAcquisitionEvent(AcquisitionEvent event)  {
      Future imageAcquiredFuture = acqExecutor_.submit(() -> {
         try {
            checkForDefaultDevices(event);

            if (event.acquisition_.isDebugMode()) {
               core_.logMessage("Processing event: " + event.toString() );
            }
            if (event.acquisition_.isDebugMode()) {
               core_.logMessage("checking for sequencing" );
            }
            if (sequencedEvents_.isEmpty() && !event.isAcquisitionSequenceEndEvent()) {
               sequencedEvents_.add(event);
            } else if (isSequencable(sequencedEvents_, event, sequencedEvents_.size() + 1)) {
               //merge event into the sequence
               sequencedEvents_.add(event);
            } else {
               // all events
               AcquisitionEvent sequenceEvent = mergeSequenceEvent(sequencedEvents_);
               sequencedEvents_.clear();
               //Add in the start of the new sequence
               if (!event.isAcquisitionSequenceEndEvent()) {
                  sequencedEvents_.add(event);
               }
               if (event.acquisition_.isDebugMode()) {
                  core_.logMessage("executing acquisition event" );
               }
               try {
                  executeAcquisitionEvent(sequenceEvent);
               } catch (HardwareControlException e) {
                  throw e;
               }
            }
         } catch (InterruptedException e) {
            if (core_.isSequenceRunning()) {
               try {
                  core_.stopSequenceAcquisition();
               } catch (Exception ex) {
                  throw new RuntimeException(ex);

               }
            }
            throw new RuntimeException("Acquisition canceled");
         }
      });
      return imageAcquiredFuture;
   }

   /**
    * If acq finishing, return a Future that can be gotten when whatever sink it
    * goes to is done. Otherwise return null, since individual images can
    * undergo abitrary duplication/deleting dependending on image processors
    *
    * @param event
    * @return
    * @throws InterruptedException
    */
   private void executeAcquisitionEvent(AcquisitionEvent event) throws InterruptedException {
      //check if we should pause until the minimum start time of the event has occured
      while (event.getMinimumStartTimeAbsolute() != null && 
              System.currentTimeMillis() < event.getMinimumStartTimeAbsolute()) {
         long waitTime = event.getMinimumStartTimeAbsolute() - System.currentTimeMillis();
         event.acquisition_.blockUnlessAborted(waitTime);
         try {
            if (event.acquisition_.isAbortRequested()) {
               return;
            }
            Thread.sleep(1);
         } catch (InterruptedException e) {
            //Abort while waiting for next time point
            return;
         }
      }

      if (event.isAcquisitionFinishedEvent()) {
         //signal to finish saving thread and mark acquisition as finished
         if (event.acquisition_.areEventsFinished()) {
            return; //Duplicate finishing event, possibly from x-ing out viewer
         }

         //send message acquisition finished message so things shut down properly
         for (AcquisitionHook h : event.acquisition_.getEventGenerationHooks()) {
            h.run(event);
            h.close();
         }
         for (AcquisitionHook h : event.acquisition_.getBeforeHardwareHooks()) {
            h.run(event);
            h.close();
         }
         for (AcquisitionHook h : event.acquisition_.getAfterHardwareHooks()) {
            h.run(event);
            h.close();
         }
         for (AcquisitionHook h : event.acquisition_.getAfterCameraHooks()) {
            h.run(event);
            h.close();
         }
         for (AcquisitionHook h : event.acquisition_.getAfterExposureHooks()) {
            h.run(event);
            h.close();
         }
         event.acquisition_.addToOutput(new TaggedImage(null, null));
         event.acquisition_.postNotification(AcqNotification.createAcqEventsFinishedNotification());
      } else {
         event.acquisition_.postNotification( new AcqNotification(
                 AcqNotification.Hardware.class, event.getAxesAsJSONString(), AcqNotification.Hardware.PRE_HARDWARE));
         for (AcquisitionHook h : event.acquisition_.getBeforeHardwareHooks()) {
            event = h.run(event);
            if (event == null) {
               return; //The hook cancelled this event
            }
            abortIfRequested(event, null);
         }
         HardwareSequences hardwareSequencesInProgress = new HardwareSequences();
         try {
            prepareHardware(event, hardwareSequencesInProgress);
         } catch (HardwareControlException e) {
            stopHardwareSequences(hardwareSequencesInProgress);
            throw e;
         }
         event.acquisition_.postNotification( new AcqNotification(
                 AcqNotification.Hardware.class, event.getAxesAsJSONString(), AcqNotification.Hardware.POST_HARDWARE));
         for (AcquisitionHook h : event.acquisition_.getAfterHardwareHooks()) {
            event = h.run(event);
            if (event == null) {
               return; //The hook cancelled this event
            }
            abortIfRequested(event, hardwareSequencesInProgress);
         }
         // Hardware hook may have modified wait time, so check again if we should
         // pause until the minimum start time of the event has occurred.
         while (event.getMinimumStartTimeAbsolute() != null &&
               System.currentTimeMillis() < event.getMinimumStartTimeAbsolute()) {
            try {
               abortIfRequested(event, hardwareSequencesInProgress);
               Thread.sleep(1);
            } catch (InterruptedException e) {
               //Abort while waiting for next time point
               return;
            }
         }

         if (event.shouldAcquireImage()) {
            if (event.acquisition_.isDebugMode()) {
               core_.logMessage("acquiring image(s)" );
            }
            try {
               acquireImages(event, hardwareSequencesInProgress);
            } catch (TimeoutException e) {
               // Don't abort on a timeout
               // TODO: this could probably be an option added to the acquisition in the future
               System.err.println("Timeout while acquiring images");
            }

            // if the acquisition was aborted, make sure everything shuts down properly
            abortIfRequested(event, hardwareSequencesInProgress);
         }
      }
   }

   /**
    * Acquire 1 or more images in a sequence, add some metadata, then
    * put them into an output queue.
    *
    * If the event is a sequence and a sequence acquisition is started in the core,
    * It should be completed by the time this method returns.
    *
    * @param event
    * @throws HardwareControlException
    */
   private void acquireImages(final AcquisitionEvent event,
                              HardwareSequences hardwareSequencesInProgress) throws HardwareControlException, TimeoutException {
      HashMap<String, Integer> cameraImageCounts = event.getCameraImageCounts(core_.getCameraDevice());
      try {
         if (event.getSequence() != null && event.getSequence().size() > 1) {
            // Start sequences on one or more cameras
            for (String cameraDeviceName : cameraImageCounts.keySet()) {
               event.acquisition_.postNotification(
                       new AcqNotification(AcqNotification.Camera.class,
                               event.getAxesAsJSONString(), AcqNotification.Camera.PRE_SEQUENCE_STARTED));
               core_.startSequenceAcquisition(cameraDeviceName,
                       cameraImageCounts.get(cameraDeviceName), 0, true);
            }
         } else {
            //snap one image with no sequencing
            event.acquisition_.postNotification(
                    new AcqNotification(AcqNotification.Camera.class,
                            event.getAxesAsJSONString(), AcqNotification.Camera.PRE_SNAP));
            if (event.getCameraDeviceName() != null) {
               String currentCamera = core_.getCameraDevice();
               core_.setCameraDevice(event.getCameraDeviceName());
               core_.snapImage();
               core_.setCameraDevice(currentCamera);
            } else {
               core_.snapImage();
            }
            event.acquisition_.postNotification(
                  new AcqNotification(AcqNotification.Camera.class,
                        event.getAxesAsJSONString(), AcqNotification.Camera.POST_EXPOSURE));
            for (AcquisitionHook h : event.acquisition_.getAfterExposureHooks()) {
               h.run(event);
            }
         }
      } catch (Exception ex) {
         throw new HardwareControlException(ex.getMessage());
      }

      //get elapsed time
      final long currentTime = System.currentTimeMillis();
      if (event.acquisition_.getStartTime_ms() == -1) {
         //first image, initialize
         event.acquisition_.setStartTime_ms(currentTime);
      }

      //need to assign events to images as they come out, assuming they might be in arbitrary order,
      //but that each camera itself is ordered
      HashMap<Integer, LinkedList<AcquisitionEvent>> multiCamAdapterCameraEventLists = null;
      if (event.getSequence() != null) {
         multiCamAdapterCameraEventLists = new HashMap<Integer, LinkedList<AcquisitionEvent>>();
         for (int camIndex = 0; camIndex < core_.getNumberOfCameraChannels(); camIndex++) {
            multiCamAdapterCameraEventLists.put(camIndex, new LinkedList<AcquisitionEvent>());
            for (AcquisitionEvent e: event.getSequence()) {
               multiCamAdapterCameraEventLists.get(camIndex).add(e);
            }
         }
      }

      // Run a hook after the camera sequence acquisition has started. This can be used for
      // external triggering of the camera (when it is in sequence mode).
      // note: SnapImage will block until exposure finishes.
      // If it is desired that AfterCameraHooks trigger cameras
      // in Snap mode, one possibility is that those hooks (or SnapImage) should run in
      // a separate thread, started after snapImage is started. But there is no guarantee
      // with this approach that the camera will be triggered at the correct time.
      for (AcquisitionHook h : event.acquisition_.getAfterCameraHooks()) {
         h.run(event);
      }

      if (event.acquisition_.isDebugMode()) {
         core_.logMessage("images acquired, copying from core" );
      }
      long startCopyTime = System.currentTimeMillis();
      // Loop through and collect all acquired images. There will be
      // (# of images in sequence) x (# of camera channels) of them
      boolean timeout = false;
      for (int i = 0; i < (event.getSequence() == null ? 1 : event.getSequence().size()); i++) {
         if (timeout) {
            // Cancel the rest of the sequence
            stopHardwareSequences(hardwareSequencesInProgress);
            break;
         }
         double exposure;

         try {
            exposure = event.getExposure() == null ? core_.getExposure() : event.getExposure();
         } catch (Exception ex) {
            throw new RuntimeException("Couldnt get exposure form core");
         }
         long numCamChannels = core_.getNumberOfCameraChannels();

         boolean needToRunAfterExposureHooks = event.acquisition_.getAfterExposureHooks().iterator().hasNext();
         for (int camIndex = 0; camIndex < numCamChannels; camIndex++) {
            TaggedImage ti = null;
            String cameraName = null;
            while (ti == null) {
               if (event.acquisition_.isAbortRequested()) {
                  return;
               }

               try {
                  if (event.getSequence() != null && event.getSequence().size() > 1) {
                     if (core_.isBufferOverflowed()) {
                        throw new RuntimeException("Sequence buffer overflow");
                     }
                     try {
                        ti = core_.popNextTaggedImage();
                        cameraName = ti.tags.getString("Camera");
                     } catch (Exception e) {
                        //continue waiting
                        if (!core_.isSequenceRunning() && core_.getRemainingImageCount() == 0) {
                           throw new RuntimeException("Expected images did not arrive in circular buffer");
                        }
                        // check it timeout has been exceeded. This is used in the case of a camera waiting for
                        // a trigger that never comes.
                        if (event.getSequence().get(i).getTimeout_ms() != null) {
                           if (System.currentTimeMillis() - startCopyTime > event.getSequence().get(i).getTimeout_ms()) {
                              timeout = true;
                              core_.stopSequenceAcquisition();
                              while (core_.isSequenceRunning()) {
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException ex) {
                                        throw new RuntimeException("Interrupted while waiting for sequence to stop");
                                    }
                              }
                              break;
                           }
                        }
                     }
                  } else {
                     try {
                        // TODO: probably there should be a timeout here too, but I'm
                        //  not sure the snapImage system supports it (as opposed to sequences)
                        ti = core_.getTaggedImage(camIndex);
                        cameraName = core_.getCameraDevice();
                     } catch (Exception e) {
                        //continue waiting
                     }
                  }
               } catch (Exception ex) {
                  // Sequence buffer overflow
                  HardwareControlException e = new HardwareControlException(ex.toString());
                  event.acquisition_.abort(e);
                  throw e;
               }
            }
            if (needToRunAfterExposureHooks) {
               for (String cameraDeviceName : cameraImageCounts.keySet()) {
                  try {
                     if (core_.isSequenceRunning(cameraDeviceName)) {
                        // All sequences are not yet done, this will be handled on another iteration
                        // of the loop
                        break;
                     }
                  } catch (Exception e) {
                     // not sure hos this would happen
                     event.acquisition_.abort(e);
                     throw new RuntimeException(e);
                  }
               }
               event.acquisition_.postNotification(
                     new AcqNotification(AcqNotification.Camera.class,
                           event.getAxesAsJSONString(), AcqNotification.Camera.POST_EXPOSURE));
               for (AcquisitionHook h : event.acquisition_.getAfterExposureHooks()) {
                  h.run(event);
               }
               needToRunAfterExposureHooks = false;
            }
            if (timeout) {
               break;
            }


            // Doesnt seem to be a version in the API in which you dont have to do this
            int actualCamIndex = camIndex;
            if (ti.tags.has("Multi Camera-CameraChannelIndex")) {
               try {
                  actualCamIndex = ti.tags.getInt("Multi Camera-CameraChannelIndex");
                  if (numCamChannels == 1) {
                     //probably a mistake in the core....
                     actualCamIndex = 0; // Override index because not using multi cam mode right now
                  }
               } catch (Exception e) {
                  throw new RuntimeException(e);
               }
            }
            AcquisitionEvent correspondingEvent = event;
            if (event.getSequence() != null) {
               // Find the event that corresponds to the camera that captured this image.
               // This assumes that the images from a single camera are in order
               // in the sequence, though different camera images may be interleaved
               if (event.getSequence().get(0).getCameraDeviceName() != null) {
                  // camera is specified in the acquisition event. Find the first event that matches
                  // this camera name.
                  final String theCameraName = cameraName;
                  correspondingEvent = multiCamAdapterCameraEventLists.get(actualCamIndex).stream().filter(
                          e -> e.getCameraDeviceName() != null && e.getCameraDeviceName().equals(theCameraName)).findFirst().get();
                  multiCamAdapterCameraEventLists.get(actualCamIndex).remove(correspondingEvent);
               } else {
                  // multi camera adapter or just using the default camera
                  correspondingEvent = multiCamAdapterCameraEventLists.get(actualCamIndex).remove(0);
               }
            }
            // add standard metadata
            AcqEngMetadata.addImageMetadata(ti.tags, correspondingEvent,
                    currentTime - correspondingEvent.acquisition_.getStartTime_ms(), exposure);
            // add user metadata specified in the event
            try {
               correspondingEvent.acquisition_.addTagsToTaggedImage(ti.tags, correspondingEvent.getTags());
            } catch (JSONException jse) {
               core_.logMessage("Error adding tags to image metadata", false);
            }
            correspondingEvent.acquisition_.addToImageMetadata(ti.tags);
            correspondingEvent.acquisition_.addToOutput(ti);
         }
      }
      for (AcquisitionHook h : event.acquisition_.getAfterSequenceHooks()) {
         h.run(event);
      }


      if (timeout) {
         throw new TimeoutException("Timeout waiting for images to arrive in circular buffer");
      }
   }

   private void abortIfRequested(AcquisitionEvent event,
                                 HardwareSequences hardwareSequencesInProgress) {
      if (event.acquisition_.isAbortRequested()) {
         if (hardwareSequencesInProgress != null) {
            stopHardwareSequences(hardwareSequencesInProgress);
         }
         return;
      }
   }

   private void stopHardwareSequences(HardwareSequences hardwareSequencesInProgress) {
      // Stop any hardware sequences
      for (String deviceName : hardwareSequencesInProgress.deviceNames) {
         try {
            if (core_.getDeviceType(deviceName).toString().equals("StageDevice")) {
               core_.stopStageSequence(deviceName);
            } else if (core_.getDeviceType(deviceName).toString().equals("XYStageDevice")) {
               core_.stopXYStageSequence(deviceName);
            } else if (core_.getDeviceType(deviceName).toString().equals("CameraDevice")) {
               core_.stopSequenceAcquisition(core_.getCameraDevice());
            }
         } catch (Exception ee) {
            core_.logMessage("Error stopping hardware sequence: " + ee.getMessage());
         }
      }
      // Stop any property sequences
      for (int i = 0; i < hardwareSequencesInProgress.propertyNames.size(); i++) {
         try {
            core_.stopPropertySequence(hardwareSequencesInProgress.propertyDeviceNames.get(i),
                  hardwareSequencesInProgress.propertyNames.get(i));
         } catch (Exception ee) {
            core_.logMessage("Error stopping property sequence: " + ee.getMessage());
         }
      }
      try {
         core_.clearCircularBuffer();
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Move all the hardware to the proper positions in preparation for the next phase of the acquisition event
    * (i.e. collecting images, or maybe to be added in the future, projecting slm patterns). If this event represents
    * a single event, the Engine will talk to each piece of hardware in series. If this event represents a sequence,
    * it will load all the values of that sequence into each hardware component, and wait for the sequence to be
    * triggered to start, which will happen in another function
    *
    * @param event
    * @param hardwareSequencesInProgress -- keep track of which hardware sequences have been started
    * @throws HardwareControlException
    * @return Data class describing which hardware devices have had sequences activated
    */
   private void prepareHardware(final AcquisitionEvent event,
                                             HardwareSequences hardwareSequencesInProgress) throws HardwareControlException {
      //Get the hardware specific to this acquisition
      final String xyStage = core_.getXYStageDevice();
      final String zStage = core_.getFocusDevice();
      final String slm = core_.getSLMDevice();
      //prepare sequences if applicable
      if (event.getSequence() != null) {
         try {
            DoubleVector zSequence = event.isZSequenced() ? new DoubleVector() : null;
            DoubleVector xSequence = event.isXYSequenced() ? new DoubleVector() : null;
            DoubleVector ySequence = event.isXYSequenced() ? new DoubleVector() : null;
            DoubleVector exposureSequence_ms =event.isExposureSequenced() ? new DoubleVector() : null;
            String group = event.getSequence().get(0).getConfigGroup();
            Configuration config = event.getSequence().get(0).getConfigPreset() == null ? null :
                  core_.getConfigData(group, event.getSequence().get(0).getConfigPreset());
            LinkedList<StrVector> propSequences = event.isConfigGroupSequenced() ? new LinkedList<StrVector>() : null;
            for (AcquisitionEvent e : event.getSequence()) {
               if (zSequence != null) {
                  zSequence.add(e.getZPosition());
               }
               if (xSequence != null) {
                  xSequence.add(e.getXPosition());
               }
               if (ySequence != null) {
                  ySequence.add(e.getYPosition());
               }
               if (exposureSequence_ms != null) {
                  exposureSequence_ms.add(e.getExposure());
               }
               //Set sequences for all channel properties
               if (propSequences != null) {
                  for (int i = 0; i < config.size(); i++) {
                     PropertySetting ps = config.getSetting(i);
                     String deviceName = ps.getDeviceLabel();
                     String propName = ps.getPropertyName();
                     if (e == event.getSequence().get(0)) { //first property
                        propSequences.add(new StrVector());
                     }
                     Configuration channelPresetConfig = core_.getConfigData(group,
                           e.getConfigPreset());
                     String propValue = channelPresetConfig.getSetting(deviceName, propName).getPropertyValue();
                     if (core_.isPropertySequenceable(deviceName, propName)) {
                        propSequences.get(i).add(propValue);
                     }
                  }
               }
            }
            hardwareSequencesInProgress.deviceNames.add(core_.getCameraDevice());
            //Now have built up all the sequences, apply them
            if (event.isExposureSequenced()) {
               core_.loadExposureSequence(core_.getCameraDevice(), exposureSequence_ms);
               // already added camera
            }
            if (event.isXYSequenced()) {
               core_.loadXYStageSequence(xyStage, xSequence, ySequence);
               hardwareSequencesInProgress.deviceNames.add(xyStage);
            }
            if (event.isZSequenced()) {
               core_.loadStageSequence(zStage, zSequence);
               hardwareSequencesInProgress.deviceNames.add(zStage);
            }
            if (event.isConfigGroupSequenced()) {
               for (int i = 0; i < config.size(); i++) {
                  PropertySetting ps = config.getSetting(i);
                  String deviceName = ps.getDeviceLabel();
                  String propName = ps.getPropertyName();
                  if (propSequences.get(i).size() > 0) {
                     core_.loadPropertySequence(deviceName, propName, propSequences.get(i));
                     hardwareSequencesInProgress.propertyNames.add(propName);
                     hardwareSequencesInProgress.propertyDeviceNames.add(deviceName);
                  }
               }
            }
            core_.prepareSequenceAcquisition(core_.getCameraDevice());


         } catch (Exception ex) {
            ex.printStackTrace();
            throw new HardwareControlException(ex.getMessage());
         }
      }

      //compare to last event to see what needs to change
      if (lastEvent_ != null && lastEvent_.acquisition_ != event.acquisition_) {
         lastEvent_ = null; //update all hardware if switching to a new acquisition
      }
      /////////////////////////////Z stage////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               if (event.isZSequenced()) {
                  core_.startStageSequence(zStage);
               } else  {
                  Double previousZ = lastEvent_ == null ? null : lastEvent_.getSequence() == null ? lastEvent_.getZPosition() :
                        lastEvent_.getSequence().get(0).getZPosition();
                  Double currentZ =  event.getSequence() == null ? event.getZPosition() : event.getSequence().get(0).getZPosition();
                  if (currentZ == null) {
                     return;
                  }
                  boolean change = previousZ == null || !previousZ.equals(currentZ);
                  if (!change) {
                     return;
                  }

                  //wait for it to not be busy (is this even needed?)
                  core_.waitForDevice(zStage);
                  //Move Z
                  core_.setPosition(zStage, currentZ);
                  //wait for move to finish
                  core_.waitForDevice(zStage);
               }
            } catch (Exception ex) {
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Moving Z device");

      /////////////////////////////Other stage devices ////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               // TODO implement Z sequencing for other devices
//               if (event.isZSequenced()) {
//                  core_.startStageSequence(zStage);
//               } else  {
               for (String stageDeviceName : event.getStageDeviceNames()) {
                  // TODO: could reimplement logic here to decide when to try to move
//                  Double previousZ = lastEvent_ == null ? null :
//                        lastEvent_.getSequence() == null ? lastEvent_.getZPosition() :
//                              lastEvent_.getSequence().get(0).getZPosition();
//                  Double currentZ = event.getSequence() == null ? event.getZPosition() :
//                        event.getSequence().get(0).getZPosition();
//                  if (currentZ == null) {
//                     return;
//                  }
//                  boolean change = previousZ == null || !previousZ.equals(currentZ);
//                  if (!change) {
//                     return;
//                  }

                  //wait for it to not be busy (is this even needed?)
                  core_.waitForDevice(stageDeviceName);
                  //Move Z
                  core_.setPosition(stageDeviceName, event.getStageSingleAxisStagePosition(stageDeviceName));
                  //wait for move to finish
                  core_.waitForDevice(stageDeviceName);
               }
//               }
            } catch (Exception ex) {
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Moving other stage devices");

      /////////////////////////////XY Stage////////////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               if (event.isXYSequenced()) {
                  core_.startXYStageSequence(xyStage);
               } else  {
                  //could be sequenced over other devices, in that case get xy position from first in sequence
                  Double prevXPosition = lastEvent_ == null ? null :
                        lastEvent_.getSequence() == null ? lastEvent_.getXPosition() : lastEvent_.getSequence().get(0).getXPosition();
                  Double xPosition = event.getSequence() == null ? event.getXPosition() : event.getSequence().get(0).getXPosition();
                  Double prevYPosition = lastEvent_ == null ? null :
                        lastEvent_.getSequence() == null ? lastEvent_.getYPosition() : lastEvent_.getSequence().get(0).getYPosition();
                  Double yPosition = event.getSequence() == null ? event.getYPosition() : event.getSequence().get(0).getYPosition();
                  boolean previousXYDefined = event != null && prevXPosition != null && prevYPosition != null;
                  boolean currentXYDefined = event != null && xPosition != null && yPosition != null;
                  if (!currentXYDefined) {
                     return;
                  }
                  boolean xyChanged = !previousXYDefined || !prevXPosition.equals(xPosition) || !prevYPosition.equals(yPosition);
                  if (!xyChanged) {
                     return;
                  }
                  //wait for it to not be busy (is this even needed?)
                  core_.waitForDevice(xyStage);
                  //Move XY
                  core_.setXYPosition(xyStage, xPosition, yPosition);
                  //wait for move to finish
                  core_.waitForDevice(xyStage);
               }
            } catch (Exception ex) {
               core_.logMessage(stackTraceToString(ex));
               ex.printStackTrace();
               throw new HardwareControlException(ex.getMessage());
            }
         }
      }, "Moving XY stage");

      /////////////////////////////Channels//////////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               //Get the values of current channel, pulling from the first event in a sequence if one is present
               String currentConfig = event.getSequence() == null ?
                     event.getConfigPreset() : event.getSequence().get(0).getConfigPreset();
               String currentGroup = event.getSequence() == null ?
                     event.getConfigGroup() : event.getSequence().get(0).getConfigGroup();
               String previousConfig = lastEvent_ == null ? null : lastEvent_.getSequence() == null ?
                     lastEvent_.getConfigPreset() : lastEvent_.getSequence().get(0).getConfigPreset();

               boolean newChannel = currentConfig != null && (previousConfig == null || !previousConfig.equals(currentConfig));
               if ( newChannel ) {
                  //set exposure
                  if (event.getExposure() != null) {
                     core_.setExposure(event.getExposure());
                  }
                  //set other channel props
                  core_.setConfig(currentGroup, currentConfig);
                  // TODO: haven't tested if this is actually needed
                  core_.waitForConfig(currentGroup, currentConfig);
               }

               if (event.isConfigGroupSequenced()) {
                  //Channels
                  String group = event.getSequence().get(0).getConfigGroup();
                  Configuration config = core_.getConfigData(group, event.getSequence().get(0).getConfigPreset());
                  for (int i = 0; i < config.size(); i++) {
                     PropertySetting ps = config.getSetting(i);
                     String deviceName = ps.getDeviceLabel();
                     String propName = ps.getPropertyName();
                     if (core_.isPropertySequenceable(deviceName, propName)) {
                        core_.startPropertySequence(deviceName, propName);
                     }
                  }
               }
            } catch (Exception ex) {
               ex.printStackTrace();
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Changing channels");

      /////////////////////////////Camera exposure//////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               if (event.isExposureSequenced()) {
                  core_.startExposureSequence(core_.getCameraDevice());
               } else {
                  Double currentExposure = event.getExposure();
                  Double prevExposure = lastEvent_ == null ? null : lastEvent_.getExposure();
                  boolean changeExposure = currentExposure != null &&
                        (prevExposure == null || !prevExposure.equals(currentExposure));
                  if (changeExposure) {
                     core_.setExposure(currentExposure);
                  }
               }
            } catch (Exception ex) {
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Changing exposure");


      /////////////////////////////   SLM    //////////////////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               if (event.getSLMImage() != null) {
                  if (event.getSLMImage() instanceof byte[]) {
                     core_.setSLMImage(slm, (byte[]) event.getSLMImage());
                  } else if (event.getSLMImage() instanceof int[]) {
                     core_.setSLMImage(slm, (int[]) event.getSLMImage());
                  } else {
                     throw new RuntimeException("SLM api only supports 8 bit and 32 bit patterns");
                  }
               }
            } catch (Exception ex) {
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Setting SLM pattern");

      //////////////////////////   Arbitrary Properties //////////////////////////////////
      loopHardwareCommandRetries(new Runnable() {
         @Override
         public void run() {
            try {
               for (String[] s : event.getAdditonalProperties()) {
                  core_.setProperty(s[0], s[1], s[2]);
               }
            } catch (Exception ex) {
               throw new HardwareControlException(ex.getMessage());
            }

         }
      }, "Changing additional properties");

      //keep track of last event to know what state the hardware was in without having to query it
      lastEvent_ = event.getSequence() == null ? event : event.getSequence().get(event.getSequence().size() - 1);
   }

   /**
    * Attempt a hardware command multiple times if it throws an exception. If still doesn't
    * work after those tries, give up and declare exception
    *
    * @param r runnable containing the command
    * @param commandName name given to the command for loggin purposes
    * @throws HardwareControlException
    */
   private void loopHardwareCommandRetries(Runnable r, String commandName) throws HardwareControlException {
      for (int i = 0; i < HARDWARE_ERROR_RETRIES; i++) {
         try {
            r.run();
            return;
         } catch (Exception e) {
            core_.logMessage(stackTraceToString(e));
            System.err.println(getCurrentDateAndTime() + ": Problem "
                    + commandName + "\n Retry #" + i + " in " + DELAY_BETWEEN_RETRIES_MS + " ms");
            try {
               Thread.sleep(DELAY_BETWEEN_RETRIES_MS);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
                throw new HardwareControlException(e1.getMessage());
            }
         }
      }
      throw new HardwareControlException(commandName + " unsuccessful");
   }

   private static String getCurrentDateAndTime() {
      DateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      Calendar calobj = Calendar.getInstance();
      return df.format(calobj.getTime());
   }

   /**
    * Check if all the hardware changes between one event and the next compatible with hardware sequencing.
    * If so, they can be merged into a single event which will take place with hardware triggering
    *
    * @param previousEvents list of previous events
    * @param nextEvent next event to sequence
    * @return true if they can be combined
    */
   private static boolean isSequencable(List<AcquisitionEvent> previousEvents,
                                        AcquisitionEvent nextEvent, int newSeqLength) {
      try {
         if (nextEvent.isAcquisitionSequenceEndEvent() || nextEvent.isAcquisitionFinishedEvent()) {
            return false;
         }

         AcquisitionEvent previousEvent = previousEvents.get(previousEvents.size() - 1);

         //check all properties in group
         if (previousEvent.getConfigPreset() != null && nextEvent.getConfigPreset() != null
                 && !previousEvent.getConfigPreset().equals(nextEvent.getConfigPreset())) {
            //check all properties in the channel
            Configuration config1 = core_.getConfigData(previousEvent.getConfigGroup(), previousEvent.getConfigPreset());
            Configuration config2 = core_.getConfigData(nextEvent.getConfigGroup(), nextEvent.getConfigPreset());
            for (int i = 0; i < config1.size(); i++) {
               PropertySetting ps1 = config1.getSetting(i);
               String deviceName = ps1.getDeviceLabel();
               String propName = ps1.getPropertyName();
               String propValue1 = ps1.getPropertyValue();
               PropertySetting ps2 = config2.getSetting(i);
               String propValue2 = ps2.getPropertyValue();
               if (!propValue1.equals(propValue2)) {
                  if (!core_.isPropertySequenceable(deviceName, propName)) {
                     return false;
                  }
                  if (core_.getPropertySequenceMaxLength(deviceName, propName) < newSeqLength) {
                     return false;
                  }
               }
            }
         }
         //TODO check for arbitrary additional properties in the acq event for being sequencable

         //z stage
         if (previousEvent.getZPosition() != null && nextEvent.getZPosition() != null &&
                 (double)previousEvent.getZPosition() != (double)nextEvent.getZPosition()) {
            if (!core_.isStageSequenceable(core_.getFocusDevice())) {
               return false;
            }
            if (newSeqLength > core_.getStageSequenceMaxLength(core_.getFocusDevice())) {
               return false;
            }
         }

         // arbitrary z stages
         // TODO implement sequences along arbitrary other stage decives
         for (String stageDevice : previousEvent.getStageDeviceNames() ) {
            return false;
         }

         //xy stage
         if ((previousEvent.getXPosition() != null && nextEvent.getXPosition() != null && (double) previousEvent.getXPosition() != (double) nextEvent.getXPosition()) ||
                 (previousEvent.getYPosition() != null && nextEvent.getYPosition() != null && (double) previousEvent.getYPosition() != (double) nextEvent.getYPosition())) {
            if (!core_.isXYStageSequenceable(core_.getXYStageDevice())) {
               return false;
            }
            if (newSeqLength > core_.getXYStageSequenceMaxLength(core_.getXYStageDevice())) {
               return false;
            }
         }

         if (previousEvent.getCameraDeviceName() == null) {
            // Using the Core-Camera, the default

            //camera exposure
            if (previousEvent.getExposure() != null && nextEvent.getExposure() != null &&
                  Double.compare(previousEvent.getExposure(), nextEvent.getExposure()) != 0 &&
                  !core_.isExposureSequenceable(core_.getCameraDevice())) {
               return false;
            }
            if (core_.isExposureSequenceable(core_.getCameraDevice()) &&
                  newSeqLength > core_.getExposureSequenceMaxLength(core_.getCameraDevice())) {
               return false;
            }
         }


         // If there is a nonzero delay between events, then its not sequencable
         if (previousEvent.getTIndex() != null && nextEvent.getTIndex() != null && !previousEvent.getTIndex().equals(nextEvent.getTIndex())) {
            if (previousEvent.getMinimumStartTimeAbsolute() != null && nextEvent.getMinimumStartTimeAbsolute() != null &&
                    !previousEvent.getMinimumStartTimeAbsolute().equals(nextEvent.getMinimumStartTimeAbsolute())) {
               return false;
            }
         }

         return true;
      } catch (Exception ex) {
         throw new RuntimeException(ex);
      }
   }

   /**
    * If it's a sequence length of one, just return the acquisition event. If it's a longer sequence,
    * create a special acquisition event corresponding to a multi-image hardware-triggered setup
    *
    * @param eventList list of one or more AcquisitionEvents
    * @return
    */
   private AcquisitionEvent mergeSequenceEvent(List<AcquisitionEvent> eventList) {
      if (eventList.size() == 1) {
         return eventList.get(0);
      }
      return new AcquisitionEvent(eventList);
   }

   public static String stackTraceToString(Exception ex) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      ex.printStackTrace(pw);
      String sStackTrace = sw.toString();
      return sStackTrace;
   }
}



class HardwareControlException extends RuntimeException {

   public HardwareControlException() {
      super();
   }

   public HardwareControlException(String s) {
      super(s);
   }
}
