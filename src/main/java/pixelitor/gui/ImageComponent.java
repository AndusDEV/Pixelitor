/*
 * Copyright 2016 Laszlo Balazs-Csiki
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.gui;

import org.jdesktop.swingx.painter.CheckerboardPainter;
import pixelitor.AppLogic;
import pixelitor.Canvas;
import pixelitor.Composition;
import pixelitor.ConsistencyChecks;
import pixelitor.gui.utils.Dialogs;
import pixelitor.history.AddToHistory;
import pixelitor.history.CompositionReplacedEdit;
import pixelitor.history.History;
import pixelitor.layers.Layer;
import pixelitor.layers.LayerButton;
import pixelitor.layers.LayerMask;
import pixelitor.layers.LayersContainer;
import pixelitor.layers.LayersPanel;
import pixelitor.menus.view.ZoomComponent;
import pixelitor.menus.view.ZoomLevel;
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.debug.ImageComponentNode;

import javax.swing.*;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyVetoException;

import static java.awt.Color.BLACK;
import static pixelitor.Composition.ImageChangeActions.FULL;

/**
 * The GUI component that shows a composition
 */
public class ImageComponent extends JComponent implements MouseListener, MouseMotionListener {
    private double viewScale = 1.0f;
    private Canvas canvas;
    private ZoomLevel zoomLevel = ZoomLevel.Z100;

    private InternalImageFrame internalFrame = null;

    private static final Color BG_GRAY = new Color(200, 200, 200);
    private static final CheckerboardPainter checkerBoardPainter = new CheckerboardPainter(BG_GRAY, Color.WHITE);

    private final LayersPanel layersPanel;

    private Composition comp;

    private boolean showLayerMask = false;

    // the start of the image if the ImageComponent is resized to bigger
    // than the canvas, and the image needs to be centralized
    private double drawStartX;
    private double drawStartY;

    public static boolean showPixelGrid = false;

    public ImageComponent(Composition comp) {
        assert comp != null;

        this.comp = comp;
        this.canvas = comp.getCanvas();
        comp.setIC(this);

        ZoomLevel fitZoom = Desktop.calcFitScreenZoom(canvas.getWidth(), canvas.getHeight(), false);
        setZoom(fitZoom, true, null);

        layersPanel = new LayersPanel();

        addListeners();
    }

    public void replaceComp(Composition newComp, AddToHistory addToHistory) {
        assert newComp != null;

        Composition oldComp = comp;
        comp = newComp;
        oldComp.setIC(null);
        comp.setIC(this);
        canvas = newComp.getCanvas();

        // keep the zoom level, but reinitialize the
        // internal frame size
        setZoom(zoomLevel, true, null);

        comp.imageChanged(FULL);

        History.addEdit(addToHistory, () -> new CompositionReplacedEdit(oldComp, newComp));
    }

