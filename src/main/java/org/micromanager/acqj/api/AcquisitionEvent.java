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

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.internal.acqengj.AffineTransformUtils;
import org.micromanager.acqj.internal.acqengj.Engine;

/**
 * Information about the acquisition of a single image or a sequence of image
 *
 */
public class AcquisitionEvent {


   enum SpecialFlag {
      AcqusitionFinished,
      AcqusitionSequenceEnd
   };

   public Acquisition acquisition_;

   //For encoded time, z indices (or other generic axes)
   //XY position and channel indices should not be encoded because acq engine
   //will dynamically infer them at runtime
   private HashMap<String, Integer> axisPositions_ = new HashMap<String, Integer>();

   private String channelGroup_, channelConfig_ = null;
   private Double exposure_ = null; //leave null to keep exposaure unchanged

   private Long miniumumStartTime_ms_; //For pausing between time points

   //positions for devices that are generically hardcoded into MMCore
   private Double zPosition_ = null, xPosition_ = null, yPosition_ = null;
   //info for xy positions arranged in a grid
   private Integer gridRow_ = null, gridCol_ = null;
   //TODO: SLM, Galvo, etc

   //Keep shutter open during non sequence acquisition
   private Boolean keepShutterOpen_ = null;

   //Option to not acquire an image for shutter only and SLM events
   private Boolean acquireImage_ = null;

   //Pattern to project onto SLM. Can either be int[] or byte[]
   private Object slmImage_ = null;

   //Arbitary additional properties
   private TreeSet<ThreeTuple> properties_ = new TreeSet<ThreeTuple>();

   //for hardware sequencing
   private List<AcquisitionEvent> sequence_ = null;
   private boolean xySequenced_ = false, zSequenced_ = false, exposureSequenced_ = false, channelSequenced_ = false;

   //To specify end of acquisition or end of sequence
   private SpecialFlag specialFlag_;

   public AcquisitionEvent(AcquisitionAPI acq) {
      acquisition_ = (Acquisition) acq;
   }

   /**
    * Constructor used for running a list of events in a sequence It should have
    * already been verified that these events are sequencable. This constructor
    * figures out which device types need a sequence and which ones can be left
    * with a single value
    *
    * @param sequence
    */
   public AcquisitionEvent(List<AcquisitionEvent> sequence) {
      acquisition_ = sequence.get(0).acquisition_;
      sequence_ = new ArrayList<>();
      sequence_.addAll(sequence);
      TreeSet<Double> zPosSet = new TreeSet<Double>();
      HashSet<Double> xPosSet = new HashSet<Double>();
      HashSet<Double> yPosSet = new HashSet<Double>();
      TreeSet<Double> exposureSet = new TreeSet<Double>();
      TreeSet<String> configSet = new TreeSet<String>();
      for (int i = 0; i < sequence_.size(); i++) {
         if (sequence_.get(i).zPosition_ != null) {
            zPosSet.add(sequence_.get(i).zPosition_);
         }
         if (sequence_.get(i).xPosition_ != null) {
            xPosSet.add(sequence_.get(i).getXPosition());
         }
         if (sequence_.get(i).yPosition_ != null) {
            yPosSet.add(sequence_.get(i).getYPosition());
         }
         if (sequence_.get(i).exposure_ != null) {
            exposureSet.add(sequence_.get(i).getExposure());
         }
         if (sequence_.get(i).channelConfig_ != null) {
            configSet.add(sequence_.get(i).getChannelConfig());
         }
      }
      //TODO: add SLM sequences
      exposureSequenced_ = exposureSet.size() > 1;
      channelSequenced_ = configSet.size() > 1;
      xySequenced_ = xPosSet.size() > 1 && yPosSet.size() > 1;
      zSequenced_ = zPosSet.size() > 1;
   }

   public AcquisitionEvent copy() {
      AcquisitionEvent e = new AcquisitionEvent(this.acquisition_);
      e.axisPositions_ = (HashMap<String, Integer>) axisPositions_.clone();
      e.channelConfig_ = channelConfig_;
      e.channelGroup_ = channelConfig_;
      e.zPosition_ = zPosition_;
      e.xPosition_ = xPosition_;
      e.yPosition_ = yPosition_;
      e.gridRow_ = gridRow_;
      e.gridCol_ = gridCol_;
      e.miniumumStartTime_ms_ = miniumumStartTime_ms_;
      e.keepShutterOpen_ = keepShutterOpen_;
      e.slmImage_ = slmImage_;
      e.acquireImage_ = acquireImage_;
      e.properties_ = new TreeSet<ThreeTuple>(this.properties_);
      return e;
   }

