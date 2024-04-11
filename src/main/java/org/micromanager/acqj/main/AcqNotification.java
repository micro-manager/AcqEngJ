package org.micromanager.acqj.main;

import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;

public class AcqNotification {

   public class Acquisition {
      public static final String ACQ_STARTED = "acq_started";
      public static final String EVENTS_FINISHED = "acq_events_finished";

   }

   public class Hardware {
      public static final String PRE_HARDWARE = "pre_hardware";
      public static final String PRE_Z_DRIVE = "pre_z_drive";
      public static final String POST_HARDWARE = "post_hardware";

   }

   public class Camera {
      public static final String PRE_SEQUENCE_STARTED = "pre_sequence_started";
      public static final String PRE_SNAP = "pre_snap";
      public static final String POST_EXPOSURE = "post_exposure";

   }

   public class Image {
      public static final String IMAGE_SAVED = "image_saved";
      public static final String DATA_SINK_FINISHED = "data_sink_finished";
   }

   public static String notificationTypeToString(Class type) {
      if (type.equals(Acquisition.class)) {
         return "global";
      } else if (type.equals(Hardware.class)) {
         return "hardware";
      } else if (type.equals(Camera.class)) {
         return "camera";
      } else if (type.equals(Image.class)) {
         return "image";
      } else {
         throw new RuntimeException("Unknown notification type");
      }
   }

   final public String type_;
   final String identifier_;
   final public String phase_;


   public AcqNotification(Class type, String identifier, String phase) {
      type_ = notificationTypeToString(type);
      identifier_ = identifier;
      phase_ = phase;
   }

   public static AcqNotification createAcqEventsFinishedNotification() {
      return new AcqNotification(Acquisition.class, null, Acquisition.EVENTS_FINISHED);
   }

   public static AcqNotification createAcqStartedNotification() {
      return new AcqNotification(Acquisition.class, null, Acquisition.ACQ_STARTED);
   }

   public static AcqNotification createDataSinkFinishedNotification() {
      return new AcqNotification(Image.class, null, Image.DATA_SINK_FINISHED);
   }

   public static AcqNotification createImageSavedNotification(String imageDescriptor) {
      return new AcqNotification(Image.class, imageDescriptor, Image.IMAGE_SAVED);
   }

   public JSONObject toJSON() throws JSONException {
      JSONObject message = new JSONObject();
      message.put("type", type_);

      if (phase_ != null) {
         message.put("phase", phase_);
      }

      if (identifier_ != null) {
         message.put("id", identifier_.toString());
      }

      return message;
   }

   public boolean isAcquisitionEventsFinishedNotification() {
      return phase_.equals(Acquisition.EVENTS_FINISHED);
   }

   public boolean isDataSinkFinishedNotification() {
      return phase_.equals(Image.DATA_SINK_FINISHED);
   }

   public boolean isImageSavedNotification() {
      return type_.equals(Image.IMAGE_SAVED);
   }
}
