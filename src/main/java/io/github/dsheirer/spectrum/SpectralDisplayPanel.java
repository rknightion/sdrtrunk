/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.spectrum;

import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.dsp.filter.smoothing.SmoothingFilter.SmoothingType;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.channel.ScanSpanRequest;
import io.github.dsheirer.gui.playlist.channel.ShowDiscoveryRequest;
import io.github.dsheirer.gui.playlist.channel.ViewChannelRequest;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.ColorSetting.ColorSettingName;
import io.github.dsheirer.settings.ColorSettingMenuItem;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.spectrum.OverlayPanel.ChannelDisplay;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import io.github.dsheirer.spectrum.converter.DFTResultsConverter;
import io.github.dsheirer.spectrum.menu.AveragingItem;
import io.github.dsheirer.spectrum.menu.DFTSizeItem;
import io.github.dsheirer.spectrum.menu.FFTWindowTypeItem;
import io.github.dsheirer.spectrum.menu.FrameRateItem;
import io.github.dsheirer.spectrum.menu.SmoothingItem;
import io.github.dsheirer.spectrum.menu.SmoothingTypeItem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Hashtable;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.MouseInputAdapter;

public class SpectralDisplayPanel extends JPanel
        implements Listener<INativeBuffer>, ISourceEventProcessor, IDFTWidthChangeProcessor
{
    private static final long serialVersionUID = 1L;

    private final static Logger mLog = LoggerFactory.getLogger(SpectralDisplayPanel.class);

    public static final String FFT_SIZE_PROPERTY = "spectral.display.dft.size";
    public static final String SPECTRAL_DISPLAY_ENABLED = "spectral.display.enabled";
    public static final int NO_ZOOM = 0;
    public static final int MAX_ZOOM = 6;

    private DFTSize mDFTSize = DFTSize.FFT04096;
    private int mZoom = 0;
    private int mDFTZoomWindowOffset = 0;

    private JScrollPane mScrollPane;
    private JLayeredPane mLayeredPanel;
    private SpectrumPanel mSpectrumPanel;
    private WaterfallPanel mWaterfallPanel;
    private OverlayPanel mOverlayPanel;
    private ComplexDftProcessor mComplexDftProcessor;
    private DFTResultsConverter mDFTConverter;
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private SettingsManager mSettingsManager;
    private DiscoveredTunerModel mDiscoveredTunerModel;
    private Tuner mTuner;

    /** Click-to-tune controller; null when not yet wired (feature disabled). */
    private ClickToTuneController mClickToTuneController;

    /** Pending-classification overlay; null until wired via {@link #setClickToTuneController}. */
    private PendingClassificationOverlay mPendingOverlay;

    /** Discovery overlay; null until wired via {@link #setDiscoveryModel}. */
    private DiscoveryOverlay mDiscoveryOverlay;

    /**
     * Spectral Display Panel provides a frequency component display with a
     * historical waterfall display and a transparent overlay to show frequency,
     * cursor and channel information.
     * <p>
     * Mouse scrolling and zooming are supported and the waterfall display can
     * be paused.
     * <p>
     * Complex sample buffers are processed by a DFTProcessor and the output of
     * the DFT is translated to decibels for display in the spectrum and
     * waterfall components.
     */
    public SpectralDisplayPanel(PlaylistManager playlistManager, SettingsManager settingsManager, DiscoveredTunerModel discoveredTunerModel)
    {
        mChannelModel = playlistManager.getChannelModel();
        mChannelProcessingManager = playlistManager.getChannelProcessingManager();
        mSettingsManager = settingsManager;
        mDiscoveredTunerModel = discoveredTunerModel;

        mSpectrumPanel = new SpectrumPanel(mSettingsManager);
        mOverlayPanel = new OverlayPanel(mSettingsManager, mChannelModel, mChannelProcessingManager);
        mWaterfallPanel = new WaterfallPanel(mSettingsManager);

        init();

        loadSettings();
    }

    /**
     * Shows the pending-classification overlay at the given span.
     * Delegates to {@link PendingClassificationOverlay#setPending}.
     * No-op if the overlay is not yet installed.
     *
     * @param centerFreqHz centre frequency of the probed span, in Hz
     * @param widthHz      estimated width of the probed span, in Hz
     */
    public void showPendingOverlay(long centerFreqHz, int widthHz)
    {
        if(mPendingOverlay != null)
        {
            mPendingOverlay.setPending(centerFreqHz, widthHz, mOverlayPanel);
        }
    }

    /**
     * Clears the pending-classification overlay.
     * No-op if the overlay is not yet installed.
     */
    public void clearPendingOverlay()
    {
        if(mPendingOverlay != null)
        {
            mPendingOverlay.clear();
        }
    }

    /**
     * Wires in the click-to-tune controller and installs the pending-classification overlay
     * on the {@link JLayeredPane} above the overlay panel.
     *
     * <p>May be called at any time after construction.  Calling it more than once replaces
     * the previous controller and removes the old overlay.</p>
     *
     * @param controller the click-to-tune controller; must not be null
     */
    public void setClickToTuneController(ClickToTuneController controller)
    {
        // Remove the old overlay if present
        if(mPendingOverlay != null)
        {
            mLayeredPanel.remove(mPendingOverlay);
        }

        mClickToTuneController = controller;

        // Create and install the pending overlay
        mPendingOverlay = new PendingClassificationOverlay(() -> mClickToTuneController.cancelPending());

        // Place it above the OverlayPanel (layer 1) but below any other floating content
        mLayeredPanel.add(mPendingOverlay, JLayeredPane.PALETTE_LAYER);

        // Size it to match the layered pane
        mPendingOverlay.setBounds(0, 0, mLayeredPanel.getWidth(), mLayeredPanel.getHeight());

        mLayeredPanel.revalidate();
    }

    /**
     * Wires in the discovery model and installs the {@link DiscoveryOverlay} on the
     * {@link JLayeredPane} between the overlay panel and the pending-classification overlay.
     *
     * <p>Calling this method a second time disposes the old overlay and installs a new one
     * (not expected in normal usage, but safe).</p>
     *
     * @param discoveryModel the model whose rows are painted; must not be null
     * @param discoveryPreference preference controlling overlay visibility mode; must not be null
     */
    public void setDiscoveryModel(io.github.dsheirer.module.discovery.DiscoveryModel discoveryModel,
                                   io.github.dsheirer.preference.discovery.DiscoveryPreference discoveryPreference)
    {
        // Remove the previous overlay if present
        if(mDiscoveryOverlay != null)
        {
            mDiscoveryOverlay.dispose();
            mLayeredPanel.remove(mDiscoveryOverlay);
        }

        mDiscoveryOverlay = new DiscoveryOverlay(discoveryModel, discoveryPreference, mOverlayPanel);

        // Place it above the channel overlay panel (DEFAULT_LAYER / PALETTE_LAYER) but below the
        // pending-classification overlay (PALETTE_LAYER).  Both overlays are paint-only
        // (no mouse listeners) so relative Z-order does not affect event routing.
        mLayeredPanel.add(mDiscoveryOverlay, JLayeredPane.POPUP_LAYER);
        mDiscoveryOverlay.setBounds(0, 0, mLayeredPanel.getWidth(), mLayeredPanel.getHeight());

        mLayeredPanel.revalidate();
    }

    /**
     * Returns the current {@link DiscoveryOverlay}, or {@code null} if not yet installed.
     * Used by the context menu to add the DiscoveryDisplay toggle.
     *
     * @return discovery overlay or null
     */
    public DiscoveryOverlay getDiscoveryOverlay()
    {
        return mDiscoveryOverlay;
    }

    private void loadSettings()
    {
        SystemProperties properties = SystemProperties.getInstance();

        String rawSize = properties.get(FFT_SIZE_PROPERTY, DFTSize.FFT04096.name());

        DFTSize size = null;

        if(rawSize != null)
        {
            try
            {
                size = DFTSize.valueOf(rawSize);
            }
            catch(Exception e)
            {
                //Do nothing
            }
        }

        if(size == null)
        {
            size = DFTSize.FFT04096;
        }

        setDFTSize(size, false);
    }

    public void dispose()
    {
        /* De-register from receiving samples when the window closes */
        clearTuner();

        mSettingsManager = null;

        mComplexDftProcessor.dispose();
        mComplexDftProcessor = null;

        mDFTConverter.dispose();
        mDFTConverter = null;

        mSpectrumPanel.dispose();
        mSpectrumPanel = null;

        mWaterfallPanel.dispose();
        mWaterfallPanel = null;

        mOverlayPanel.dispose();
        mOverlayPanel = null;

        if(mDiscoveryOverlay != null)
        {
            mDiscoveryOverlay.dispose();
            mDiscoveryOverlay = null;
        }

        mTuner = null;
    }

    /**
     * Queues an FFT size change request.  The scheduled executor will apply
     * the change when it runs.
     */
    public void setDFTSize(DFTSize size, boolean save)
    {
        mComplexDftProcessor.setDFTSize(size);
        mOverlayPanel.setDFTSize(size);
        mDFTSize = size;

        if(save)
        {
            SystemProperties.getInstance().set(FFT_SIZE_PROPERTY, size.name());
        }

        setZoom(0, 0, 0);
    }

    public void setDFTSize(DFTSize size)
    {
        setDFTSize(size, true);
    }

    @Override public DFTSize getDFTSize()
    {
        return mDFTSize;
    }

    public int getZoom()
    {
        return mZoom;
    }

    /**
     * Sets the current zoom level which will be 2 to the power of zoom (2^zoom)
     * <p>
     * 0 	No Zoom
     * 1	2x Zoom
     * 2	4x Zoom
     * 3	8x Zoom
     * 4	16x Zoom
     * 5	32x Zoom
     * 6    64x Zoom
     *
     * @param zoom         level, 0 - 6.
     * @param frequency    under the mouse to maintain while zooming
     * @param windowOffset where to maintain the frequency under the mouse
     */
    public void setZoom(int zoom, long frequency, double windowOffset)
    {
        if(zoom < NO_ZOOM)
        {
            zoom = NO_ZOOM;
        }
        else if(zoom > MAX_ZOOM)
        {
            zoom = MAX_ZOOM;
        }

        if(zoom != mZoom)
        {
            mZoom = zoom;

            //Calculate the bin offset that would place the reference frequency
            //at the left edge of the zoom window.
            double binOffsetToFrequency = getBinOffset(frequency);

            //Calculate the bin offset into the newly sized zoom window that
            //would place the frequency in the same proportional window location
            //that it was in the previous zoom size
            double windowBinOffset = (double)getZoomWindowSizeInBins() * windowOffset;

            //Set the overall offset to place the reference frequency in the
            //same location in the newly zoomed window
            double offset = binOffsetToFrequency - windowBinOffset;

            mSpectrumPanel.setZoom(mZoom);
            mOverlayPanel.setZoom(mZoom);
            mWaterfallPanel.setZoom(mZoom);

            setZoomWindowOffset(offset);
        }
    }

    /**
     * Sets the offset (in DFT bins) to the first bin that the zoom window displays
     */
    public void setZoomWindowOffset(double offset)
    {
        if(offset < 0)
        {
            offset = 0;
        }

        if(offset > (mDFTSize.getSize() - getZoomWindowSizeInBins()))
        {
            offset = mDFTSize.getSize() - getZoomWindowSizeInBins();
        }

        mDFTZoomWindowOffset = (int)offset;

        mSpectrumPanel.setZoomWindowOffset(mDFTZoomWindowOffset);
        mOverlayPanel.setZoomWindowOffset(mDFTZoomWindowOffset);
        mWaterfallPanel.setZoomWindowOffset(mDFTZoomWindowOffset);
    }

    /**
     * Calculates the size of the current zoom window in DFT bins
     */
    private int getZoomWindowSizeInBins()
    {
        return mDFTSize.getSize() / getZoomMultiplier();
    }

    public int getZoomMultiplier()
    {
        return (int)FastMath.pow(2.0, mZoom);
    }

    /**
     * Calculates the overall offset of the frequency from the current minimum
     * frequency in terms of total FFT width
     *
     * @param frequency
     * @return
     */
    private double getBinOffset(long frequency)
    {
        double offset = 0.0;

        if(mOverlayPanel.containsFrequency(frequency))
        {
            offset = (double)mDFTSize.getSize() * ((double)(frequency - mOverlayPanel.getMinFrequency())
                    / (double)mOverlayPanel.getBandwidth());
        }

        return offset;
    }

    /**
     * Overrides JComponent method to return false, since we have overlapping
     * panels with the spectrum and channel panels
     */
    public boolean isOptimizedDrawingEnabled()
    {
        return false;
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow]", "[grow]"));

        /**
         * The layered pane holds the overlapping spectrum and channel panels
         * and manages the sizing of each panel with the resize listener
         */
        mLayeredPanel = new JLayeredPane();
        mLayeredPanel.addComponentListener(new ResizeListener());

        /**
         * Create a mouse adapter to handle mouse events over the spectrum
         * and waterfall panels
         */
        MouseEventProcessor mouser = new MouseEventProcessor();

        mOverlayPanel.addMouseListener(mouser);
        mOverlayPanel.addMouseMotionListener(mouser);
        mOverlayPanel.addMouseWheelListener(mouser);

        //Add the spectrum and channel panels to the layered panel
        mLayeredPanel.add(mSpectrumPanel, 0, 0);
        mLayeredPanel.add(mOverlayPanel, 1, 0);

        //Create the waterfall
        mWaterfallPanel.addMouseListener(mouser);
        mWaterfallPanel.addMouseMotionListener(mouser);
        mWaterfallPanel.addMouseWheelListener(mouser);

        /* Attempt to set a 50/50 split preferred size for the split pane */
        double totalHeight =
                mLayeredPanel.getPreferredSize().getHeight() + mWaterfallPanel.getPreferredSize().getHeight();

        mLayeredPanel.setPreferredSize(
                new Dimension((int)mLayeredPanel.getPreferredSize().getWidth(), (int)(totalHeight / 2.0d)));

        mWaterfallPanel.setPreferredSize(
                new Dimension((int)mWaterfallPanel.getPreferredSize().getWidth(), (int)(totalHeight / 2.0d)));

        //Create the split pane to hold the layered pane and the waterfall
        JideSplitPane splitPane = new JideSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerSize(5);
        splitPane.add(mLayeredPanel);
        splitPane.add(mWaterfallPanel);

        mScrollPane = new JScrollPane(splitPane);

        add(mScrollPane, "grow");

        /**
         * Setup DFTProcessor to process samples and register the waterfall and
         * spectrum panel to receive the processed dft results
         */
        mComplexDftProcessor = new ComplexDftProcessor();
        mDFTConverter = new ComplexDecibelConverter();
        mComplexDftProcessor.addConverter(mDFTConverter);

        mDFTConverter.addListener((DFTResultsListener)mSpectrumPanel);
        mDFTConverter.addListener((DFTResultsListener)mWaterfallPanel);
    }

    /**
     * Receives frequency change events -- primarily from tuner components.
     */
    public void process(SourceEvent event)
    {
        mOverlayPanel.process(event);
    }

    /**
     * Complex sample buffer receive method
     */
    @Override public void receive(INativeBuffer nativeBuffer)
    {
        mComplexDftProcessor.receive(nativeBuffer);
    }

    /**
     * Responds to tuner event by deregistering from the current
     * complex sample buffer source and registering with the tuner argument.
     */
    public void showTuner(Tuner tuner)
    {
        clearTuner();

        mComplexDftProcessor.clearBuffer();

        mComplexDftProcessor.start();

        mTuner = tuner;

        if(mTuner != null)
        {
            //Register the dft processor to receive samples from the tuner
            mTuner.getTunerController().addBufferListener(mComplexDftProcessor);

            //Verify that the tuner is still non-null, in case it encountered an error on starting sample stream
            if(mTuner != null)
            {
                //Register to receive frequency change events
                mTuner.getTunerController().addListener(this);

                mSpectrumPanel.setSampleSize(mTuner.getSampleSize());

                //Fire frequency and sample rate change events so that the spectrum
                //and overlay panels can synchronize
                process(SourceEvent.frequencyChange(null, mTuner.getTunerController().getFrequency()));
                process(SourceEvent.sampleRateChange(mTuner.getTunerController().getSampleRate()));
            }
        }
    }

    /**
     * Tuner de-selection cleanup method
     */
    public void clearTuner()
    {
        if(mTuner != null)
        {
            //Deregister for frequency change events from the tuner
            mTuner.getTunerController().removeListener(SpectralDisplayPanel.this);

            //Deregister the dft processor from receiving samples
            mTuner.getTunerController().removeBufferListener(mComplexDftProcessor);
            mTuner = null;
        }

        mComplexDftProcessor.stop();
        mComplexDftProcessor.clearBuffer();
        mSpectrumPanel.clearSpectrum();
        mWaterfallPanel.clearWaterfall();
    }

    /**
     * Currently displayed tuner
     */
    public Tuner getTuner()
    {
        return mTuner;
    }

    /**
     * Monitors the sizing of the layered pane and resizes the spectrum and
     * channel panels whenever the layered pane is resized
     */
    public class ResizeListener implements ComponentListener
    {
        @Override public void componentResized(ComponentEvent e)
        {
            Component c = e.getComponent();

            mSpectrumPanel.setBounds(0, 0, c.getWidth(), c.getHeight());
            mOverlayPanel.setBounds(0, 0, c.getWidth(), c.getHeight());

            if(mDiscoveryOverlay != null)
            {
                mDiscoveryOverlay.setBounds(0, 0, c.getWidth(), c.getHeight());
            }

            if(mPendingOverlay != null)
            {
                mPendingOverlay.setBounds(0, 0, c.getWidth(), c.getHeight());
            }
        }

        @Override public void componentHidden(ComponentEvent arg0)
        {
        }

        @Override public void componentMoved(ComponentEvent arg0)
        {
        }

        @Override public void componentShown(ComponentEvent arg0)
        {
        }
    }

    // -------------------------------------------------------------------------
    // Click-to-tune helper methods (package-private for unit testing)
    // -------------------------------------------------------------------------

    /**
     * Computes the midpoint frequency (Hz) between two x-pixel positions on the overlay panel.
     *
     * @param xLeft  left pixel coordinate
     * @param xRight right pixel coordinate
     * @return midpoint frequency in Hz
     */
    long midpointFrequency(int xLeft, int xRight)
    {
        long freqLeft  = mOverlayPanel.getFrequencyFromAxis(xLeft);
        long freqRight = mOverlayPanel.getFrequencyFromAxis(xRight);
        return (freqLeft + freqRight) / 2L;
    }

    /**
     * Computes the frequency span (Hz) represented by a pixel range.
     *
     * @param xLeft  left pixel coordinate
     * @param xRight right pixel coordinate
     * @return span in Hz (always non-negative)
     */
    int pixelSpanToHz(int xLeft, int xRight)
    {
        long freqLeft  = mOverlayPanel.getFrequencyFromAxis(xLeft);
        long freqRight = mOverlayPanel.getFrequencyFromAxis(xRight);
        return (int) Math.abs(freqRight - freqLeft);
    }

    /**
     * Returns true if the given x-pixel range is "non-trivial" (>= 10 pixels wide).
     * Used to distinguish an intentional drag-select from an accidental mouse-jitter.
     *
     * @param xLeft  left pixel
     * @param xRight right pixel
     * @return true if |xRight - xLeft| >= 10
     */
    static boolean isNonTrivialDrag(int xLeft, int xRight)
    {
        return Math.abs(xRight - xLeft) >= 10;
    }

    /**
     * Mouse event handler for the spectral display panel.
     */
    public class MouseEventProcessor extends MouseInputAdapter
    {
        private int mDFTZoomWindowOffsetAtDragStart = 0;
        private int mDragStartX = 0;
        private double mPixelsPerBin;

        // --- Shift+drag selection state ---
        private boolean mShiftDragActive = false;
        private int mSelectionStartX = 0;
        private int mSelectionCurrentX = 0;
        private SelectionOverlay mSelectionOverlay;

        public MouseEventProcessor()
        {
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e)
        {
            int zoom = mZoom - e.getWheelRotation();

            long frequency = mOverlayPanel.getFrequencyFromAxis(e.getX());

            double windowOffset = (double)e.getX() / (double)getWidth();

            setZoom(zoom, frequency, windowOffset);
        }

        @Override public void mouseMoved(MouseEvent event)
        {
            update(event);
        }

        @Override public void mouseDragged(MouseEvent event)
        {
            update(event);

            if(mShiftDragActive)
            {
                // Shift+drag: update the live selection rectangle
                mSelectionCurrentX = event.getX();

                if(mSelectionOverlay != null)
                {
                    int xLeft  = Math.min(mSelectionStartX, mSelectionCurrentX);
                    int xRight = Math.max(mSelectionStartX, mSelectionCurrentX);
                    long midHz = midpointFrequency(xLeft, xRight);
                    int  bwHz  = pixelSpanToHz(xLeft, xRight);

                    mSelectionOverlay.update(xLeft, xRight, midHz, bwHz);
                }

                return; // don't pan while Shift is held
            }

            // Plain drag → pan
            int dragDistance = mDragStartX - event.getX();

            double binDistance = (double)dragDistance / mPixelsPerBin;

            int offset = (int)(mDFTZoomWindowOffsetAtDragStart + binDistance);

            if(offset < 0)
            {
                offset = 0;
            }

            int maxOffset = mDFTSize.getSize() - (mDFTSize.getSize() / getZoomMultiplier());

            if(offset > maxOffset)
            {
                offset = maxOffset;
            }

            setZoomWindowOffset(offset);
        }

        @Override public void mousePressed(MouseEvent e)
        {
            mDragStartX = e.getX();
            mDFTZoomWindowOffsetAtDragStart = mDFTZoomWindowOffset;
            mPixelsPerBin = (double)getWidth() / ((double)(mDFTSize.getSize()) / (double)getZoomMultiplier());

            // Start a Shift+drag selection if Shift is held AND left button pressed
            // Gate on the overlay panel so waterfall coords don't mismatch spectrum coords
            if(SwingUtilities.isLeftMouseButton(e)
                && (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0
                && mClickToTuneController != null
                && e.getComponent() == mOverlayPanel)
            {
                mShiftDragActive = true;
                mSelectionStartX  = e.getX();
                mSelectionCurrentX = e.getX();

                if(mSelectionOverlay == null)
                {
                    mSelectionOverlay = new SelectionOverlay();
                    mLayeredPanel.add(mSelectionOverlay, JLayeredPane.DRAG_LAYER);
                    mSelectionOverlay.setBounds(0, 0,
                        mLayeredPanel.getWidth(), mLayeredPanel.getHeight());
                }

                mSelectionOverlay.setVisible(true);
                mSelectionOverlay.repaint();
            }
        }

        @Override public void mouseReleased(MouseEvent e)
        {
            if(mShiftDragActive)
            {
                mShiftDragActive = false;

                if(mSelectionOverlay != null)
                {
                    mSelectionOverlay.setVisible(false);
                    mSelectionOverlay.repaint();
                }

                int xLeft  = Math.min(mSelectionStartX, e.getX());
                int xRight = Math.max(mSelectionStartX, e.getX());

                if(isNonTrivialDrag(xLeft, xRight) && mClickToTuneController != null)
                {
                    long midHz = midpointFrequency(xLeft, xRight);
                    int  bwHz  = pixelSpanToHz(xLeft, xRight);

                    // showPendingOverlay / clearPendingOverlay are called via UICallbacks
                    mClickToTuneController.classifyAndTune(midHz, bwHz);
                }
            }
        }

        /**
         * Updates the cursor display while the mouse is performing actions
         */
        private void update(MouseEvent event)
        {
            if(event.getComponent() == mOverlayPanel)
            {
                mOverlayPanel.setCursorLocation(event.getPoint());
            }
            else
            {
                mWaterfallPanel.setCursorLocation(event.getPoint());
                mWaterfallPanel.setCursorFrequency(mOverlayPanel.getFrequencyFromAxis(event.getPoint().x));
            }
        }

        @Override public void mouseEntered(MouseEvent e)
        {
            if(e.getComponent() == mOverlayPanel)
            {
                mOverlayPanel.setCursorVisible(true);
            }
            else
            {
                mWaterfallPanel.setCursorVisible(true);
            }
        }

        @Override public void mouseExited(MouseEvent e)
        {
            mOverlayPanel.setCursorVisible(false);
            mWaterfallPanel.setCursorVisible(false);
        }

        /**
         * Displays the context menu.  Also handles double-click for click-to-tune.
         */
        @Override public void mouseClicked(MouseEvent event)
        {
            // Double-click (left button) → classify-and-tune at click frequency
            if(SwingUtilities.isLeftMouseButton(event)
                && event.getClickCount() == 2
                && mClickToTuneController != null
                && event.getComponent() == mOverlayPanel)
            {
                long freq = mOverlayPanel.getFrequencyFromAxis(event.getX());
                // Pending overlay is shown via UICallbacks.showPending() inside classifyAndTune
                mClickToTuneController.classifyAndTune(freq, 0);
                return;
            }

            if(SwingUtilities.isRightMouseButton(event))
            {
                JPopupMenu contextMenu = new JPopupMenu();

                if(event.getComponent() == mWaterfallPanel)
                {
                    contextMenu.add(new PauseItem(mWaterfallPanel, "Pause"));
                    contextMenu.add(new JSeparator());
                }

                long frequency = mOverlayPanel.getFrequencyFromAxis(event.getX());

                if(event.getComponent() == mOverlayPanel)
                {
                    ArrayList<Channel> channels = mOverlayPanel.getChannelsAtFrequency(frequency);

                    JMenu channelMenu = new JMenu("Channels");
                    for(Channel channel : channels)
                    {
                        JMenuItem viewChannel = new JMenuItem("View/Edit: " + channel.getShortTitle());
                        viewChannel.addActionListener(
                                e -> MyEventBus.getGlobalEventBus().post(new ViewChannelRequest(channel)));
                        channelMenu.add(viewChannel);

                        // If this channel was created by click-to-tune, add decoder-change items
                        if(mClickToTuneController != null
                            && mClickToTuneController.getClickToTuneChannels().contains(channel))
                        {
                            JMenu changeDecoder = new JMenu("Change decoder");
                            for(DecoderType type : DecoderType.PRIMARY_DECODERS)
                            {
                                JMenuItem decoderItem = new JMenuItem(type.getShortDisplayString());
                                decoderItem.addActionListener(ev ->
                                    mClickToTuneController.changeDecoder(channel, type));
                                changeDecoder.add(decoderItem);
                            }

                            channelMenu.add(changeDecoder);

                            JMenuItem redetect = new JMenuItem("Re-detect…");
                            redetect.addActionListener(ev -> mClickToTuneController.redetect(channel));
                            channelMenu.add(redetect);
                        }
                    }

                    contextMenu.add(channelMenu);

                    if(!channels.isEmpty())
                    {
                        contextMenu.add(new JSeparator());
                    }

                    // --- Click-to-tune items (only when controller is wired) ---
                    if(mClickToTuneController != null)
                    {
                        JMenuItem autoDetect = new JMenuItem("Decode here (auto-detect)");
                        final long clickFreq = frequency;
                        // Pending overlay is shown via UICallbacks.showPending() inside classifyAndTune
                        autoDetect.addActionListener(ev ->
                            mClickToTuneController.classifyAndTune(clickFreq, 0));
                        contextMenu.add(autoDetect);

                        JMenu decodeAsMenu = new JMenu("Decode here as");
                        for(DecoderType type : DecoderType.PRIMARY_DECODERS)
                        {
                            final DecoderType dt = type;
                            JMenuItem item = new JMenuItem(type.getDisplayString());
                            item.addActionListener(ev -> mClickToTuneController.tuneAs(clickFreq, dt));
                            decodeAsMenu.add(item);
                        }

                        contextMenu.add(decodeAsMenu);
                        contextMenu.add(new JSeparator());
                    }
                }

                /**
                 * Color Menus
                 */
                JMenu colorMenu = new JMenu("Color");

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.CHANNEL_CONFIG));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.CHANNEL_CONFIG_PROCESSING));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.CHANNEL_CONFIG_SELECTED));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.SPECTRUM_CURSOR));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.SPECTRUM_LINE));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.SPECTRUM_BACKGROUND));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.SPECTRUM_GRADIENT_BOTTOM));

                colorMenu.add(new ColorSettingMenuItem(mSettingsManager, ColorSettingName.SPECTRUM_GRADIENT_TOP));

                contextMenu.add(colorMenu);

                /**
                 * Display items: fft and frame rate
                 */
                JMenu displayMenu = new JMenu("Display");
                contextMenu.add(displayMenu);

                if(event.getComponent() != mWaterfallPanel)
                {
                    /**
                     * Averaging menu
                     */
                    JMenu averagingMenu = new JMenu("Averaging");
                    averagingMenu.add(new AveragingItem(mSpectrumPanel, 4));
                    displayMenu.add(averagingMenu);

                    /**
                     * Channel Display setting menu
                     */
                    JMenu channelDisplayMenu = new JMenu("Channel");

                    channelDisplayMenu.add(new ChannelDisplayItem(mOverlayPanel, ChannelDisplay.ALL));
                    channelDisplayMenu.add(new ChannelDisplayItem(mOverlayPanel, ChannelDisplay.ENABLED));
                    channelDisplayMenu.add(new ChannelDisplayItem(mOverlayPanel, ChannelDisplay.NONE));

                    displayMenu.add(channelDisplayMenu);

                    /**
                     * Discovery Display setting menu (only shown when overlay is installed)
                     */
                    if(mDiscoveryOverlay != null)
                    {
                        JMenu discoveryDisplayMenu = new JMenu("Discovery");
                        discoveryDisplayMenu.add(new DiscoveryDisplayItem(mDiscoveryOverlay, DiscoveryOverlay.DiscoveryDisplay.ALL));
                        discoveryDisplayMenu.add(new DiscoveryDisplayItem(mDiscoveryOverlay, DiscoveryOverlay.DiscoveryDisplay.IDENTIFIED_ONLY));
                        discoveryDisplayMenu.add(new DiscoveryDisplayItem(mDiscoveryOverlay, DiscoveryOverlay.DiscoveryDisplay.NONE));
                        displayMenu.add(discoveryDisplayMenu);
                    }
                }

                /**
                 * FFT width
                 */
                JMenu fftWidthMenu = new JMenu("FFT Width");
                displayMenu.add(fftWidthMenu);

                for(DFTSize width : DFTSize.values())
                {
                    fftWidthMenu.add(new DFTSizeItem(SpectralDisplayPanel.this, width));
                }

                /**
                 * DFT Processor Frame Rate
                 */
                JMenu frameRateMenu = new JMenu("Frame Rate");
                displayMenu.add(frameRateMenu);

                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 14));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 16));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 18));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 20));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 25));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 30));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 40));
                frameRateMenu.add(new FrameRateItem(mComplexDftProcessor, 50));

                /**
                 * FFT Window Type
                 */
                JMenu fftWindowType = new JMenu("Window Type");
                displayMenu.add(fftWindowType);

                for(WindowType type : WindowType.values())
                {
                    fftWindowType.add(new FFTWindowTypeItem(mComplexDftProcessor, type));
                }

                if(event.getComponent() != mWaterfallPanel)
                {
                    /**
                     * Smoothing menu
                     */
                    JMenu smoothingMenu = new JMenu("Smoothing");

                    if(mSpectrumPanel.getSmoothingType() != SmoothingType.NONE)
                    {
                        smoothingMenu.add(new SmoothingItem(mSpectrumPanel, 5));
                        smoothingMenu.add(new JSeparator());
                    }
                    smoothingMenu.add(new SmoothingTypeItem(mSpectrumPanel, SmoothingType.GAUSSIAN));
                    smoothingMenu.add(new SmoothingTypeItem(mSpectrumPanel, SmoothingType.TRIANGLE));
                    smoothingMenu.add(new SmoothingTypeItem(mSpectrumPanel, SmoothingType.RECTANGLE));
                    smoothingMenu.add(new SmoothingTypeItem(mSpectrumPanel, SmoothingType.NONE));

                    displayMenu.add(smoothingMenu);
                }

                /*
                 * Zoom menu
                 */
                JMenuItem zoomMenu = new JMenu("Zoom");

                double windowOffset = (double)event.getX() / (double)getWidth();

                zoomMenu.add(new ZoomItem(frequency, windowOffset));

                contextMenu.add(zoomMenu);

                if(mTuner != null)
                {
                    contextMenu.add(new JSeparator());
                    contextMenu.add(new DisableSpectrumWaterfallMenuItem(SpectralDisplayPanel.this));
                }

                boolean separatorAdded = false;

                for(DiscoveredTuner discoveredTuner : mDiscoveredTunerModel.getAvailableTuners())
                {
                    if(mTuner == null || mTuner != discoveredTuner.getTuner())
                    {
                        if(!separatorAdded)
                        {
                            contextMenu.add(new JSeparator());
                            separatorAdded = true;
                        }

                        contextMenu.add(new ShowTunerMenuItem(mDiscoveredTunerModel, discoveredTuner.getTuner()));
                    }
                }

                // --- Discovery / scan items (always present) ---
                if(mClickToTuneController != null)
                {
                    contextMenu.add(new JSeparator());

                    JMenuItem scanView = new JMenuItem("Scan this view…");
                    scanView.addActionListener(ev -> {
                        long minHz = mOverlayPanel.getMinFrequency();
                        long maxHz = mOverlayPanel.getMaxFrequency();

                        if(minHz > 0 && maxHz > minHz)
                        {
                            MyEventBus.getGlobalEventBus()
                                .post(new ScanSpanRequest(minHz, maxHz));
                        }
                    });
                    contextMenu.add(scanView);

                    JMenuItem showDiscoveries = new JMenuItem("Show discoveries");
                    showDiscoveries.addActionListener(ev ->
                        MyEventBus.getGlobalEventBus().post(new ShowDiscoveryRequest()));
                    contextMenu.add(showDiscoveries);
                }

                if(contextMenu != null)
                {
                    if(event.getComponent() == mOverlayPanel)
                    {
                        contextMenu.show(mOverlayPanel, event.getX(), event.getY());
                    }
                    else
                    {
                        contextMenu.show(mWaterfallPanel, event.getX(), event.getY());
                    }
                }
            }
        }
    }

    /**
     * Lightweight Swing component that draws the live Shift+drag selection rectangle
     * on the {@link JLayeredPane#DRAG_LAYER} during a selection drag.
     */
    private class SelectionOverlay extends JComponent
    {
        private static final long serialVersionUID = 1L;

        private int mXLeft;
        private int mXRight;
        private long mMidHz;
        private int mBwHz;

        SelectionOverlay()
        {
            setOpaque(false);
        }

        void update(int xLeft, int xRight, long midHz, int bwHz)
        {
            mXLeft  = xLeft;
            mXRight = xRight;
            mMidHz  = midHz;
            mBwHz   = bwHz;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            if(!isVisible() || mXRight <= mXLeft)
            {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();

            try
            {
                // Semi-transparent fill
                g2.setColor(new Color(255, 200, 0, 50));
                g2.fillRect(mXLeft, 0, mXRight - mXLeft, getHeight());

                // Border
                g2.setColor(new Color(255, 200, 0, 180));
                g2.drawRect(mXLeft, 0, mXRight - mXLeft - 1, getHeight() - 1);

                // Label: centre freq + width
                String label = String.format(java.util.Locale.ROOT, "%.4f MHz  +/-%.1f kHz",
                    mMidHz / 1e6, mBwHz / 2_000.0);
                g2.setColor(new Color(255, 255, 200, 230));
                g2.drawString(label, mXLeft + 4, 14);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    public class PauseItem extends JCheckBoxMenuItem
    {
        private static final long serialVersionUID = 1L;

        private Pausable mPausable;

        public PauseItem(Pausable pausable, String label)
        {
            super(label);

            final boolean paused = pausable.isPaused();

            setSelected(paused);

            mPausable = pausable;

            addActionListener(new ActionListener()
            {
                @Override public void actionPerformed(ActionEvent e)
                {
                    EventQueue.invokeLater(new Runnable()
                    {
                        @Override public void run()
                        {
                            mPausable.setPaused(!paused);
                        }
                    });
                }
            });
        }
    }

    public class ZoomItem extends JSlider
    {
        private static final long serialVersionUID = 1L;

        private long mFrequency;
        private double mWindowOffset;

        public ZoomItem(long frequency, double windowOffset)
        {
            super(NO_ZOOM, MAX_ZOOM, mZoom);

            mFrequency = frequency;
            mWindowOffset = windowOffset;

            Hashtable<Integer, JComponent> labels = new Hashtable<>();
            labels.put(0, new JLabel("1x"));
            labels.put(1, new JLabel("2x"));
            labels.put(2, new JLabel("4x"));
            labels.put(3, new JLabel("8x"));
            labels.put(4, new JLabel("16x"));
            labels.put(5, new JLabel("32x"));
            labels.put(6, new JLabel("64x"));

            setLabelTable(labels);

            setMajorTickSpacing(1);
            setMinorTickSpacing(1);
            setPaintTicks(true);
            setPaintLabels(true);

            this.addChangeListener(new ChangeListener()
            {
                @Override public void stateChanged(ChangeEvent e)
                {
                    setZoom(getValue(), mFrequency, mWindowOffset);
                }
            });
        }
    }

    public class ChannelDisplayItem extends JCheckBoxMenuItem
    {
        private static final long serialVersionUID = 1L;

        private OverlayPanel mOverlayPanel;
        private ChannelDisplay mChannelDisplay;

        public ChannelDisplayItem(OverlayPanel panel, ChannelDisplay display)
        {
            super(display.name());

            mOverlayPanel = panel;

            mChannelDisplay = display;

            setSelected(mOverlayPanel.getChannelDisplay() == mChannelDisplay);

            addActionListener(new ActionListener()
            {
                @Override public void actionPerformed(ActionEvent e)
                {
                    EventQueue.invokeLater(new Runnable()
                    {
                        @Override public void run()
                        {
                            mOverlayPanel.setChannelDisplay(mChannelDisplay);
                        }
                    });
                }
            });
        }
    }

    /**
     * JCheckBoxMenuItem that toggles the per-session discovery overlay display mode.
     */
    public class DiscoveryDisplayItem extends JCheckBoxMenuItem
    {
        private static final long serialVersionUID = 1L;

        private final DiscoveryOverlay mDiscoveryOverlay;
        private final DiscoveryOverlay.DiscoveryDisplay mDisplay;

        public DiscoveryDisplayItem(DiscoveryOverlay overlay, DiscoveryOverlay.DiscoveryDisplay display)
        {
            super(display.name());

            mDiscoveryOverlay = overlay;
            mDisplay = display;

            setSelected(mDiscoveryOverlay.getDiscoveryDisplay() == mDisplay);

            addActionListener(new ActionListener()
            {
                @Override public void actionPerformed(ActionEvent e)
                {
                    EventQueue.invokeLater(new Runnable()
                    {
                        @Override public void run()
                        {
                            mDiscoveryOverlay.setDiscoveryDisplay(mDisplay);
                        }
                    });
                }
            });
        }
    }
}