   private static JSONObject eventToJSON(AcquisitionEvent e) {
      try {
         JSONObject json = new JSONObject();
         if (e.isAcquisitionFinishedEvent()) {
            json.put("special", "acquisition-end");
            return json;
         } else if (e.isAcquisitionSequenceEndEvent()) {
            json.put("special", "sequence-end");
            return json;
         }

         //timelpases
         if (e.miniumumStartTime_ms_ != null) {
            json.put("min_start_time", e.miniumumStartTime_ms_ / 1000);
         }

         if (e.hasChannel()) {
            JSONObject channel = new JSONObject();
            channel.put("group", e.channelGroup_);
            channel.put("config", e.channelConfig_);
            json.put("channel", channel);
         }

         if (e.exposure_ != null) {
            json.put("exposure", e.exposure_);
         }

         if (e.keepShutterOpen_ != null && e.keepShutterOpen_) {
            json.put("keep_shutter_open", true);
         }

         if (e.slmImage_ != null) {
            json.put("slm_pattern", e.slmImage_);
         }

         //Coordinate indices
         JSONObject axes = new JSONObject();
         for (String axis : e.axisPositions_.keySet()) {
            axes.put(axis, e.axisPositions_.get(axis));
         }
         if (axes.length() > 0) {
            json.put("axes", axes);
         }

         //Things for which a generic device tyoe and functions to operate on
         //it exists in MMCore
         if (e.zPosition_ != null) {
            json.put("z", e.zPosition_);
         }
         if (e.xPosition_ != null) {
            json.put("x", e.xPosition_);
         }
         if (e.yPosition_ != null) {
            json.put("y", e.yPosition_);
         }
         if (e.gridRow_ != null) {
            json.put("row", e.gridRow_);
         }
         if (e.gridCol_ != null) {
            json.put("col", e.gridCol_);
         }

         //TODO: galvo
         //TODO: more support for imperative API calls (i.e. SLM set image)
         //Arbitrary extra properties
         JSONArray props = new JSONArray();
         for (ThreeTuple t : e.properties_) {
            JSONArray prop = new JSONArray();
            prop.put(t.dev);
            prop.put(t.prop);
            prop.put(t.val);
            props.put(prop);
         }
         json.put("properties", props);

         return json;
      } catch (JSONException ex) {
         throw new RuntimeException(ex);
      }
   }

   private static AcquisitionEvent eventFromJSON(JSONObject json, AcquisitionAPI acq) {
      try {
         if (json.has("special")) {
            if (json.getString("special").equals("acquisition-end")) {
               return AcquisitionEvent.createAcquisitionFinishedEvent(acq);
            } else if (json.getString("special").equals("sequence-end")) {
               return AcquisitionEvent.createAcquisitionSequenceEndEvent(acq);
            }
         }

         AcquisitionEvent event = new AcquisitionEvent(acq);

         //convert JSON axes to internal hashmap
         if (json.has("axes")) {
            JSONObject axes = json.getJSONObject("axes");
            axes.keys().forEachRemaining((String axisLabel) -> {
               try {
                  event.axisPositions_.put(axisLabel, axes.getInt(axisLabel));
               } catch (JSONException ex) {
                  throw new RuntimeException(ex);
               }
            });
         }
         //timelpases
         if (json.has("min_start_time")) {
            event.miniumumStartTime_ms_ = (long) (json.getDouble("min_start_time") * 1000);
         }

         //channel name
         if (json.has("channel")) {
            event.channelConfig_ = json.getJSONObject("channel").getString("config");
            event.channelGroup_ = json.getJSONObject("channel").getString("group");
         }
         if (json.has("exposure")) {
            event.exposure_ = json.getDouble("exposure");
         }

         //Things for which a generic device type and imperative API exists in MMCore
         if (json.has("z")) {
            event.zPosition_ = json.getDouble("z");
         }
         if (json.has("row")) {
            event.gridRow_ = json.getInt("row");
         }
         if (json.has("col")) {
            event.gridCol_ = json.getInt("col");
         }
         if (event.gridRow_ != null && event.gridCol_ != null) {
            int posIndex = acq.getPixelStageTranslator().getPositionIndices(
                    new int[]{event.gridRow_}, new int[]{event.gridCol_})[0];

            //infer XY stage position based on affine transform
            Point2D.Double xyPos = acq.getPixelStageTranslator().getXYPosition(posIndex).getCenter();
            event.xPosition_ = xyPos.x;
            event.yPosition_ = xyPos.y;
         }
         if (json.has("x")) {
            event.xPosition_ = json.getDouble("x");
         }
         if (json.has("y")) {
            event.yPosition_ = json.getDouble("y");
         }

         if (json.has("slm_pattern")) {
            event.slmImage_ = json.get("slm_pattern");
         }

         //TODO: galvo, etc (i.e. other aspects of imperative API)

         if (json.has("keep_shutter_open")) {
            event.keepShutterOpen_ = json.getBoolean("keep_shutter_open");
         }

         //Arbitrary additional properties (i.e. state based API)
         if (json.has("properties")) {
            JSONArray propList = json.getJSONArray("properties");
            for (int i = 0; i < propList.length(); i++) {
               JSONArray trip = propList.getJSONArray(i);
               ThreeTuple t = new ThreeTuple(trip.getString(0), trip.getString(1), trip.getString(2));
               event.properties_.add(t);
            }
         }

         return event;
      } catch (JSONException ex) {
         throw new RuntimeException(ex);
      }
   }

