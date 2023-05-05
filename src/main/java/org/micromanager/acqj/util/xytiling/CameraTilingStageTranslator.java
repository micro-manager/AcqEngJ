/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.acqj.util.xytiling;

import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import org.micromanager.acqj.internal.Engine;

/**
 * Convenience class for using an Affine transform to translate between pixel coordinates and stage coordinates
 * when doing XY tiled imaging
 *
 * @author henrypinkard
 */
public class CameraTilingStageTranslator {

   private static final String COORDINATES_KEY = "DeviceCoordinatesUm";
   
   private AffineTransform affine_;
   private String xyStageName_;
   private static Point2D.Double tile00StageCoords_ = null;
   private int tileWidth_, tileHeight_, displayTileHeight_, displayTileWidth_, overlapX_, overlapY_;

   public CameraTilingStageTranslator(AffineTransform transform, String xyStageName, int width,
                                      int height, int overlapX, int overlapY) {
      affine_ = transform;
      xyStageName_ = xyStageName;
      tileWidth_ = width;
      tileHeight_ = height;
      overlapX_ = overlapX;
      overlapY_ = overlapY;
      displayTileWidth_ = tileWidth_ - overlapX_;
      displayTileHeight_ = tileHeight_ - overlapY_;
      overlapX_ = overlapX;
      overlapY_ = overlapY;
      if (tile00StageCoords_ == null) {
         try {
            tile00StageCoords_ = new Point2D.Double(Engine.getCore().getXPosition(), Engine.getCore().getYPosition());
         } catch (Exception e) {
            throw  new RuntimeException(e);
         }
      }
   }

   public Point getTileIndicesFromDisplayedPixel(double magnification, int x, int y,
                                                 double viewOffsetX, double viewOffsetY,
                                                 int rowOffset, int colOffset) {
      int fullResX = (int) ((x / magnification) + viewOffsetX);
      int fullResY = (int) ((y / magnification) + viewOffsetY);
      int xTileIndex = fullResX / getDisplayTileWidth() - (fullResX >= 0 ? 0 : 1);
      int yTileIndex = fullResY / getDisplayTileHeight() - (fullResY >= 0 ? 0 : 1);
      return new Point(xTileIndex, yTileIndex);
   }


   /**
    * return the pixel location in coordinates at appropriate res level of the
    * top left pixel for the given row/column
    *
    * @param row
    * @param col
    * @return
    */
   public Point getDisplayedPixel(double magnification, long row, long col,
                                  double viewOffsetX, double viewOffsetY) {
//      double scale = display_.getMagnification();

      int x = (int) ((col * getDisplayTileWidth() - viewOffsetX) * magnification);
      int y = (int) ((row * getDisplayTileHeight() - viewOffsetY) * magnification);
      return new Point(x, y);
   }

   /**
    *
    * @param absoluteX x coordinate in the full Res stitched image
    * @param absoluteY y coordinate in the full res stitched image
    * @return stage coordinates of the given pixel position
    */
   public Point2D.Double getStageCoordsFromPixelCoords(int absoluteX, int absoluteY,
                                                       double mag, Point2D.Double offset) {
      long newX = (long) (absoluteX / mag + offset.x);
      long newY = (long) (absoluteY / mag + offset.y);
      return getStageCoordsFromPixelCoords(newX, newY);
   }


   /*
    * @param stageCoords x and y coordinates of image in stage space
    * @return absolute, full resolution pixel coordinate of given stage posiiton
    */
   public Point getPixelCoordsFromStageCoords(double x, double y, double magnification,
                                              Point2D.Double offset) {
      Point fullResCoords = getPixelCoordsFromStageCoords(x, y);
      return new Point(
              (int) ((fullResCoords.x - offset.x) * magnification),
              (int) ((fullResCoords.y - offset.y) * magnification));
   }

