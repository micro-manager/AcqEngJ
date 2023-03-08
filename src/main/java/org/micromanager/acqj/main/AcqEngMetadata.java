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

import java.awt.geom.AffineTransform;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;

import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.AcquisitionAPI;
import org.micromanager.acqj.internal.Engine;
import org.micromanager.acqj.internal.AffineTransformUtils;

/**
 * Convenience/standardization for Acq Engine metadata
 */
public class AcqEngMetadata {

   private static final String CHANNEL_GROUP = "ChannelGroup";
   private static final String CORE_AUTOFOCUS_DEVICE = "Core-Autofocus";
   private static final String CORE_CAMERA = "Core-Camera";
   private static final String CORE_GALVO = "Core-Galvo";
   private static final String CORE_IMAGE_PROCESSOR = "Core-ImageProcessor";
   private static final String CORE_SLM = "Core-SLM";
   private static final String CORE_SHUTTER = "Core-Shutter";
   private static final String WIDTH = "Width";
   private static final String HEIGHT = "Height";
   private static final String PIX_SIZE = "PixelSize_um";
   private static final String POS_NAME = "PositionName";
   private static final String X_UM_INTENDED = "XPosition_um_Intended";
   private static final String Y_UM_INTENDED = "YPosition_um_Intended";
   private static final String Z_UM_INTENDED = "ZPosition_um_Intended";
   private static final String X_UM = "XPosition_um";
   private static final String Y_UM = "YPosition_um";
   private static final String Z_UM = "ZPosition_um";
   private static final String EXPOSURE = "Exposure";
   private static final String CHANNEL_NAME = "Channel";
   private static final String ZC_ORDER = "SlicesFirst"; // this is called ZCT in the functions
   private static final String TIME = "Time";
   private static final String DATE_TIME = "DateAndTime";
   private static final String SAVING_PREFIX = "Prefix";
   private static final String INITIAL_POS_LIST = "InitialPositionList";
   private static final String TIMELAPSE_INTERVAL = "Interval_ms";
   private static final String PIX_TYPE = "PixelType";
   private static final String BIT_DEPTH = "BitDepth";
   private static final String ELAPSED_TIME_MS = "ElapsedTime-ms";
   private static final String Z_STEP_UM = "z-step_um";
   public static final String GRID_COL = "GridColumnIndex";
   public static final String GRID_ROW = "GridRowIndex";
   private static final String EXPLORE_ACQUISITION = "ExploreAcquisition";
   public static final String AXES_GRID_COL = "column";
   public static final String AXES_GRID_ROW = "row";
   private static final String OVERLAP_X = "GridPixelOverlapX";
   private static final String OVERLAP_Y = "GridPixelOverlapY";
   private static final String AFFINE_TRANSFORM = "AffineTransform";
   private static final String PIX_TYPE_GRAY8 = "GRAY8";
   private static final String PIX_TYPE_GRAY16 = "GRAY16";
   private static final String CORE_XYSTAGE = "Core-XYStage";
   private static final String CORE_FOCUS = "Core-Focus";
   private static final String AXES = "Axes";
   
   public static final String CHANNEL_AXIS = "channel";
   public static final String TIME_AXIS = "time";
   public static final String Z_AXIS = "z";
   private static final String ACQUISITION_EVENT = "Event";