   /**
    * Return JSONArray or JSONObject for sequence vs single event
    * @return
    */
   public JSONObject toJSON() {
      try {
         if (sequence_ != null) {
            JSONArray array = new JSONArray();
            for (AcquisitionEvent e : sequence_) {
               array.put(eventToJSON(e));
            }
            JSONObject json = new JSONObject();
            json.put("events", array);
            return json;
         } else {
            return eventToJSON(this);
         }
      } catch (JSONException e) {
         throw new RuntimeException(e);
      }
   }

   public static AcquisitionEvent fromJSON(JSONObject json, AcquisitionAPI acq)  {
      try {
         if (!json.has("events")) {
            return eventFromJSON((JSONObject) json, acq);
         } else {
            ArrayList<AcquisitionEvent> sequence = new ArrayList<AcquisitionEvent>();
            JSONArray arr = (JSONArray) json.getJSONArray("events");
            for (int i = 0; i < arr.length(); i++) {
               sequence.add(eventFromJSON(arr.getJSONObject(i), acq));
            }
            return new AcquisitionEvent(sequence);
         }
      } catch (JSONException e) {
         throw new RuntimeException(e);
      }
   }

   public List<String[]> getAdditonalProperties() {
      ArrayList<String[]> list = new ArrayList<String[]>();
      for (ThreeTuple t : properties_) {
         list.add(new String[]{t.dev, t.prop, t.val});
      }
      return list;
   }

   public boolean shouldAcquireImage() {
      if (sequence_ != null) {
         return true;
      } else if (gridRow_ != null && gridCol_ != null) {
         return true;
      } else {
         return channelConfig_ != null || axisPositions_.keySet().size() > 0;
      }
   }

   public boolean hasChannel() {
      return channelConfig_ != null && channelGroup_ != null;
   }

   public String getChannelConfig() {
      return channelConfig_;
   }

   public String getChannelGroup() {
      return channelGroup_;
   }

   public void setChannelConfig(String config) {
      channelConfig_ = config;
   }

   public void setChannelGroup(String group) {
      channelGroup_ = group;
   }

   public Double getExposure() {
      return exposure_;
   }

   public void setExposure(double exposure) {
      exposure_ = exposure;
   }

   /**
    * Set the minimum start time in ms relative to when the acq started
    *
    * @param l
    */
   public void setMinimumStartTime(Long l) {
      miniumumStartTime_ms_ = l;
   }

   public Boolean shouldKeepShutterOpen() {
      return keepShutterOpen_;
   }

   public Set<String> getDefinedAxes() {
      return axisPositions_.keySet();
   }

   public void setAxisPosition(String label, int index) {
      axisPositions_.put(label, index);
   }

   public Integer getAxisPosition(String label) {
      if (!axisPositions_.containsKey(label)) {
         return null;
      }
      return axisPositions_.get(label);
   }

   public void setTimeIndex(int index) {
      setAxisPosition(AcqEngMetadata.TIME_AXIS, index);
   }

   public Object getSLMImage() {
      return slmImage_;
   }

   public void setZ(Integer index, Double position) {
      if (index != null) {
         setAxisPosition(AcqEngMetadata.Z_AXIS, index);
      }
      zPosition_ = position;
   }

   public Integer getTIndex() {
      return getAxisPosition(AcqEngMetadata.TIME_AXIS);
   }

   public Integer getZIndex() {
      return getAxisPosition(AcqEngMetadata.Z_AXIS);
   }

   public static AcquisitionEvent createAcquisitionFinishedEvent(AcquisitionAPI acq) {
      AcquisitionEvent evt = new AcquisitionEvent(acq);
      evt.specialFlag_ = SpecialFlag.AcqusitionFinished;
      return evt;
   }

   public boolean isAcquisitionFinishedEvent() {
      return specialFlag_ == SpecialFlag.AcqusitionFinished;
   }

   public static AcquisitionEvent createAcquisitionSequenceEndEvent(AcquisitionAPI acq) {
      AcquisitionEvent evt = new AcquisitionEvent(acq);
      evt.specialFlag_ = SpecialFlag.AcqusitionSequenceEnd;
      return evt;
   }