   /**
    *
    * @param xAbsolute x coordinate in the full Res stitched image
    * @param yAbsolute y coordinate in the full res stitched image
    * @return stage coordinates of the given pixel position
    */
   public synchronized Point2D.Double getStageCoordsFromPixelCoords(long xAbsolute, long yAbsolute) {
      double existingX = tile00StageCoords_.x;
      double existingY = tile00StageCoords_.y;
      double existingRow = 0;
      double existingColumn = 0;
      //get pixel displacement from center of the tile we have coordinates for
      long dxPix = (long) (xAbsolute - (existingColumn + 0.5) * displayTileWidth_);
      long dyPix = (long) (yAbsolute - (existingRow + 0.5) * displayTileHeight_);

      Point2D.Double stagePos = new Point2D.Double();
      double[] mat = new double[4];
      affine_.getMatrix(mat);
      AffineTransform transform = new AffineTransform(mat[0], mat[1], mat[2], mat[3], existingX, existingY);
      transform.transform(new Point2D.Double(dxPix, dyPix), stagePos);
      return stagePos;
   }

   /**
    *
    * @return absolute, full resolution pixel coordinate of given stage posiiton
    */
   public synchronized Point getPixelCoordsFromStageCoords(double stageX, double stageY) {
      try {
         double existingX = tile00StageCoords_.x;
         double existingY = tile00StageCoords_.y;
         double existingRow = 0;
         double existingColumn = 0;

         //get stage displacement from center of the tile we have coordinates for
         double dx = stageX - existingX;
         double dy = stageY - existingY;
         AffineTransform transform = (AffineTransform) affine_.clone();
         Point2D.Double pixelOffset = new Point2D.Double(); // offset in number of pixels from the center of this tile
         transform.inverseTransform(new Point2D.Double(dx, dy), pixelOffset);
         //Add pixel offset to pixel center of this tile to get absolute pixel position
         int xPixel = (int) ((existingColumn + 0.5) * displayTileWidth_ + pixelOffset.x);
         int yPixel = (int) ((existingRow + 0.5) * displayTileHeight_ + pixelOffset.y);
         return new Point(xPixel, yPixel);
      } catch (NoninvertibleTransformException e) {
         throw new RuntimeException("Problem using affine transform to convert stage coordinates to pixel coordinates");
      }
   }

   /**
    * Get the row and col indices of the tile closest to this stage position
    */
   public Point getTileRowColClosestToStageCoords(double x, double y) {
      Point pixelCoords = getPixelCoordsFromStageCoords(x, y);
      long rowIndex = Math.round((double) (pixelCoords.y - displayTileHeight_ / 2) / (double) displayTileHeight_);
      long colIndex = Math.round((double) (pixelCoords.x - displayTileWidth_ / 2) / (double) displayTileWidth_);
      return new Point((int) colIndex, (int) rowIndex );
   }
   
   /**
    * Calculate the stage coordinates of the center of an image at the given row col
    *
    * @param row
    * @param col
    * @return
    */
      public Point2D.Double getStageCoordsFromTileIndices(int row, int col) {
      double existingX = tile00StageCoords_.x;
      double existingY = tile00StageCoords_.y;
      double existingRow = 0;
      double existingColumn = 0;

      double xPixelOffset = (col - existingColumn) * (Engine.getCore().getImageWidth() - overlapX_);
      double yPixelOffset = (row - existingRow) * (Engine.getCore().getImageHeight() - overlapY_);

      Point2D.Double stagePos = new Point2D.Double();
      double[] mat = new double[4];
      affine_.getMatrix(mat);
      AffineTransform transform = new AffineTransform(mat[0], mat[1], mat[2], mat[3], existingX, existingY);
      transform.transform(new Point2D.Double(xPixelOffset, yPixelOffset), stagePos);
      return stagePos;
   }

   public int getDisplayTileHeight() {
      return displayTileHeight_;
   }

   public int getDisplayTileWidth() {
      return displayTileWidth_;
   }
   
   public Point2D.Double[] getDisplayTileCornerStageCoords(XYStagePosition pos) {
      return pos.getVisibleTileCorners(overlapX_, overlapY_);
   }

   /**
    * Get list of position objects corresponding to these rows and columns
    * @param newPositionRows
    * @param newPositionCols
    * @return
    */
   public List<XYStagePosition> getPositions(int[] newPositionRows, int[] newPositionCols) {
      ArrayList<XYStagePosition> positions = new ArrayList<XYStagePosition>();
      for (int i = 0; i < newPositionRows.length; i++) {
         Point2D.Double center = getStageCoordsFromTileIndices(newPositionRows[i], newPositionCols[i]);
         XYStagePosition pos = new XYStagePosition(center, newPositionRows[i], newPositionCols[i]);
         positions.add(pos);
      }
      return positions;
   }
}