    private void addListeners() {
        addMouseListener(this);
        addMouseMotionListener(this);

        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) { // up, away from the user
                    increaseZoom(e.getPoint());
                } else {  // down, towards the user
                    decreaseZoom(e.getPoint());
                }
            }
        });

        // make sure that the image is drawn at the middle
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateDrawStart();

                if (Tools.getCurrentTool() == Tools.CROP) {
                    Tools.CROP.icResized(ImageComponent.this);
                }
                repaint();
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        if (comp.isEmpty()) {
            return super.getPreferredSize();
        } else {
            return canvas.getZoomedSize();
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Tools.EventDispatcher.mouseClicked(e, this);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
//        mouseEntered is never used in the tools
    }

    @Override
    public void mouseExited(MouseEvent e) {
//        mouseExited is never used in the tools
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Tools.EventDispatcher.mousePressed(e, this);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        Tools.EventDispatcher.mouseReleased(e, this);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Tools.EventDispatcher.mouseDragged(e, this);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        Tools.EventDispatcher.mouseMoved(e, this);
    }

    @Override
    public String toString() {
        ImageComponentNode node = new ImageComponentNode("ImageComponent", this);
        return node.toDetailedString();
    }

    public void setInternalFrame(InternalImageFrame internalFrame) {
        this.internalFrame = internalFrame;
    }

    public InternalImageFrame getInternalFrame() {
        return internalFrame;
    }

    public void close() {
        if (internalFrame != null) {
            // this will also cause the calling of AppLogic.imageClosed via
            // InternalImageFrame.internalFrameClosed
            internalFrame.dispose();
        }
        comp.dispose();
    }

    public void onActivation() {
        try {
            getInternalFrame().setSelected(true);
        } catch (PropertyVetoException e) {
            Messages.showException(e);
        }
        LayersContainer.showLayersPanel(layersPanel);
    }

    public double getViewScale() {
        return viewScale;
    }

    public void updateTitle() {
        if (internalFrame != null) {
            String frameTitle = createFrameTitle();
            internalFrame.setTitle(frameTitle);
        }
    }

    public String createFrameTitle() {
        return comp.getName() + " - " + zoomLevel.toString();
    }

    public ZoomLevel getZoomLevel() {
        return zoomLevel;
    }

    public void deleteLayerButton(LayerButton button) {
        layersPanel.deleteLayerButton(button);
    }

    public Composition getComp() {
        return comp;
    }

    public void changeLayerOrderInTheGUI(int oldIndex, int newIndex) {
        layersPanel.changeLayerOrderInTheGUI(oldIndex, newIndex);
    }

    @Override
    public void paint(Graphics g) {
        try {
//            long startTime = System.nanoTime();

            // no borders, no children, double-buffering is happening
            // in the parent
            paintComponent(g);

//            double estimatedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
//            System.out.println(String.format("ImageComponent::paint: estimatedSeconds = '%.2f'", estimatedSeconds));
        } catch (OutOfMemoryError e) {
            Dialogs.showOutOfMemoryDialog(e);
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        Shape originalClip = g.getClip();

        Graphics2D g2 = (Graphics2D) g;

        int zoomedWidth = canvas.getZoomedWidth();
        int zoomedHeight = canvas.getZoomedHeight();

        Rectangle imageClip = adjustClipBoundsForImage(g, drawStartX, drawStartY, zoomedWidth, zoomedHeight);

        AffineTransform unscaledTransform = g2.getTransform(); // a copy of the transform object

        g2.translate(drawStartX, drawStartY);

//        if (!showLayerMask) {
        checkerBoardPainter.paint(g2, this, zoomedWidth, zoomedHeight);
//        }

        g2.scale(viewScale, viewScale);

        if (showLayerMask) {
            LayerMask layerMask = comp.getActiveLayer().getMask();
            layerMask.paintLayerOnGraphics(g2, true);
        } else {
            BufferedImage drawnImage = comp.getCompositeImage();
            ImageUtils.drawImageWithClipping(g2, drawnImage);
        }

        // possibly allow a larger clip for the selections and tools
        Tool currentTool = Tools.getCurrentTool();
        currentTool.setClip(g2);

        comp.paintSelection(g2);

        currentTool.paintOverImage(g2, canvas, this, unscaledTransform);

        // restore original transform
        g2.setTransform(unscaledTransform);

        g2.setClip(imageClip);

        // draw pixel grid
        if (showPixelGrid && zoomLevel.drawPixelGrid() && !comp.hasSelection()) {
            // TODO why is this very slow if there is selection?

            g2.setXORMode(BLACK);
            double pixelSize = zoomLevel.getViewScale();
//            assert pixelSize > 0;

//            System.out.println("ImageComponent::paintComponent: START zoomLevel = " + zoomLevel
//                    + ", pixelSize = " + pixelSize
//                    + ", width = " + zoomedWidth + ", height = " + zoomedHeight
//                    + ", comp = " + comp.getName());
//            long startTime = System.nanoTime();

            int startX = (int) this.drawStartX;
            int startY = (int) this.drawStartY;

            int endX = zoomedWidth + startX;
            int endY = zoomedHeight + startY;

            // vertical lines
            for (double i = pixelSize; i < zoomedWidth; i += pixelSize) {
                int x = (int) (drawStartX + i);
                g2.drawLine(x, startY, x, endY);
            }
            // horizontal lines
            for (double i = pixelSize; i < zoomedHeight; i += pixelSize) {
                int y = (int) (drawStartY + i);
                g2.drawLine(startX, y, endX, y);
            }

//            double estimatedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
//            System.out.println(String.format("ImageComponent::paintComponent: FINISHED estimatedSeconds = '%.2f'", estimatedSeconds));
        }

        g2.setClip(originalClip);
    }

    /**
     * Makes sure that not the whole area is repainted, only the image
     */
    private static Rectangle adjustClipBoundsForImage(Graphics g, double drawStartX, double drawStartY, int maxWidth, int maxHeight) {
        Rectangle clipBounds = g.getClipBounds();
        Rectangle imageRect = new Rectangle((int) drawStartX, (int) drawStartY, maxWidth, maxHeight);
        clipBounds = clipBounds.intersection(imageRect);

        g.setClip(clipBounds);
        return clipBounds;
    }

    /**
     * Repaints only a region of the image, called from the brush tools
     */
    public void updateRegion(double startX, double startY, double endX, double endY, int thickness) {
        if (zoomLevel != ZoomLevel.Z100) { // not the 100% view
            startX = (int) (drawStartX + viewScale * startX);
            startY = (int) (drawStartY + viewScale * startY);
            endX = (int) (drawStartX + viewScale * endX);
            endY = (int) (drawStartY + viewScale * endY);
            thickness = (int) (viewScale * thickness);
        } else { // drawStartX drawStartY has to be adjusted anyway
            startX = (int) (drawStartX + startX);
            startY = (int) (drawStartY + startY);
            endX = (int) (drawStartX + endX);
            endY = (int) (drawStartY + endY);
        }

        if (endX < startX) {
            double tmp = startX;
            startX = endX;
            endX = tmp;
        }
        if (endY < startY) {
            double tmp = startY;
            startY = endY;
            endY = tmp;
        }
        startX -= thickness;
        endX += thickness;
        startY -= thickness;
        endY += thickness;

        double repWidth = endX - startX;
        double repHeight = endY - startY;

        repaint((int) startX, (int) startY, (int) repWidth, (int) repHeight);
    }

    public void makeSureItIsVisible() {
        if (internalFrame != null) {
            internalFrame.makeSureItIsVisible();
        }
    }

    public void setShowLayerMask(boolean showLayerMask) {
        this.showLayerMask = showLayerMask;

        if (showLayerMask) {
            assert comp.getActiveLayer().hasMask();
        }

        repaint();
    }

    public void canvasSizeChanged() {
        assert ConsistencyChecks.imageCoversCanvasCheck(comp);

        if (internalFrame != null) {
            internalFrame.setSize(canvas.getZoomedWidth(), canvas.getZoomedHeight(), -1, -1);
        }
        revalidate();
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void zoomToFitScreen() {
        BufferedImage image = comp.getCompositeImage();
        int width = image.getWidth();
        int height = image.getHeight();
        ZoomLevel fitZoom = Desktop.calcFitScreenZoom(width, height, true);
        setZoom(fitZoom, false, null);
    }

    /**
     * Sets the new zoom level
     */
    public void setZoom(ZoomLevel newZoom, boolean forceSettingSize, Point mousePos) {
        ZoomLevel oldZoom = zoomLevel;
        if (oldZoom == newZoom && !forceSettingSize) {
            // forceSettingSize is set at initial creation and at F5 reload
            return;
        }

        this.zoomLevel = newZoom;

        viewScale = newZoom.getViewScale();
        canvas.updateForZoom(viewScale);

        Rectangle areaThatShouldBeVisible = null;
        if (internalFrame != null) {
            updateTitle();
            internalFrame.setSize(canvas.getZoomedWidth(), canvas.getZoomedHeight(), -1, -1);

            // Update the scrollbars.
            Point origin;
            if (mousePos != null) { // we had a mouse click
                // the x, y coordinates were generated BEFORE the zooming
                // so we need to find the corresponding coordinates after zooming
                Point imageSpaceOrigin = fromComponentToImageSpace(mousePos, oldZoom);
                origin = fromImageToComponentSpace(imageSpaceOrigin, newZoom);
            } else {
                int cx = canvas.getZoomedWidth() / 2;
                int cy = canvas.getZoomedHeight() / 2;

                origin = new Point(cx, cy);
            }

            Rectangle viewRect = getViewRect();
            areaThatShouldBeVisible = new Rectangle(
                    origin.x - viewRect.width / 2,
                    origin.y - viewRect.height / 2,
                    viewRect.width,
                    viewRect.height
            );
        }
//        SwingUtilities.invokeLater(() -> {
//            scrollRectToVisible(areaThatShouldBeVisible);
//            repaint();
//        });

        //updateDrawStart();

        revalidate();
//        validate(); // make sure the size is updated


//        repaint();

        Rectangle finalRect = areaThatShouldBeVisible;

        // TODO is this necessary? - could call validate instead of revalidate
        // some flickering is present either way

        // we are already on the EDT, but we want to call this code
        // only after all pending AWT events have been processed
        // because then this component will have the final size
        // and updateDrawStart can calculate correct results
        Runnable r = () -> {
            updateDrawStart();
            if (finalRect != null) {
                scrollRectToVisible(finalRect);
            }
            repaint();
        };
        SwingUtilities.invokeLater(r);

        if (ImageComponents.getActiveIC() == this) {
            ZoomComponent.INSTANCE.setToNewZoom(zoomLevel);
            zoomLevel.getMenuItem().setSelected(true);
        }
    }

    public void setZoomAtCenter(ZoomLevel newZoom) {
        setZoom(newZoom, false, null);
    }

    public void increaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomIn();
        setZoom(newZoom, false, mousePos);
    }

    public void decreaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomOut();
        setZoom(newZoom, false, mousePos);
    }

    public void updateDrawStart() {
        int width = getWidth();
        int canvasZoomedWidth = canvas.getZoomedWidth();
        int height = getHeight();
        int canvasZoomedHeight = canvas.getZoomedHeight();

        drawStartX = (width - canvasZoomedWidth) / 2.0;
        drawStartY = (height - canvasZoomedHeight) / 2.0;
    }

    public double componentXToImageSpace(int mouseX) {
        return ((mouseX - drawStartX) / viewScale);
    }

    public double componentYToImageSpace(int mouseY) {
        return ((mouseY - drawStartY) / viewScale);
    }

    public int imageXToComponentSpace(double mouseX) {
        return (int) (drawStartX + mouseX * viewScale);
    }

    public int imageYToComponentSpace(double mouseY) {
        return (int) (drawStartY + mouseY * viewScale);
    }

    public Point fromComponentToImageSpace(Point input, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) ((input.x - drawStartX) / zoomViewScale),
                (int) ((input.y - drawStartY) / zoomViewScale)
        );
    }

    public Point fromImageToComponentSpace(Point input, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) (drawStartX + input.x * zoomViewScale),
                (int) (drawStartY + input.y * zoomViewScale)
        );
    }

    public Rectangle2D fromComponentToImageSpace(Rectangle input) {
        return new Rectangle.Double(
                componentXToImageSpace(input.x),
                componentYToImageSpace(input.y),
                (input.getWidth() / viewScale),
                (input.getHeight() / viewScale)
        );
    }

    public Rectangle fromImageToComponentSpace(Rectangle2D input) {
        return new Rectangle(
                imageXToComponentSpace(input.getX()),
                imageYToComponentSpace(input.getY()),
                (int) (input.getWidth() * viewScale),
                (int) (input.getHeight() * viewScale)
        );
    }

    // TODO untested
    public AffineTransform getImageToComponentTransform() {
        AffineTransform t = new AffineTransform();
        t.translate(drawStartX, drawStartY);
        t.scale(viewScale, viewScale);
        return t;
    }

    // TODO untested
    public AffineTransform getComponentToImageTransform() {
        AffineTransform inverse = null;
        try {
            inverse = getImageToComponentTransform().createInverse();
        } catch (NoninvertibleTransformException e) {
            // should not happen
            e.printStackTrace();
        }
        return inverse;
    }

    /**
     * Returns how much of this ImageComponent is currently visible considering that
     * the JScrollPane might show only a part of it
     */
    public Rectangle getViewRect() {
        return internalFrame.getScrollPane().getViewport().getViewRect();
    }

    public void addLayerToGUI(Layer newLayer, int newLayerIndex) {
        LayerButton layerButton = newLayer.getUI().getLayerButton();
        layersPanel.addLayerButton(layerButton, newLayerIndex);

        if (ImageComponents.isActive(this)) {
            AppLogic.activeCompLayerCountChanged(comp, comp.getNrLayers());
        }
    }

    public boolean isMaskShowing() {
        return showLayerMask;
    }

    public boolean activeIsImageLayer() {
        return comp.activeIsImageLayer();
    }

    @SuppressWarnings("MethodMayBeStatic")
    public boolean isMock() {
        return false;
    }
}