   public boolean isAcquisitionSequenceEndEvent() {
      return specialFlag_ == SpecialFlag.AcqusitionSequenceEnd;
   }

   public Double getZPosition() {
      return zPosition_;
   }

   /**
    * get the minimum start timein system time
    *
    * @return
    */
   public Long getMinimumStartTimeAbsolute() {
      if (miniumumStartTime_ms_ == null) {
         return null;
      }
      return acquisition_.getStartTime_ms() + miniumumStartTime_ms_;
   }

   public List<AcquisitionEvent> getSequence() {
      return sequence_;
   }

   public boolean isExposureSequenced() {
      return exposureSequenced_;
   }

   public boolean isChannelSequenced() {
      return channelSequenced_;
   }

   public boolean isXYSequenced() {
      return xySequenced_;
   }

   public boolean isZSequenced() {
      return zSequenced_;
   }

   /**
    * Get the stage coordinates of the corners of the camera field of view
    * @return
    */
   public Point2D.Double[] getDisplayPositionCorners() {
      if (xPosition_ == null || yPosition_ == null) {
         throw new RuntimeException("xy position undefined");
      }
      int width = (int) Engine.getCore().getImageWidth();
      int height = (int) Engine.getCore().getImageHeight();
      Integer overlapX = AcqEngMetadata.getPixelOverlapX(acquisition_.getSummaryMetadata());
      Integer overlapY = AcqEngMetadata.getPixelOverlapY(acquisition_.getSummaryMetadata());
      int displayTileWidth = width - (overlapX != null ? overlapX : 0);
      int displayTileHeight = height - (overlapY != null ? overlapY : 0);
      Point2D.Double[] displayedTileCorners = new Point2D.Double[4];
      displayedTileCorners[0] = new Point2D.Double();
      displayedTileCorners[1] = new Point2D.Double();
      displayedTileCorners[2] = new Point2D.Double();
      displayedTileCorners[3] = new Point2D.Double();
      //this AT is centered at the stage position, becuase there no global translation relevant to a single stage position
      AffineTransform transform = AffineTransformUtils.getAffineTransform(
              xPosition_, yPosition_);
      transform.transform(new Point2D.Double(-displayTileWidth / 2, -displayTileHeight / 2), displayedTileCorners[0]);
      transform.transform(new Point2D.Double(-displayTileWidth / 2, displayTileHeight / 2), displayedTileCorners[1]);
      transform.transform(new Point2D.Double(displayTileWidth / 2, displayTileHeight / 2), displayedTileCorners[2]);
      transform.transform(new Point2D.Double(displayTileWidth / 2, -displayTileHeight / 2), displayedTileCorners[3]);
      return displayedTileCorners;
   }

   public Double getXPosition() {
      return xPosition_;
   }

   public Double getYPosition() {
      return yPosition_;
   }

   public Integer getGridRow() {
      return gridRow_;
   }

   public Integer getGridCol() {
      return gridCol_;
   }

   public void setX(double x) {
      xPosition_ = x;
   }

   public void setY(double y) {
      yPosition_ = y;
   }

   public void setGridRow(Integer gridRow) {
      gridRow_ = gridRow;
   }

   public void setGridCol(Integer gridCol) {
      gridCol_ = gridCol;
   }

   //For debugging
   @Override
   public String toString() {
      if (specialFlag_ == SpecialFlag.AcqusitionFinished) {
         return "Acq finished event";
      } else if (specialFlag_ == SpecialFlag.AcqusitionSequenceEnd) {
         return "Acq sequence end event";
      }

      StringBuilder builder = new StringBuilder();
      if (zPosition_ != null) {
         builder.append("z " + zPosition_);
      }
      if (xPosition_ != null) {
         builder.append("x " + xPosition_);
      }
      if (yPosition_ != null) {
         builder.append("y  " + yPosition_);
      }
      if (gridRow_ != null) {
         builder.append("row  " + gridRow_);
      }
      if (gridCol_ != null) {
         builder.append("col   " + gridCol_);
      }

      for (String axis : axisPositions_.keySet()) {
         builder.append("\t" + axis + ": " + axisPositions_.get(axis));
      }

      return builder.toString();
   }

}

class ThreeTuple implements Comparable<ThreeTuple> {

   final String dev, prop, val;

   public ThreeTuple(String d, String p, String v) {
      dev = d;
      prop = p;
      val = v;
   }

   public String[] toArray() {
      return new String[]{dev, prop, val};
   }

   @Override
   public int compareTo(ThreeTuple t) {
      if (!dev.equals(t.dev)) {
         return dev.compareTo(dev);
      } else {
         return prop.compareTo(prop);
      }
   }

}
