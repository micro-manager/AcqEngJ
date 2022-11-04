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

import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.*;
import org.micromanager.acqj.internal.Engine;
import org.micromanager.acqj.util.xytiling.PixelStageTranslator;

import java.util.Iterator;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Special type of acquisiton that collects tiles in a 2D grid.
 * Used by Micro-Magellan and Pycro-Manager
 */
public class XYTiledAcquisition extends Acquisition implements XYTiledAcquisitionAPI {

   private PixelStageTranslator pixelStageTranslator_;

   private Integer overlapX_, overlapY_;

   public XYTiledAcquisition(DataSink sink, Integer overlapX, Integer overlapY) {
      this(sink, overlapX, overlapY, null);
   }

   public XYTiledAcquisition(DataSink sink, Integer overlapX, Integer overlapY,
                             Consumer<JSONObject> summaryMDAdder) {
      super(sink, new Consumer<JSONObject>() {
         @Override
         public void accept(JSONObject smd) {
            if (summaryMDAdder != null) {
               summaryMDAdder.accept(smd);
            }
            AcqEngMetadata.setPixelOverlapX(smd, overlapX);
            AcqEngMetadata.setPixelOverlapY(smd, overlapY);
            if (AcqEngMetadata.getAffineTransformString(smd).equals("Undefined")) {
               throw new RuntimeException("Cannot run acquisition with XY tiling without first defining" +
                       "affine transform between camera and stage. Check pixel size calibration");
            }

         }
      });
      overlapX_ = overlapX;
      overlapY_ = overlapY;
      xyStage_ = core_.getXYStageDevice();
      pixelStageTranslator_ = new PixelStageTranslator(AcqEngMetadata.getAffineTransform(getSummaryMetadata()), xyStage_,
              (int) Engine.getCore().getImageWidth(), (int) Engine.getCore().getImageHeight(), overlapX_, overlapY_);
   }

   public PixelStageTranslator getPixelStageTranslator() {
      return pixelStageTranslator_;
   }

}