   /**
    * Add the core set of image metadata that should be present in any
    * acquisition
    *
    * @param tags image metadata
    * @param event event
    * @param elapsed_ms time since acq start
    * @param exposure camera exposure in ms
    */
   public static void addImageMetadata(JSONObject tags, AcquisitionEvent event,
            long elapsed_ms, double exposure) {
      try {

         AcqEngMetadata.setPixelSizeUm(tags, Engine.getCore().getPixelSizeUm());

         //////////  Date and time   //////////////
         AcqEngMetadata.setElapsedTimeMs(tags, elapsed_ms);
         AcqEngMetadata.setImageTime(tags, (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss -")).format(Calendar.getInstance().getTime()));

         //////// Info about all hardware that the core specifically knows about ///////
         // e.g. Core focus, core XYStage, core Camera etc

         // Theses are too slow to call presumably because they communicate with hardware
         //.setStageX(tags, Engine.getCore().getXPosition());
         //AcqEngMetadata.setStageY(tags, Engine.getCore().getYPosition());
         //AcqEngMetadata.setZPositionUm(tags, Engine.getCore().getPosition());


         //Axes (i.e. channel. z, t, or arbitray other indices)
         AcqEngMetadata.createAxes(tags);

         /////////  XY Stage Positions (with optional support for grid layout) ////////
         if (event.getXPosition() != null && event.getYPosition() != null) {
            //infer Stage position index at acquisition time to support on the fly modification
//            AcqEngMetadata.setPositionIndex(tags, event.acquisition_.getPositionIndexFromName(event.getXY()));
            AcqEngMetadata.setStageXIntended(tags, event.getXPosition());
            AcqEngMetadata.setStageYIntended(tags, event.getYPosition());
            if (event.getGridRow() != null && event.getGridCol() != null) {
               AcqEngMetadata.setAxisPosition(tags, AcqEngMetadata.AXES_GRID_ROW, event.getGridRow());
               AcqEngMetadata.setAxisPosition(tags, AcqEngMetadata.AXES_GRID_COL, event.getGridCol());
               AcqEngMetadata.setGridRow(tags, event.getGridRow());
               AcqEngMetadata.setGridCol(tags, event.getGridCol());
//               TODO: add overlap here?
            }
         }
         if (event.getZPosition() != null) {
            AcqEngMetadata.setStageZIntended(tags, event.getZPosition());
         }

         if (event.getSequence() != null) {
            //Dont add the event to image metadata if it is a sequence, because it could potentially be very large
            // Could probably pop out the individual event in the squence this corresponds to
            AcqEngMetadata.addAcquisitionEvent(tags, event);
         }
         
         ////// Generic image coordinate axes //////
         // Position and channel indices are inferred at acquisition time
         //All other axes (including T and Z) must be explicitly defined in the 
         //acquisition event and get added here
         for (String s : event.getDefinedAxes()) {
            AcqEngMetadata.setAxisPosition(tags, s, event.getAxisPosition(s));
         }

         AcqEngMetadata.setExposure(tags, exposure);

      } catch (Exception e) {
         e.printStackTrace();
         throw new RuntimeException("Problem adding image metadata");
      }
   }

   private static void addAcquisitionEvent(JSONObject tags, AcquisitionEvent event) {
      try {
         tags.put(ACQUISITION_EVENT, event.toJSON());
      } catch (JSONException e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Make the core set of tags needed in summary metadata. Specific types of
    * acquistitions can add to this as needed
    *
    * @param acq acquisition
    * @return Summary metadata
    */
   public static JSONObject makeSummaryMD(AcquisitionAPI acq) {
      JSONObject summary = new JSONObject();

      AcqEngMetadata.setAcqDate(summary, getCurrentDateAndTime());

      //General information the core-camera
      int byteDepth = (int) Engine.getCore().getBytesPerPixel();
      if (byteDepth ==0) {
         throw new RuntimeException("Camera byte depth cannot be zero");
      }
      AcqEngMetadata.setPixelTypeFromByteDepth(summary, byteDepth);
//      AcqEngMetadata.setBitDepth(summary, (int) Engine.getCore().getImageBitDepth());
//      AcqEngMetadata.setWidth(summary, (int) Engine.getCore().getImageWidth());
//      AcqEngMetadata.setHeight(summary, (int) Engine.getCore().getImageHeight());
      AcqEngMetadata.setPixelSizeUm(summary, Engine.getCore().getPixelSizeUm());

      /////// Info about core devices ////////
      try {
         AcqEngMetadata.setCoreXY(summary, Engine.getCore().getXYStageDevice());
         AcqEngMetadata.setCoreFocus(summary, Engine.getCore().getFocusDevice());
         AcqEngMetadata.setCoreAutofocus(summary, Engine.getCore().getAutoFocusDevice());
         AcqEngMetadata.setCoreCamera(summary, Engine.getCore().getCameraDevice());
         AcqEngMetadata.setCoreGalvo(summary, Engine.getCore().getGalvoDevice());
         AcqEngMetadata.setCoreImageProcessor(summary, Engine.getCore().getImageProcessorDevice());
         AcqEngMetadata.setCoreSLM(summary, Engine.getCore().getSLMDevice());
         AcqEngMetadata.setCoreShutter(summary, Engine.getCore().getShutterDevice());
      } catch (Exception e) {
         throw new RuntimeException("couldn't get info from corea about devices");
      }

      //affine transform
      if (AffineTransformUtils.isAffineTransformDefined()) {
         AffineTransform at = AffineTransformUtils.getAffineTransform(0, 0);
         AcqEngMetadata.setAffineTransformString(summary, AffineTransformUtils.transformToString(at));
      } else {
         AcqEngMetadata.setAffineTransformString(summary, "Undefined");
      }

      return summary;
   }

   protected static String getCurrentDateAndTime() {
      DateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      Calendar calobj = Calendar.getInstance();
      return df.format(calobj.getTime());
   }

   public static int[] getIndices(String imageLabel) {
      int[] ind = new int[4];
      String[] s = imageLabel.split("_");
      for (int i = 0; i < 4; i++) {
         ind[i] = Integer.parseInt(s[i]);
      }
      return ind;
   }

   public static JSONObject copy(JSONObject map) {
      try {
         return new JSONObject(map.toString());
      } catch (JSONException e) {
         return null;
      }
   }

   public static void setCoreXY(JSONObject map, String xyName) {
      try {
         map.put(CORE_XYSTAGE, xyName);
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt set core xy");
      }
   }

   public static boolean hasCoreXY(JSONObject map) {
      return map.has(CORE_XYSTAGE);
   }

   public static String getCoreXY(JSONObject map) {
      try {
         return map.getString(CORE_XYSTAGE);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing core xy stage tag");
      }
   }

   public static void setCoreFocus(JSONObject map, String zName) {
      try {
         map.put(CORE_FOCUS, zName);
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt set core focus tag");
      }
   }

   public static boolean hasCoreFocus(JSONObject map) {
      return map.has(CORE_FOCUS);
   }

   public static String getCoreFocus(JSONObject map) {
      try {
         return map.getString(CORE_FOCUS);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing core focus tag");
      }
   }

   public static void setAcqDate(JSONObject map, String dateTime) {
      try {
         map.put(DATE_TIME, dateTime);
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt set core focus tag");
      }
   }

   public static boolean isExploreAcq(JSONObject summaryMetadata_) {
      try {
         return summaryMetadata_.getBoolean(EXPLORE_ACQUISITION);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing expolore tag");
      }
   }

   public static void setExploreAcq(JSONObject summaryMetadata, boolean b) {
      try {
         summaryMetadata.put(EXPLORE_ACQUISITION, b);
      } catch (JSONException e) {
         throw new RuntimeException(e);
      }
   }

   public boolean hasAcqDate(JSONObject map) {
      return map.has(DATE_TIME);
   }

   public static String getAcqDate(JSONObject map) {
      try {
         return map.getString(DATE_TIME);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing Acq dat time tag");
      }
   }

   public static void setBitDepth(JSONObject map, int bitDepth) {
      try {
         map.put(BIT_DEPTH, bitDepth);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set bit depth");
      }
   }

   public static boolean hasBitDepth(JSONObject map) {
      return map.has(BIT_DEPTH);
   }

   public static int getBitDepth(JSONObject map) {
      try {
         return map.getInt(BIT_DEPTH);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing bit depth tag");
      }
   }

   public static void setWidth(JSONObject map, int width) {
      try {
         map.put(WIDTH, width);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn set image width");
      }
   }

   public static boolean hasWidth(JSONObject map) {
      return map.has(WIDTH);
   }

   public static int getWidth(JSONObject map) {
      try {
         return map.getInt(WIDTH);
      } catch (JSONException ex) {
         throw new RuntimeException("Image width tag missing");
      }
   }

   public static void setHeight(JSONObject map, int height) {
      try {
         map.put(HEIGHT, height);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldnt set image height");
      }
   }

   public static boolean hasHeight(JSONObject map) {
      return map.has(HEIGHT);
   }

   public static int getHeight(JSONObject map) {
      try {
         return map.getInt(HEIGHT);
      } catch (JSONException ex) {
         throw new RuntimeException("Height missing from image tags");
      }
   }

   public static void setPositionName(JSONObject map, String positionName) {
      try {
         map.put(POS_NAME, positionName);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set position name");
      }
   }

   public static boolean hasPositionName(JSONObject map) {
      return map.has(POS_NAME);
   }

   public static String getPositionName(JSONObject map) {
      try {
         return map.getString(POS_NAME);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing position name tag");
      }
   }

   public static void setPixelTypeFromString(JSONObject map, String type) {
      try {
         map.put(PIX_TYPE, type);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set pixel type");

      }
   }

   public static void setPixelTypeFromByteDepth(JSONObject map, int depth) {
      try {
         switch (depth) {
            case 1:
               map.put(PIX_TYPE, PIX_TYPE_GRAY8);
               break;
            case 2:
               map.put(PIX_TYPE, PIX_TYPE_GRAY16);
               break;
            case 4:
               map.put(PIX_TYPE, "RGB32");
               break;
//         case 8:
//            map.put(PIX_TYPE, "RGB64");
//         break;
         }
      } catch (JSONException e) {
         throw new RuntimeException("Couldn't set pixel type");

      }
   }

   public static boolean hasPixelType(JSONObject map) {
      return map.has(PIX_TYPE);
   }

   public static String getPixelType(JSONObject map) {
      try {
         if (map != null) {
            return map.getString(PIX_TYPE);
         }
      } catch (JSONException e) {
         throw new RuntimeException(e);
      }
      return "";
   }

   public static int getBytesPerPixel(JSONObject map) {
      if (isGRAY8(map)) {
         return 1;
      }
      if (isGRAY16(map)) {
         return 2;
      }
//       if (isGRAY32(map)) return 4;
      if (isRGB32(map)) {
         return 4;
      }
//       if (isRGB64(map)) return 8;
      return 0;
   }

   public static int getNumberOfComponents(JSONObject map) {
      String pixelType = getPixelType(map);
      if (pixelType.contentEquals(PIX_TYPE_GRAY8)) {
         return 1;
      } else if (pixelType.contentEquals(PIX_TYPE_GRAY16)) {
         return 1;
      } //      else if (pixelType.contentEquals("GRAY32"))
      //         return 1;
      else if (pixelType.contentEquals("RGB32")) {
         return 3;
      } //      else if (pixelType.contentEquals("RGB64"))
      //           return 3;
      else {
         throw new RuntimeException();
      }
   }

   public static boolean isGRAY8(JSONObject map) {
      return getPixelType(map).contentEquals(PIX_TYPE_GRAY8);
   }

   public static boolean isGRAY16(JSONObject map) {
      return getPixelType(map).contentEquals(PIX_TYPE_GRAY16);
   }

//   public static boolean isGRAY32(JSONObject map)  {
//      return getPixelType(map).contentEquals("GRAY32");
//   }
//
   public static boolean isRGB32(JSONObject map) {
      return getPixelType(map).contentEquals("RGB32");
   }

//   public static boolean isRGB64(JSONObject map)  {
//      return getPixelType(map).contentEquals("RGB64");
//   }
   public static boolean isGRAY(JSONObject map) {
      return (isGRAY8(map) || isGRAY16(map));
   }

   public static boolean isRGB(JSONObject map) {
      return (isRGB32(map));
//              || isRGB64(map));
   }

   public static String[] getKeys(JSONObject md) {
      int n = md.length();
      String[] keyArray = new String[n];
      Iterator<String> keys = md.keys();
      for (int i = 0; i < n; ++i) {
         keyArray[i] = keys.next();
      }
      return keyArray;
   }

   public static JSONArray getJSONArrayMember(JSONObject obj, String key) {
      JSONArray theArray;
      try {
         return obj.getJSONArray(key);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing JSONArray member");

      }
   }

   public static void setImageTime(JSONObject map, String time) {
      try {
         map.put(TIME, time);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set image time");
      }
   }

   public boolean hasImageTime(JSONObject map) {
      return map.has(TIME);
   }

   public static String getImageTime(JSONObject map) {
      try {
         return map.getString(TIME);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing image time tag");

      }
   }

   public static int getDepth(JSONObject tags) {
      String pixelType = getPixelType(tags);
      if (pixelType.contains(PIX_TYPE_GRAY8)) {
         return 1;
      } else if (pixelType.contains(PIX_TYPE_GRAY16)) {
         return 2;
      } //      else if (pixelType.contains(MMTags.Values.PIX_TYPE_RGB_32))
      //         return 4;
      //      else if (pixelType.contains(MMTags.Values.PIX_TYPE_RGB_64))
      //         return 8;
      else {
         return 0;
      }
   }

   public static void setExposure(JSONObject map, double exp) {
      try {
         map.put(EXPOSURE, exp);
      } catch (JSONException ex) {
         throw new RuntimeException("could not set exposure");
      }
   }

   public boolean hasExposure(JSONObject map) {
      return map.has(EXPOSURE);
   }

   public static double getExposure(JSONObject map) {
      try {
         return map.getDouble(EXPOSURE);
      } catch (JSONException ex) {
         throw new RuntimeException("Exposure tag missing");

      }
   }

   public static void setPixelSizeUm(JSONObject map, double val) {
      try {
         map.put(PIX_SIZE, val);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing pixel size tag");
      }
   }

   public static boolean hasPixelSizeUm(JSONObject map) {
      return map.has(PIX_SIZE);
   }

   public static double getPixelSizeUm(JSONObject map) {
      try {
         return map.getDouble(PIX_SIZE);
      } catch (JSONException ex) {
         throw new RuntimeException("Pixel size missing in metadata");

      }
   }

   public static void setZStepUm(JSONObject map, double val) {
      try {
         map.put(Z_STEP_UM, val);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set z step tag");
      }
   }

   public static boolean hasZStepUm(JSONObject map) {
      return map.has(Z_STEP_UM);
   }

   public static double getZStepUm(JSONObject map) {
      try {
         return map.getDouble(Z_STEP_UM);
      } catch (JSONException ex) {
         throw new RuntimeException("Z step metadta field missing");
      }
   }

   public static void setZPositionUm(JSONObject map, double val) {
      try {
         map.put(Z_UM, val);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set z position");
      }
   }

   public static boolean hasZPositionUm(JSONObject map) {
      return map.has(Z_UM);
   }

   public static double getZPositionUm(JSONObject map) {
      try {
         return map.getDouble(Z_UM);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing Z position tag");
      }
   }

   public static void setElapsedTimeMs(JSONObject map, long val) {
      try {
         map.put(ELAPSED_TIME_MS, val);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set elapsed time");
      }
   }

   public static boolean hasElapsedTimeMs(JSONObject map) {
      return map.has(ELAPSED_TIME_MS);
   }

   public static long getElapsedTimeMs(JSONObject map) {
      try {
         return map.getLong(ELAPSED_TIME_MS);
      } catch (JSONException ex) {
         throw new RuntimeException("missing elapsed time tag");
      }
   }

   public static void setIntervalMs(JSONObject map, double val) {
      try {
         map.put(TIMELAPSE_INTERVAL, val);
      } catch (JSONException ex) {
         throw new RuntimeException("couln dt set time interval metadta field");
      }
   }

   public static boolean hasIntervalMs(JSONObject map) {
      return map.has(TIMELAPSE_INTERVAL);
   }

   public static double getIntervalMs(JSONObject map) {
      try {
         return map.getDouble(TIMELAPSE_INTERVAL);
      } catch (JSONException ex) {
         throw new RuntimeException("Time interval missing from summary metadata");
      }
   }

   public static void setZCTOrder(JSONObject map, boolean val) {
      try {
         map.put(ZC_ORDER, val);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set ZCT Order");
      }
   }

   public static boolean hasZCTOrder(JSONObject map) {
      return map.has(ZC_ORDER);
   }

   public static boolean getZCTOrder(JSONObject map) {
      try {
         return map.getBoolean(ZC_ORDER);
      } catch (JSONException ex) {
         throw new RuntimeException("Missing ZCT Tag");
      }
   }

   public static void setAffineTransformString(JSONObject summaryMD, String affine) {
      try {
         summaryMD.put(AFFINE_TRANSFORM, affine);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldn't set affine transform");
      }
   }

   public static boolean hasAffineTransformString(JSONObject map) {
      return map.has(AFFINE_TRANSFORM);
   }

   public static String getAffineTransformString(JSONObject summaryMD) {
      try {
         return summaryMD.getString(AFFINE_TRANSFORM);
      } catch (JSONException ex) {
         throw new RuntimeException("Affine transform missing from summary metadata");
      }
   }

   public static AffineTransform getAffineTransform(JSONObject summaryMD) {
      try {
         return stringToTransform(summaryMD.getString(AFFINE_TRANSFORM));
      } catch (JSONException ex) {
         throw new RuntimeException("Affine transform missing from summary metadata");
      }
   }

   private static AffineTransform stringToTransform(String s) {
      if (s.equals("Undefined")) {
         return null;
      }
      double[] mat = new double[4];
      String[] vals = s.split("_");
      for (int i = 0; i < 4; i++) {
         mat[i] = parseDouble(vals[i]);
      }
      return new AffineTransform(mat);
   }

   private static double parseDouble(String s) {
      try {
         return DecimalFormat.getNumberInstance().parse(s).doubleValue();
      } catch (ParseException ex) {
         throw new RuntimeException(ex);
      }
   }
   
   public static void setPixelOverlapX(JSONObject smd, int overlap) {
      try {
         smd.put(OVERLAP_X, overlap);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set pixel overlap tag");
      }
   }

   public static boolean hasPixelOverlapX(JSONObject map) {
      return map.has(OVERLAP_X);
   }

   public static int getPixelOverlapX(JSONObject summaryMD) {
      try {
         return summaryMD.getInt(OVERLAP_X);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find pixel overlap in image tags");
      }
   }

   public static void setPixelOverlapY(JSONObject smd, int overlap) {
      try {
         smd.put(OVERLAP_Y, overlap);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set pixel overlap tag");
      }
   }

   public static boolean hasPixelOverlapY(JSONObject map) {
      return map.has(OVERLAP_Y);
   }

   public static int getPixelOverlapY(JSONObject summaryMD) {
      try {
         return summaryMD.getInt(OVERLAP_Y);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find pixel overlap in image tags");
      }
   }

   public static void setGridRow(JSONObject smd, long gridRow) {
      try {
         smd.put(GRID_ROW, gridRow);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set grid row");
      }
   }

   public static boolean hasGridRow(JSONObject map) {
      return map.has(GRID_ROW);
   }

   public static int getGridRow(JSONObject smd) {
      try {
         return smd.getInt(GRID_ROW);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get grid row");

      }
   }

   public static void setGridCol(JSONObject smd, long gridCol) {
      try {
         smd.put(GRID_COL, gridCol);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set grid row");
      }
   }

   public static boolean hasGridCol(JSONObject map) {
      return map.has(GRID_COL);
   }

   public static int getGridCol(JSONObject smd) {
      try {
         return smd.getInt(GRID_COL);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get grid row");
      }
   }

   public static void setStageXIntended(JSONObject smd, double x) {
      try {
         smd.put(X_UM_INTENDED, x);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set stage x");
      }
   }

   public static boolean hasStageXIntended(JSONObject map) {
      return map.has(X_UM_INTENDED);
   }

   public static double getStageXIntended(JSONObject smd) {
      try {
         return smd.getDouble(X_UM_INTENDED);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get stage x");
      }
   }

   public static void setStageYIntended(JSONObject smd, double y) {
      try {
         smd.put(Y_UM_INTENDED, y);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set stage y");
      }
   }

   public static boolean hasStageYIntended(JSONObject map) {
      return map.has(Y_UM_INTENDED);
   }

   public static double getStageYIntended(JSONObject smd) {
      try {
         return smd.getDouble(Y_UM_INTENDED);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get stage y");
      }
   }

   public static void setStageZIntended(JSONObject smd, double y) {
      try {
         smd.put(Z_UM_INTENDED, y);
      } catch (JSONException ex) {
         throw new RuntimeException("Couldnt set stage y");
      }
   }

   public static boolean hasStageZIntended(JSONObject map) {
      return map.has(Z_UM_INTENDED);
   }

   public static double getStageZIntended(JSONObject smd) {
      try {
         return smd.getDouble(Z_UM_INTENDED);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get stage Z");
      }
   }

   public static void setStageX(JSONObject smd, double x) {
      try {
         smd.put(X_UM, x);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set stage x");
      }
   }

   public static boolean hasStageX(JSONObject map) {
      return map.has(X_UM);
   }

   public static double getStageX(JSONObject smd) {
      try {
         return smd.getDouble(X_UM);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get stage x");
      }
   }

   public static void setStageY(JSONObject smd, double y) {
      try {
         smd.put(Y_UM, y);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set stage y");
      }
   }

   public static boolean hasStageY(JSONObject map) {
      return map.has(Y_UM);
   }

   public static double getStageY(JSONObject smd) {
      try {
         return smd.getDouble(Y_UM);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get stage y");
      }
   }

   public static void setChannelGroup(JSONObject summary, String channelGroup) {
      try {
         summary.put(CHANNEL_GROUP, channelGroup);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set channel group");
      }
   }

   public static boolean hasChannelGroup(JSONObject map) {
      return map.has(CHANNEL_GROUP);
   }

   public static String getChannelGroup(JSONObject summary) {
      try {
         return summary.getString(CHANNEL_GROUP);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find CHannel Group");
      }
   }

   public static void setCoreAutofocus(JSONObject summary, String autoFocusDevice) {
      try {
         summary.put(CORE_AUTOFOCUS_DEVICE, autoFocusDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set autofocus device");
      }
   }

   public static boolean hasCoreAutofocus(JSONObject summary) {
      return summary.has(CORE_AUTOFOCUS_DEVICE);
   }

   public static String getCoreAutofocusDevice(JSONObject summary) {
      try {
         return summary.getString(CORE_AUTOFOCUS_DEVICE);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find autofocus device");
      }
   }

   public static void setCoreCamera(JSONObject summary, String cameraDevice) {
      try {
         summary.put(CORE_CAMERA, cameraDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set core camera");
      }
   }

   public static boolean hasCoreCamera(JSONObject summary) {
      return summary.has(CORE_CAMERA);
   }

   public static String getCoreCamera(JSONObject summary) {
      try {
         return summary.getString(CORE_CAMERA);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get core camera");
      }
   }

   public static void setCoreGalvo(JSONObject summary, String galvoDevice) {
      try {
         summary.put(CORE_GALVO, galvoDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set core galvo");
      }
   }

   public static boolean hasCoreGalvo(JSONObject summary) {
      return summary.has(CORE_GALVO);
   }

   public static String getCoreGalvo(JSONObject summary) {
      try {
         return summary.getString(CORE_GALVO);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not get core galve");
      }
   }

   public static void setCoreImageProcessor(JSONObject summary, String imageProcessorDevice) {
      try {
         summary.put(CORE_IMAGE_PROCESSOR, imageProcessorDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set core image processor");
      }
   }

   public static boolean hasCoreImageProcessor(JSONObject summary) {
      return summary.has(CORE_IMAGE_PROCESSOR);
   }

   public static String getCoreImageProcessor(JSONObject summary) {
      try {
         return summary.getString(CORE_IMAGE_PROCESSOR);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find core image processor");
      }
   }

   public static void setCoreSLM(JSONObject summary, String slmDevice) {
      try {
         summary.put(CORE_SLM, slmDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set core slm");
      }
   }

   public static boolean hasCoreSLM(JSONObject summary) {
      return summary.has(CORE_SLM);
   }

   public static String getCoreSLM(JSONObject summary) {
      try {
         return summary.getString(CORE_SLM);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find core slm");
      }
   }

   public static void setCoreShutter(JSONObject summary, String shutterDevice) {
      try {
         summary.put(CORE_SHUTTER, shutterDevice);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not set core shutter");
      }
   }

   public static boolean hasCoreShutter(JSONObject summary) {
      return summary.has(CORE_SHUTTER);
   }

   public static String getCoreShutter(JSONObject summary) {
      try {
         return summary.getString(CORE_SHUTTER);
      } catch (JSONException ex) {
         throw new RuntimeException("Could not find core shutter");
      }
   }

   public static void createAxes(JSONObject tags) {
      try {
         tags.put(AXES, new JSONObject());
      } catch (JSONException ex) {
         throw new RuntimeException("Could not create axes");
      }
   }
   
    public static HashMap<String, Object> getAxes(JSONObject tags) {
      try {
         JSONObject axes = tags.getJSONObject(AXES);
         Iterator<String> iter = axes.keys();
         HashMap<String, Object> axesMap = new HashMap<String, Object>();
         while (iter.hasNext()) {
            String key = iter.next();
            axesMap.put(key, axes.get(key));
         }
         return axesMap;
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt create axes");
      }
   }

   public static void setAxisPosition(JSONObject tags, String axis, Object position) {
      if (!(position instanceof String || position instanceof Integer)) {
         throw new RuntimeException("position must be String or Integer");
      }
      try {
         tags.getJSONObject(AXES).put(axis, position);
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt create axes");
      }
   }

   public static boolean hasAxis(JSONObject tags, String axis) {
      try {
         return tags.getJSONObject(AXES).has(axis);
      } catch (JSONException ex) {
         throw new RuntimeException("Axes not present in metadata");
      }
   }

   public static Object getAxisPosition(JSONObject tags, String axis) {
      try {
         return tags.getJSONObject(AXES).get(axis);
      } catch (JSONException ex) {
         throw new RuntimeException("couldnt create axes");
      }
   }
}
