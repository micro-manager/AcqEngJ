package org.micromanager.acqj.main;

import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;

public class AcqNotification {

   public static enum TYPE {
      ACQ_STARTED("acq_started"),
      ACQ_FINISHED("acq_finished"),
      HARDWARE("hardware"),
      CAMERA_NOTIFICATIONS("camera");

      private final String type;

      TYPE(String stage) {
         this.type = stage;
      }

      public String toString() {
         return type;
      }
   }

   public static enum PHASE {
      PRE_HARDWARE_STAGE("pre_hardware"),
      POST_HARDWARE_STAGE("post_hardware"),
      PRE_SEQUENCE_STARTED("pre_sequence_started"),
      PRE_SNAP("pre_snap"),
      POST_EXPOSURE_STAGE("post_exposure");

      private final String phase;

      PHASE(String phase) {
         this.phase = phase;
      }

      public String toString() {
         return phase;
      }
   }


   final public TYPE type_;
   final AcquisitionEvent event_;
   final public PHASE phase_;


   public AcqNotification(TYPE type, AcquisitionEvent event, PHASE phase) {
      type_ = type;
      event_ = event;
      phase_ = phase;
   }

   public static AcqNotification createAcqFinishedEvent() {
      return new AcqNotification(TYPE.ACQ_FINISHED, null, null);
   }

   public static AcqNotification createAcqStartedEvent() {
      return new AcqNotification(TYPE.ACQ_STARTED, null, null);
   }

   public JSONObject toJSON() throws JSONException {
      JSONObject message = new JSONObject();
      message.put("type", type_);

      if (event_ != null) {
         if (event_.getSequence() == null) {
            message.put("axes", AcqEngMetadata.getAxesAsJSON(event_.getAxisPositions()));
         } else {
            JSONArray sequenceAxes = new JSONArray();
            for (AcquisitionEvent event : event_.getSequence()) {
               sequenceAxes.put(AcqEngMetadata.getAxesAsJSON(event.getAxisPositions()));
            }
            message.put("axes", sequenceAxes);
         }
      }
      if (phase_ != null) {
         message.put("phase", phase_);
      }

      return message;
   }

   public boolean isAcquisitionFinishedNotification() {
      return type_.toString().equals(TYPE.ACQ_FINISHED.toString());
   }
}
