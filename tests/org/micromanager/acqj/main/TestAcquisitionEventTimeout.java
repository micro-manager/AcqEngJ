package org.micromanager.acqj.main;

import org.junit.Assert;
import org.junit.Test;
import org.micromanager.acqj.api.AcquisitionAPI;

/**
 * Tests for the AcquisitionEvent timeout.
 */
public class TestAcquisitionEventTimeout {

   private static final Double TIMEOUT_MS = 5000.0;

   @Test
   public void testTimeoutDefaultsToNull() {
      AcquisitionEvent event = new AcquisitionEvent((AcquisitionAPI) null);
      Assert.assertNull(event.getTimeoutMs());
   }

   @Test
   public void testTimeoutSetterAndJSONRoundTrip() {
      AcquisitionEvent event = new AcquisitionEvent((AcquisitionAPI) null);
      event.setTimeoutMs(TIMEOUT_MS);
      Assert.assertEquals(TIMEOUT_MS, event.getTimeoutMs());

      // Python callers set the timeout through this key
      Assert.assertEquals(TIMEOUT_MS, event.toJSON().opt("timeout"));
      AcquisitionEvent deserialized = AcquisitionEvent.fromJSON(event.toJSON(), null);
      Assert.assertEquals(TIMEOUT_MS, deserialized.getTimeoutMs());
   }

   @Test
   public void testNullClearsTimeout() {
      AcquisitionEvent event = new AcquisitionEvent((AcquisitionAPI) null);
      event.setTimeoutMs(TIMEOUT_MS);
      event.setTimeoutMs(null);
      Assert.assertNull(event.getTimeoutMs());
   }

   @Test
   public void testNullTimeoutRoundTripsThroughJSON() {
      AcquisitionEvent event = new AcquisitionEvent((AcquisitionAPI) null);
      Assert.assertFalse(event.toJSON().has("timeout"));
      Assert.assertNull(AcquisitionEvent.fromJSON(event.toJSON(), null).getTimeoutMs());
   }

   @Test
   public void testTimeoutSurvivesCopy() {
      AcquisitionEvent event = new AcquisitionEvent((AcquisitionAPI) null);
      event.setTimeoutMs(TIMEOUT_MS);
      Assert.assertEquals(TIMEOUT_MS, event.copy().getTimeoutMs());
   }
}
