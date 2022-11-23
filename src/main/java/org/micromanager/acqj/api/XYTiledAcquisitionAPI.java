/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.acqj.api;

import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.main.AcquisitionEvent;
import org.micromanager.acqj.util.xytiling.PixelStageTranslator;

import java.util.Iterator;
import java.util.concurrent.Future;

/**
 * General interface for tiled XY multi-resolution acquisitions
 *
 * @author henrypinkard
 */
public interface XYTiledAcquisitionAPI extends AcquisitionAPI {

   public PixelStageTranslator getPixelStageTranslator();

   }
