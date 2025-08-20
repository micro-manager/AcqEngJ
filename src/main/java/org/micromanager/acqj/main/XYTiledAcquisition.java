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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import mmcorej.DeviceType;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.AcqEngJDataSink;
import org.micromanager.acqj.api.XYTiledAcquisitionAPI;
import org.micromanager.acqj.internal.Engine;
import org.micromanager.acqj.internal.ZAxis;
import org.micromanager.acqj.util.xytiling.CameraTilingStageTranslator;

/**
 * Special type of acquisiton that collects tiles in a 2D grid.
 * Used by Micro-Magellan and Pycro-Manager
 */
public class XYTiledAcquisition extends Acquisition implements XYTiledAcquisitionAPI {

   protected CameraTilingStageTranslator pixelStageTranslator_;

   private Integer overlapX_;
   private Integer overlapY_;
   Consumer<JSONObject> summaryMDAdder_;

   protected HashMap<String, ZAxis> zAxes_ = new HashMap<String, ZAxis>();

   public XYTiledAcquisition(AcqEngJDataSink sink, Integer overlapX, Integer overlapY) {
      this(sink, overlapX, overlapY, 1., null);
   }

   public XYTiledAcquisition(AcqEngJDataSink sink, Integer overlapX, Integer overlapY, Double zStep,
                             Consumer<JSONObject> summaryMDAdder) {
      super(sink, false);
      overlapX_ = overlapX;
      overlapY_ = overlapY;
      summaryMDAdder_ = summaryMDAdder;

      createZDeviceModel(zStep);
      xyStage_ = core_.getXYStageDevice();

      initialize();
   }

   private void createZDeviceModel(Double zStep) {
      for (String zDeviceName : core_.getLoadedDevicesOfType(DeviceType.StageDevice)) {
         // Could uncomment if want to add support for setting z device origins
         // double zDeviceOrigin = zDeviceOrigins == null && zDeviceOrigins.containsKey(zDeviceName)
         // ? Engine.getCore().getPosition(zDeviceName) : zDeviceOrigins.get(zDeviceName);
         double currentZPos;
         try {
            currentZPos = Engine.getCore().getPosition(zDeviceName);
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
         zAxes_.put(zDeviceName, new ZAxis(zDeviceName, currentZPos,
                 zStep, 0, 0, 0, 0));
      }
   }

   @Override
   public void initialize() {

      // Create default summary metadata and add to it
      JSONObject summaryMetadata = AcqEngMetadata.makeSummaryMD(this);
      if (summaryMDAdder_ != null) {
         summaryMDAdder_.accept(summaryMetadata);
      }
      AcqEngMetadata.setPixelOverlapX(summaryMetadata, overlapX_);
      AcqEngMetadata.setPixelOverlapY(summaryMetadata, overlapY_);
      if (AcqEngMetadata.getAffineTransformString(summaryMetadata).equals("Undefined")) {
         throw new RuntimeException("Cannot run acquisition with XY tiling without first defining"
               + "affine transform between camera and stage. Check pixel size calibration");
      }

      try {
         // Make a local in copy in case something else modifies it
         summaryMetadata_ = new JSONObject(summaryMetadata.toString());
      } catch (JSONException ex) {
         System.err.print("Couldn't copy summaary metadata");
         ex.printStackTrace();
      }

      pixelStageTranslator_ = new CameraTilingStageTranslator(AcqEngMetadata.getAffineTransform(
            getSummaryMetadata()),
            xyStage_,
            (int) Engine.getCore().getImageWidth(),
            (int) Engine.getCore().getImageHeight(),
            overlapX_,
            overlapY_);

      if (dataSink_ != null) {
         //It could be null if not using saving and viewing and diverting with custom processor
         dataSink_.initialize(this, summaryMetadata);
      }
   }

   public HashMap<String, ZAxis> getZAxes() {
      return zAxes_;
   }

   public CameraTilingStageTranslator getPixelStageTranslator() {
      return pixelStageTranslator_;
   }

   public double getZStep(String name) {
      return zAxes_.get(name).zStepUm_;
   }

   public double getZOrigin(String name) {
      return zAxes_.get(name).zOriginUm_;
   }

   public List<String> getZDeviceNames() {
      return zAxes_.keySet().stream().collect(Collectors.toList());
   }



}
